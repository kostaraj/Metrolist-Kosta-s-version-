/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * LiriQo aggregates native word-synced sources (including KuGou KRC and QQ Music QRC)
 * and exposes their timestamps as normalized absolute milliseconds.
 */
class LiriQoLyricsProvider(
    private val httpClient: HttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
    ): String? {
        if (title.isBlank()) return null

        val response =
            httpClient
                .prepareGet("$BASE_URL/lyrics") {
                    expectMissingLyrics()
                    parameter("title", title)
                    if (artist.isNotBlank()) parameter("artist", artist)
                    duration?.takeIf { it > 0 }?.let { parameter("duration", it / 1_000L) }
                    album?.takeIf { it.isNotBlank() }?.let { parameter("album", it) }
                }.readLyricsText(MAX_RESPONSE_BYTES) ?: return null

        val root = json.parseToJsonElement(response).jsonObject
        if (root["error"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) return null

        val primary = root["primary"] as? JsonObject
        val tracks = root["tracks"] as? JsonArray
        val candidates =
            buildList {
                primary?.let(::add)
                tracks?.forEach { (it as? JsonObject)?.let(::add) }
            }.distinctBy { track ->
                track["provider"]?.jsonPrimitive?.contentOrNull.orEmpty() to
                    track["timed"]?.toString().orEmpty()
            }

        val parsed =
            candidates.mapNotNull { track ->
                parseTrack(track, title, artist, root["metadata"] as? JsonObject)
            }

        // Prefer verified native word timestamps over line-only results, regardless
        // of the API's primary selection. Keep the API's order as the tie-breaker.
        return parsed.maxByOrNull { it.syncQuality }?.mlx
            ?: candidates.firstNotNullOfOrNull { track ->
                track["lrc"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                    lrcToMlx(it, title, artist, source = "LiriQo")
                }
            }
    }

    private fun parseTrack(
        track: JsonObject,
        requestedTitle: String,
        requestedArtist: String,
        metadata: JsonObject?,
    ): ParsedTrack? {
        val providerId = track.string("provider")
        val upstream = track.string("upstreamProvider")
        val source = upstream?.let { "LiriQo ($it)" } ?: providerId?.let { "LiriQo ($it)" } ?: "LiriQo"
        val timed = track["timed"] as? JsonArray
        if (timed.isNullOrEmpty()) {
            val plain = track.string("plain")?.takeIf { it.isNotBlank() } ?: return null
            val builder =
                MlxBuilder()
                    .meta(source)
                    .song(
                        title = metadata?.string("title") ?: requestedTitle,
                        artist = metadata?.string("artist") ?: requestedArtist,
                    )
            plain.lineSequence().filter { it.isNotBlank() }.forEach { builder.unsyncedLine(it.trim()) }
            return ParsedTrack(builder.build(), 0)
        }

        val lines =
            timed.mapNotNull { element ->
                val line = element as? JsonObject ?: return@mapNotNull null
                val start = line.long("start")?.takeIf { it >= 0L } ?: return@mapNotNull null
                val end = line.long("end")?.takeIf { it > start }
                val text = line.string("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val id = line.long("id")?.takeIf { it >= 0L } ?: 0L
                val words = line["words"] as? JsonArray ?: track.wordsFor(id)
                TimedLine(start, end, text, words?.mapNotNull(::parseWord).orEmpty())
            }.sortedBy { it.startMs }

        if (lines.isEmpty()) return null

        val builder =
            MlxBuilder()
                .meta(source)
                .song(
                    title = metadata?.string("title") ?: requestedTitle,
                    artist = metadata?.string("artist") ?: requestedArtist,
                    songwriters = track["songwriters"]?.stringArray()?.takeIf { it.isNotEmpty() },
                )

        var hasWordTiming = false
        lines.forEachIndexed { index, line ->
            val inferredEnd =
                lines.getOrNull(index + 1)?.startMs
                    ?: line.words.maxOfOrNull { it.endMs }
                    ?: line.startMs + 5_000L
            val endMs = line.endMs ?: inferredEnd.coerceAtLeast(line.startMs + 1L)
            val validWords =
                line.words
                    .filter { word ->
                        word.startMs >= line.startMs && word.endMs > word.startMs && word.endMs <= endMs
                    }
                    .sortedBy { it.startMs }

            val mlxWords =
                validWords.map { word ->
                    val trailingSpace = word.text.lastOrNull()?.isWhitespace() == true
                    MlxWord(
                        text = word.text.trimEnd(),
                        startMs = word.startMs,
                        endMs = word.endMs,
                        hasTrailingSpace = trailingSpace,
                    )
                }.filter { it.text.isNotEmpty() }
            if (mlxWords.isNotEmpty()) {
                hasWordTiming = true
                builder.line(startMs = line.startMs, endMs = endMs, words = mlxWords)
            } else {
                builder.line(line.startMs, endMs, line.text)
            }
        }

        return ParsedTrack(builder.build(), if (hasWordTiming) 2 else 1)
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
        return TimedWord(text, start, end)
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull

    private fun JsonElement.stringArray(): List<String> =
        (this as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    private data class TimedLine(
        val startMs: Long,
        val endMs: Long?,
        val text: String,
        val words: List<TimedWord>,
    )

    private data class TimedWord(
        val text: String,
        val startMs: Long,
        val endMs: Long,
    )

    private data class ParsedTrack(
        val mlx: String,
        val syncQuality: Int,
    )

    private companion object {
        const val BASE_URL = "https://api.liriqo-alfarrizi.my.id/v1"
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
