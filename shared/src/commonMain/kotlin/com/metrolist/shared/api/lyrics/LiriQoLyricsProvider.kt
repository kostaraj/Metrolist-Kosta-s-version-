/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.AgentType
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.LyricsWithProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * One shared LiriQo request backs all eleven independently configurable source entries.
 * Each upstream track returns only its best available sync format.
 */
class LiriQoLyricsProvider(
    private val httpClient: HttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestMutex = Mutex()
    private val responseCache = linkedMapOf<String, CachedResponse>()

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
    ): String? {
        val results = mutableListOf<LyricsWithProvider>()
        for (provider in LyricsProvider.liriQoProviders) {
            results += getLyricsResults(title, artist, duration, album, provider)
        }
        return results.maxByOrNull { result ->
            when (timingKind(result.lyrics)) {
                LyricsTimingKind.WORD_SYNCED -> 3
                LyricsTimingKind.LINE_SYNCED -> 2
                LyricsTimingKind.UNSYNCED -> 1
            }
        }?.lyrics
    }

    suspend fun getLyricsResults(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        provider: LyricsProvider,
    ): List<LyricsWithProvider> {
        val sourceId = sourceId(provider) ?: return emptyList()
        if (title.isBlank()) return emptyList()

        val response = responseFor(title, artist, duration, album) ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull() ?: return emptyList()
        if (root["error"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) return emptyList()

        val metadata = root["metadata"] as? JsonObject
        val primary = root["primary"] as? JsonObject
        val tracks = root["tracks"] as? JsonArray
        val candidates =
            buildList {
                primary?.let(::add)
                tracks?.forEach { (it as? JsonObject)?.let(::add) }
            }.distinctBy { track ->
                listOf(
                    track.string("provider").orEmpty(),
                    track.string("syncLevel").orEmpty(),
                    track["timed"]?.toString().orEmpty(),
                    track.string("plain").orEmpty(),
                ).joinToString("|")
            }

        return candidates
            .asSequence()
            .filter { it.string("provider") == sourceId }
            .flatMap { track -> parseTrackVariants(track, title, artist, metadata).asSequence() }
            .distinctBy { it.lyrics to it.provider }
            .sortedByDescending { result ->
                when (timingKind(result.lyrics)) {
                    LyricsTimingKind.WORD_SYNCED -> 3
                    LyricsTimingKind.LINE_SYNCED -> 2
                    LyricsTimingKind.UNSYNCED -> 1
                }
            }.take(1).toList()
    }

    private suspend fun responseFor(
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
    ): String? {
        val key = listOf(title.trim(), artist.trim(), duration?.toString().orEmpty(), album.orEmpty()).joinToString("\u0000")
        return requestMutex.withLock {
            responseCache[key]?.takeIf { it.createdAt.elapsedNow() < RESPONSE_CACHE_TTL }?.let {
                return@withLock it.body
            }
            val response =
                try {
                    httpClient
                        .prepareGet("$BASE_URL/lyrics") {
                            expectMissingLyrics()
                            parameter("title", title)
                            if (artist.isNotBlank()) parameter("artist", artist)
                            duration?.takeIf { it > 0 }?.let { parameter("duration", it / 1_000L) }
                            album?.takeIf { it.isNotBlank() }?.let { parameter("album", it) }
                            parameter("full", "1")
                        }.readLyricsText(MAX_RESPONSE_BYTES)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }

            // Cache misses too, so all eleven providers still share a single failed lookup.
            responseCache[key] = CachedResponse(response, TimeSource.Monotonic.markNow())
            while (responseCache.size > MAX_CACHED_QUERIES) {
                responseCache.remove(responseCache.keys.first())
            }
            response
        }
    }

    private fun parseTrackVariants(
        track: JsonObject,
        requestedTitle: String,
        requestedArtist: String,
        metadata: JsonObject?,
    ): List<LyricsWithProvider> {
        val sourceId = track.string("provider") ?: return emptyList()
        val sourceLabel = "LIRIQO/${sourceSlug(sourceId)}"
        val timed = track["timed"] as? JsonArray
        val lines =
            timed?.mapNotNull { element ->
                val line = element as? JsonObject ?: return@mapNotNull null
                val start = line.long("start")?.takeIf { it >= 0L } ?: return@mapNotNull null
                val end = line.long("end")?.takeIf { it > start }
                val text = line.string("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val id = line.long("id")?.takeIf { it >= 0L } ?: 0L
                val words = line["words"] as? JsonArray ?: track.wordsFor(id)
                TimedLine(
                    start,
                    end,
                    text,
                    words?.mapNotNull(::parseWord).orEmpty(),
                    line.string("agent") ?: line.string("agentId"),
                )
            }?.sortedBy { it.startMs }.orEmpty()

        if (lines.isNotEmpty()) {
            if (lines.any { it.words.isNotEmpty() }) {
                val wordLyrics = buildTimedLyrics(lines, sourceLabel, requestedTitle, requestedArtist, metadata, track, includeWords = true)
                if (timingKind(wordLyrics) == LyricsTimingKind.WORD_SYNCED) {
                    return listOf(LyricsWithProvider(wordLyrics, sourceLabel))
                }
            }

            val lineLyrics = buildTimedLyrics(lines, sourceLabel, requestedTitle, requestedArtist, metadata, track, includeWords = false)
            return listOf(LyricsWithProvider(lineLyrics, sourceLabel))
        }

        val plain = track.string("plain")?.takeIf { it.isNotBlank() }
        if (plain != null) {
            val builder =
                MlxBuilder()
                    .meta(sourceLabel)
                    .song(
                        title = metadata?.string("title") ?: requestedTitle,
                        artist = metadata?.string("artist") ?: requestedArtist,
                        songwriters = track["songwriters"]?.stringArray()?.takeIf { it.isNotEmpty() },
                    )
            plain.lineSequence().filter { it.isNotBlank() }.forEach { builder.unsyncedLine(it.trim()) }
            val plainLyrics = builder.build()
            return listOf(LyricsWithProvider(plainLyrics, sourceLabel))
        }
        return emptyList()
    }

    private fun buildTimedLyrics(
        lines: List<TimedLine>,
        source: String,
        requestedTitle: String,
        requestedArtist: String,
        metadata: JsonObject?,
        track: JsonObject,
        includeWords: Boolean,
    ): String {
        val builder =
            MlxBuilder()
                .meta(source)
                .song(
                    title = metadata?.string("title") ?: requestedTitle,
                    artist = metadata?.string("artist") ?: requestedArtist,
                )
        lines.mapNotNull { it.agentId }.distinct().forEach { agentId ->
            val agent = (track["agents"] as? JsonObject)?.get(agentId) as? JsonObject
            val type = agent?.string("type")?.let(AgentType::fromString) ?: AgentType.PERSON
            builder.agent(agentId, type, agent?.string("name"))
        }
        lines.forEachIndexed { index, line ->
            val inferredEnd =
                lines.getOrNull(index + 1)?.startMs
                    ?: line.words.maxOfOrNull { it.endMs }
                    ?: line.startMs + 5_000L
            val endMs = line.endMs ?: inferredEnd.coerceAtLeast(line.startMs + 1L)
            val validWords =
                if (includeWords) {
                    line.words.filter { word ->
                        word.startMs >= line.startMs && word.endMs > word.startMs && word.endMs <= endMs
                    }.sortedBy { it.startMs }
                } else {
                    emptyList()
                }
            val backgroundSource = BACKGROUND_PARENTHESIS.find(line.text)?.groupValues?.get(1).orEmpty()
            val mainSource = BACKGROUND_PARENTHESIS.replace(line.text, " ").replace(WHITESPACE, " ").trim()
            // Parenthesized background vocals can overlap the main vocal. Do not toggle a
            // background state from "(" to ")": timed main-vocal words may occur between
            // two background words. Match the parenthesized phrase to timed words instead.
            val explicitBackgroundIndexes = validWords.indices.filter { validWords[it].isBackground }.toSet()
            val backgroundIndexes =
                explicitBackgroundIndexes.takeIf { it.isNotEmpty() }
                    ?: findBackgroundWordIndexes(validWords, backgroundSource)
            val mainTimedWords = validWords.filterIndexed { index, _ -> index !in backgroundIndexes }
            val backgroundTimedWords = validWords.filterIndexed { index, _ -> index in backgroundIndexes }
                .map { it.copy(text = it.text.replace("(", "").replace(")", "").trim()) }
                .filter { it.text.isNotEmpty() }
            val mlxWords = buildMlxWords(mainTimedWords, mainSource)
            val backgroundWords = buildMlxWords(backgroundTimedWords, backgroundSource)
            val outputMainText = mainSource.ifBlank { line.text }
            if (mlxWords.isNotEmpty()) {
                builder.line(
                    startMs = line.startMs,
                    endMs = endMs,
                    agentId = line.agentId,
                    words = mlxWords,
                    backgroundWords = backgroundWords.takeIf { it.isNotEmpty() },
                )
            } else {
                builder.line(line.startMs, endMs, outputMainText, agentId = line.agentId,
                    background = BACKGROUND_PARENTHESIS.find(line.text)?.groupValues?.get(1))
            }
        }
        return builder.build()
    }

    /**
     * Finds the timed fragments that spell the parenthesized background phrase.
     * Search backwards so repeated words in overlapping main/background vocals
     * resolve to the later matching occurrence, leaving earlier main-vocal words intact.
     */
    private fun findBackgroundWordIndexes(words: List<TimedWord>, backgroundText: String): Set<Int> {
        val phraseWords = backgroundText.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (phraseWords.isEmpty()) return emptySet()
        val selected = mutableListOf<Int>()
        var searchBefore = words.lastIndex
        for (phraseWord in phraseWords.asReversed()) {
            val normalizedPhraseWord = phraseWord.trim('(', ')')
            val match = (searchBefore downTo 0).firstOrNull { index ->
                words[index].text.trim().trim('(', ')').equals(normalizedPhraseWord, ignoreCase = true)
            } ?: return emptySet()
            selected += match
            searchBefore = match - 1
        }
        return selected.toSet()
    }

    /**
     * Keeps each upstream timing marker, but derives spaces from the provider's original
     * lyric text rather than assuming every timed fragment is a complete word.
     */
    private fun buildMlxWords(words: List<TimedWord>, sourceText: String): List<MlxWord> {
        var searchFrom = 0
        return words.mapIndexedNotNull { index, word ->
            val text = word.text.trim()
            if (text.isEmpty()) return@mapIndexedNotNull null
            val matchStart = sourceText.indexOf(text, searchFrom, ignoreCase = true)
            val matchEnd = if (matchStart >= 0) matchStart + text.length else searchFrom + text.length
            val nextWordStart =
                words.getOrNull(index + 1)?.text?.trim()?.let { nextText ->
                    sourceText.indexOf(nextText, matchEnd.coerceIn(0, sourceText.length), ignoreCase = true)
                } ?: -1
            val separatorEnd = if (nextWordStart >= 0) nextWordStart else matchEnd.coerceIn(0, sourceText.length)
            val hasSeparator =
                matchStart >= 0 &&
                    sourceText.substring(matchEnd.coerceIn(0, sourceText.length), separatorEnd).any(Char::isWhitespace)
            searchFrom = matchEnd.coerceAtMost(sourceText.length)
            MlxWord(
                text = text,
                startMs = word.startMs,
                endMs = word.endMs,
                hasTrailingSpace = hasSeparator,
            )
        }
    }

    private fun timingKind(mlx: String): LyricsTimingKind {
        val document = runCatching { MlxParser.parse(mlx) }.getOrNull()
        if (document == null || document.lines.isEmpty()) return LyricsTimingKind.UNSYNCED
        if (document.lines.any { !it.words.isNullOrEmpty() }) return LyricsTimingKind.WORD_SYNCED
        return if (document.lines.any { it.time > 0L || it.endTime != null }) {
            LyricsTimingKind.LINE_SYNCED
        } else {
            LyricsTimingKind.UNSYNCED
        }
    }

    private fun sourceId(provider: LyricsProvider): String? =
        when (provider) {
            LyricsProvider.LIRIQO_APPLE_MUSIC -> "apple_music_liriqo"
            LyricsProvider.LIRIQO_MUSIXMATCH -> "musixmatch"
            LyricsProvider.LIRIQO_UNISON -> "unison_community"
            LyricsProvider.LIRIQO_NETEASE -> "netease"
            LyricsProvider.LIRIQO_PETITLYRICS -> "petitlyrics"
            LyricsProvider.LIRIQO_KUGOU -> "kugou_legato"
            LyricsProvider.LIRIQO_KUWO -> "kuwo"
            LyricsProvider.LIRIQO_MIGU -> "migu"
            LyricsProvider.LIRIQO_QQ_MUSIC -> "qq_portato"
            LyricsProvider.LIRIQO_LRCLIB -> "lrclib"
            LyricsProvider.LIRIQO_YOUTUBE_MUSIC -> "ytm_line"
            else -> null
        }

    private fun sourceSlug(id: String): String =
        when (id) {
            "apple_music_liriqo" -> "APPLE_MUSIC"
            "musixmatch" -> "MUSIXMATCH"
            "unison_community" -> "UNISON"
            "netease" -> "NETEASE"
            "petitlyrics" -> "PETITLYRICS"
            "kugou_legato" -> "KUGOU"
            "kuwo" -> "KUWO"
            "migu" -> "MIGU"
            "qq_portato" -> "QQ_MUSIC"
            "lrclib" -> "LRCLIB"
            "ytm_line" -> "YOUTUBE_MUSIC"
            else -> id.uppercase()
        }

    private fun JsonObject.wordsFor(lineId: Long): JsonArray? {
        val words = this["words"] ?: return null
        return when (words) {
            is JsonArray -> words.getOrNull(lineId.toInt()) as? JsonArray
            is JsonObject -> words[lineId.toString()] as? JsonArray
            else -> null
        }
    }

    private fun parseWord(element: JsonElement): TimedWord? {
        val word = element as? JsonObject ?: return null
        val text = word.string("text")?.takeIf { it.isNotEmpty() } ?: return null
        val start = word.long("start")?.takeIf { it >= 0L } ?: return null
        val end = word.long("end")?.takeIf { it > start } ?: return null
        val isBackground = word["isBackground"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true
        return TimedWord(text, start, end, isBackground)
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonElement.stringArray(): List<String> =
        (this as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    private fun JsonObject.stringArray(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    private data class CachedResponse(
        val body: String?,
        val createdAt: TimeMark,
    )

    private data class TimedLine(
        val startMs: Long,
        val endMs: Long?,
        val text: String,
        val words: List<TimedWord>,
        val agentId: String? = null,
    )

    private data class TimedWord(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val isBackground: Boolean = false,
    )

    private companion object {
        val BACKGROUND_PARENTHESIS = Regex("\\(([^()]*)\\)")
        val WHITESPACE = Regex("\\s+")
        const val BASE_URL = "https://api.liriqo-alfarrizi.my.id/v1"
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_CACHED_QUERIES = 6
        val RESPONSE_CACHE_TTL = 20.seconds
    }
}
