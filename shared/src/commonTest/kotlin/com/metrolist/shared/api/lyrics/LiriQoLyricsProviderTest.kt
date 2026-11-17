package com.metrolist.shared.api.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiriQoLyricsProviderTest {
    @Test
    fun prefersNativeWordTimingAndPreservesAbsoluteTimestamps() =
        runBlocking {
            val response =
                """
                {
                  "metadata":{"title":"Song","artist":"Artist","duration":120},
                  "primary":{
                    "provider":"kugou_legato",
                    "upstreamProvider":"KuGou",
                    "syncLevel":"word",
                    "timed":[{
                      "id":0,"text":"Hello world","start":1000,"end":3000,
                      "words":[
                        {"text":"Hello","start":1000,"end":1800},
                        {"text":"world","start":1900,"end":3000}
                      ]
                    }]
                  },
                  "tracks":[
                    {
                      "provider":"qq_portato","upstreamProvider":"QQ Music","syncLevel":"line",
                      "timed":[{"id":0,"text":"Line fallback","start":1000,"end":3000}]
                    }
                  ]
                }
                """.trimIndent()
            val client = HttpClient(MockEngine { request ->
                assertEquals("/v1/lyrics", request.url.encodedPath)
                assertEquals("Song", request.url.parameters["title"])
                assertEquals("Artist", request.url.parameters["artist"])
                assertEquals("120", request.url.parameters["duration"])
                respond(response, HttpStatusCode.OK)
            })
            try {
                val mlx = assertNotNull(LiriQoLyricsProvider(client).getLyrics("Song", "Artist", 120_000))
                val document = MlxParser.parse(mlx)
                val line = document.lines.single()
                assertEquals("Hello world", line.text)
                assertEquals(1_000L, line.time)
                assertEquals(3_000L, line.endTime)
                val words = assertNotNull(line.words)
                assertEquals(listOf("Hello", "world"), words.map { it.text })
                assertEquals(listOf(1.0, 1.9), words.map { it.startTime })
                assertEquals(listOf(1.8, 3.0), words.map { it.endTime })
                assertEquals("LiriQo/KUGOU", document.meta.lyricSource)
            } finally {
                client.close()
            }
        }

    @Test
    fun usesLineSyncedTrackWhenNoNativeWordsAreAvailable() =
        runBlocking {
            val response =
                """
                {
                  "primary":{
                    "provider":"qq_portato",
                    "upstreamProvider":"QQ Music",
                    "syncLevel":"line",
                    "timed":[{"id":0,"text":"Line only","start":2500,"end":4500}]
                  },
                  "tracks":[]
                }
                """.trimIndent()
            val client = HttpClient(MockEngine { respond(response) })
            try {
                val mlx = assertNotNull(LiriQoLyricsProvider(client).getLyrics("Song", "Artist", null))
                val line = MlxParser.parse(mlx).lines.single()
                assertEquals("Line only", line.text)
                assertEquals(2_500L, line.time)
                assertEquals(4_500L, line.endTime)
                assertNull(line.words)
            } finally {
                client.close()
            }
        }

    @Test
    fun returnsNullWhenApiHasNoMatchingTracks() =
        runBlocking {
            val client = HttpClient(MockEngine { respond("""{"primary":null,"tracks":[],"count":0}""") })
            try {
                assertNull(LiriQoLyricsProvider(client).getLyrics("Song", "Artist", null))
            } finally {
                client.close()
            }
        }

    @Test
    fun returnsOnlyBestSyncVariantPerSourceAndSharesOneRequestAcrossSources() =
        runBlocking {
            val response =
                """
                {
                  "metadata":{"title":"Song","artist":"Artist"},
                  "primary":null,
                  "tracks":[
                    {
                      "provider":"kugou_legato",
                      "upstreamProvider":"KuGou",
                      "syncLevel":"word",
                      "plain":"Hello world",
                      "timed":[
                        {
                          "id":0,"text":"Hello world","start":1000,"end":3000,
                          "words":[
                            {"text":"Hello ","start":1000,"end":1800},
                            {"text":"world","start":1900,"end":3000}
                          ]
                        }
                      ]
                    },
                    {
                      "provider":"qq_portato",
                      "upstreamProvider":"QQ Music",
                      "syncLevel":"line",
                      "plain":"Another lyric",
                      "timed":[{"id":0,"text":"Another lyric","start":4000,"end":6000}]
                    }
                  ]
                }
                """.trimIndent()
            var requests = 0
            val client = HttpClient(MockEngine {
                requests++
                respond(response, HttpStatusCode.OK)
            })
            try {
                val provider = LiriQoLyricsProvider(client)
                val kugou = provider.getLyricsResults("Song", "Artist", 120_000, provider = com.metrolist.shared.model.LyricsProvider.LIRIQO_KUGOU)
                val qq = provider.getLyricsResults("Song", "Artist", 120_000, provider = com.metrolist.shared.model.LyricsProvider.LIRIQO_QQ_MUSIC)

                assertEquals(1, kugou.size)
                val parsedKugou = kugou.map { MlxParser.parse(it.lyrics) }
                assertTrue(parsedKugou.any { document -> document.lines.any { !it.words.isNullOrEmpty() } })
                assertTrue(parsedKugou.none { document -> document.lines.any { it.time > 0L && it.words.isNullOrEmpty() } })
                assertTrue(parsedKugou.none { document -> document.lines.isNotEmpty() && document.lines.all { it.time == 0L } })

                assertEquals(1, qq.size)
                assertEquals(1, requests)
                assertTrue(kugou.all { it.provider == "LiriQo/KUGOU" })
                assertTrue(qq.all { it.provider == "LiriQo/QQ_MUSIC" })
            } finally {
                client.close()
            }
        }


    @Test
    fun usesSameSubproviderLineLyricsToGroupSyllablesWithoutChangingTheirTimings() =
        runBlocking {
            val response =
                """
                {
                  "primary": {
                    "provider": "apple_music_liriqo",
                    "syncLevel": "syllable",
                    "timed": [{
                      "id": 7,
                      "text": "be au ti ful song",
                      "start": 1000,
                      "end": 4000,
                      "words": [
                        {"text": "be", "start": 1000, "end": 1200},
                        {"text": "au", "start": 1200, "end": 1450},
                        {"text": "ti", "start": 1450, "end": 1650},
                        {"text": "ful", "start": 1650, "end": 2200},
                        {"text": "song", "start": 2500, "end": 4000}
                      ]
                    }]
                  },
                  "tracks": [{
                    "provider": "apple_music_liriqo",
                    "syncLevel": "line",
                    "timed": [{
                      "id": 7,
                      "text": "beautiful song",
                      "start": 1000,
                      "end": 4000
                    }]
                  }]
                }
                """.trimIndent()
            val client = HttpClient(MockEngine { respond(response, HttpStatusCode.OK) })
            try {
                val mlx =
                    assertNotNull(
                        LiriQoLyricsProvider(client).getLyricsResults(
                            "Song",
                            "Artist",
                            null,
                            provider = com.metrolist.shared.model.LyricsProvider.LIRIQO_APPLE_MUSIC,
                        ).singleOrNull()?.lyrics,
                    )
                val line = MlxParser.parse(mlx).lines.single()
                val words = assertNotNull(line.words)

                assertEquals("beautiful song", line.text)
                assertEquals(listOf("be", "au", "ti", "ful", "song"), words.map { it.text })
                assertEquals(listOf(1.0, 1.2, 1.45, 1.65, 2.5), words.map { it.startTime })
                assertEquals(listOf(false, false, false, true, false), words.map { it.hasTrailingSpace })
            } finally {
                client.close()
            }
        }

    @Test
    fun alignsSyllableFragmentsByLineOrderWhenLineIdsAreMissingOrDifferent() =
        runBlocking {
            val response =
                """
                {
                  "primary": {
                    "provider": "kugou_legato",
                    "syncLevel": "word",
                    "timed": [{
                      "text": "be au ti ful song",
                      "start": 1000,
                      "end": 4000,
                      "words": [
                        {"text":"be","start":1000,"end":1200},
                        {"text":"au","start":1200,"end":1450},
                        {"text":"ti","start":1450,"end":1650},
                        {"text":"ful","start":1650,"end":2200},
                        {"text":"song","start":2500,"end":4000}
                      ]
                    }]
                  },
                  "tracks": [{
                    "provider": "kugou_legato",
                    "syncLevel": "line",
                    "timed": [{"id":99,"text":"beautiful song","start":1000,"end":4000}]
                  }]
                }
                """.trimIndent()
            val client = HttpClient(MockEngine { respond(response, HttpStatusCode.OK) })
            try {
                val mlx =
                    assertNotNull(
                        LiriQoLyricsProvider(client).getLyricsResults(
                            "Song",
                            "Artist",
                            null,
                            provider = com.metrolist.shared.model.LyricsProvider.LIRIQO_KUGOU,
                        ).singleOrNull()?.lyrics,
                    )
                val line = MlxParser.parse(mlx).lines.single()
                assertEquals("beautiful song", line.text)
                assertEquals(listOf("be", "au", "ti", "ful", "song"), line.words!!.map { it.text })
                assertEquals(listOf(false, false, false, true, false), line.words!!.map { it.hasTrailingSpace })
                assertEquals(listOf(1.0, 1.2, 1.45, 1.65, 2.5), line.words!!.map { it.startTime })
            } finally {
                client.close()
            }
        }

    @Test
    fun usesPlainTextAsTheWordBoundaryReferenceWhenNoLineTrackExists() =
        runBlocking {
            val response =
                """
                {
                  "primary": {
                    "provider": "qq_portato",
                    "syncLevel": "syllable",
                    "plain": "beautiful song",
                    "timed": [{
                      "id": 4,
                      "text": "be au ti ful song",
                      "start": 1000,
                      "end": 4000,
                      "words": [
                        {"text":"be","start":1000,"end":1200},
                        {"text":"au","start":1200,"end":1450},
                        {"text":"ti","start":1450,"end":1650},
                        {"text":"ful","start":1650,"end":2200},
                        {"text":"song","start":2500,"end":4000}
                      ]
                    }]
                  },
                  "tracks": []
                }
                """.trimIndent()
            val client = HttpClient(MockEngine { respond(response, HttpStatusCode.OK) })
            try {
                val mlx =
                    assertNotNull(
                        LiriQoLyricsProvider(client).getLyricsResults(
                            "Song",
                            "Artist",
                            null,
                            provider = com.metrolist.shared.model.LyricsProvider.LIRIQO_QQ_MUSIC,
                        ).singleOrNull()?.lyrics,
                    )
                val line = MlxParser.parse(mlx).lines.single()
                assertEquals("beautiful song", line.text)
                assertEquals(listOf(false, false, false, true, false), line.words!!.map { it.hasTrailingSpace })
                assertEquals(listOf(1.0, 1.2, 1.45, 1.65, 2.5), line.words!!.map { it.startTime })
            } finally {
                client.close()
            }
        }

    @Test
    fun preservesSyllableTimestampsInsideWordsAndSpacesBetweenWords() =
        runBlocking {
            val response =
                """
                {
                  "primary": {
                    "provider": "apple_music_liriqo",
                    "syncLevel": "syllable",
                    "timed": [{
                      "id": 0,
                      "text": "beautiful song",
                      "start": 1000,
                      "end": 4000,
                      "words": [
                        {"text": "be", "start": 1000, "end": 1200},
                        {"text": "au", "start": 1200, "end": 1450},
                        {"text": "ti", "start": 1450, "end": 1650},
                        {"text": "ful", "start": 1650, "end": 2200},
                        {"text": "song", "start": 2500, "end": 4000}
                      ]
                    }]
                  },
                  "tracks": []
                }
                """.trimIndent()
            val client = HttpClient(MockEngine { respond(response, HttpStatusCode.OK) })
            try {
                val mlx =
                    assertNotNull(
                        LiriQoLyricsProvider(client).getLyricsResults(
                            "Song",
                            "Artist",
                            null,
                            provider = com.metrolist.shared.model.LyricsProvider.LIRIQO_APPLE_MUSIC,
                        ).singleOrNull()?.lyrics,
                    )
                val line = MlxParser.parse(mlx).lines.single()
                val words = assertNotNull(line.words)

                assertEquals("LiriQo/APPLE_MUSIC", MlxParser.parse(mlx).meta.lyricSource)
                assertEquals("beautiful song", line.text)
                assertEquals(listOf("be", "au", "ti", "ful", "song"), words.map { it.text })
                assertEquals(listOf(1.0, 1.2, 1.45, 1.65, 2.5), words.map { it.startTime })
                assertEquals(listOf(1.2, 1.45, 1.65, 2.2, 4.0), words.map { it.endTime })
                assertEquals(listOf(false, false, false, true, false), words.map { it.hasTrailingSpace })
            } finally {
                client.close()
            }
        }


    @Test
    fun separatesParenthesizedBackgroundVocalsAndKeepsTheirWordSpacing() =
        runBlocking {
            val response =
                """
                {
                  "primary": {
                    "provider": "apple_music_liriqo",
                    "syncLevel": "syllable",
                    "timed": [{
                      "id": 0,
                      "text": "I swore my loyalty to me, myself, and I (Me, myself, I)",
                      "start": 36576,
                      "end": 40620,
                      "words": [
                        {"text": "I ", "start": 36576, "end": 36773},
                        {"text": "swore ", "start": 36773, "end": 37011},
                        {"text": "my ", "start": 37011, "end": 37291},
                        {"text": "loyalty ", "start": 37291, "end": 38021},
                        {"text": "to ", "start": 38021, "end": 38256},
                        {"text": "me, ", "start": 38256, "end": 38677},
                        {"text": "(Me, ", "start": 38677, "end": 39032},
                        {"text": "myself, ", "start": 39032, "end": 39513},
                        {"text": "myself, ", "start": 39513, "end": 39976},
                        {"text": "and ", "start": 39976, "end": 40245},
                        {"text": "I ", "start": 40245, "end": 40620},
                        {"text": "I)", "start": 40620, "end": 40700}
                      ]
                    }]
                  },
                  "tracks": []
                }
                """.trimIndent()
            val client = HttpClient(MockEngine { respond(response, HttpStatusCode.OK) })
            try {
                val mlx =
                    assertNotNull(
                        LiriQoLyricsProvider(client).getLyricsResults(
                            "Song",
                            "Artist",
                            null,
                            provider = com.metrolist.shared.model.LyricsProvider.LIRIQO_APPLE_MUSIC,
                        ).singleOrNull()?.lyrics,
                    )
                val line = MlxParser.parse(mlx).lines.single()
                assertEquals("I swore my loyalty to me,", line.text)
                assertEquals(
                    listOf("I", "swore", "my", "loyalty", "to", "me,", "myself,", "and", "I"),
                    assertNotNull(line.words).filterNot { it.isBackground }.map { it.text },
                )
                assertEquals(
                    listOf("Me,", "myself,", "I"),
                    assertNotNull(line.words).filter { it.isBackground }.map { it.text },
                )
                assertEquals(
                    listOf(true, true, false),
                    line.words.filter { it.isBackground }.map { it.hasTrailingSpace },
                )
                assertEquals(
                    listOf(38.677, 39.513, 40.62),
                    line.words.filter { it.isBackground }.map { it.startTime },
                )
            } finally {
                client.close()
            }
        }


    @Test
    fun preservesSingerAgentAnnotationsFromNormalizedLiriQoLines() =
        runBlocking {
            val response =
                """
                {
                  "primary": {
                    "provider": "apple_music_liriqo",
                    "syncLevel": "word",
                    "agents": {
                      "v1": {"type": "person", "name": "Singer One"},
                      "v2": {"type": "person", "name": "Singer Two"}
                    },
                    "timed": [{
                      "id": 0,
                      "text": "Hello there Hey",
                      "start": 1000,
                      "end": 3000,
                      "agent": "v2",
                      "words": [
                        {"text": "Hello ", "start": 1000, "end": 2000},
                        {"text": "there ", "start": 2000, "end": 2500},
                        {"text": "Hey", "start": 2500, "end": 3000, "isBackground": true}
                      ]
                    }]
                  },
                  "tracks": []
                }
                """.trimIndent()
            val client = HttpClient(MockEngine { respond(response, HttpStatusCode.OK) })
            try {
                val mlx =
                    assertNotNull(
                        LiriQoLyricsProvider(client).getLyricsResults(
                            "Song",
                            "Artist",
                            null,
                            provider = com.metrolist.shared.model.LyricsProvider.LIRIQO_APPLE_MUSIC,
                        ).singleOrNull()?.lyrics,
                    )
                val document = MlxParser.parse(mlx)
                assertEquals("v2", document.lines.single().agent)
                assertEquals("Singer Two", document.agents["v2"]?.name)
                assertEquals("Hello there", document.lines.single().text)
                assertEquals("Hey", document.lines.single().words!!.single { it.isBackground }.text)
            } finally {
                client.close()
            }
        }
    
    @Test
    fun keepsHyphenatedSyllableFragmentsJoinedWhenNormalizingLiriQo() =
        runBlocking {
            val response =
                """
                {
                  "primary": {
                    "provider": "kugou_legato",
                    "syncLevel": "syllable",
                    "timed": [{
                      "id": 0,
                      "text": "Ooh- ooh- ooh- ooh",
                      "start": 1000,
                      "end": 3000,
                      "words": [
                        {"text":"Ooh- ","start":1000,"end":1500},
                        {"text":"ooh- ","start":1500,"end":2000},
                        {"text":"ooh- ","start":2000,"end":2500},
                        {"text":"ooh","start":2500,"end":3000}
                      ]
                    }]
                  },
                  "tracks": []
                }
                """.trimIndent()
            val client = HttpClient(MockEngine { respond(response, HttpStatusCode.OK) })
            try {
                val mlx =
                    assertNotNull(
                        LiriQoLyricsProvider(client).getLyricsResults(
                            "Song",
                            "Artist",
                            null,
                            provider = com.metrolist.shared.model.LyricsProvider.LIRIQO_KUGOU,
                        ).singleOrNull()?.lyrics,
                    )
                val line = MlxParser.parse(mlx).lines.single()
                assertEquals("Ooh-ooh-ooh-ooh", line.text)
                assertEquals(listOf("Ooh-", "ooh-", "ooh-", "ooh"), line.words!!.map { it.text })
                assertEquals(listOf(false, false, false, false), line.words!!.map { it.hasTrailingSpace })
                assertEquals(listOf(1.0, 1.5, 2.0, 2.5), line.words!!.map { it.startTime })
            } finally {
                client.close()
            }
        }

    @Test
    fun prefersOriginalAppleMusicTtmlForNativeSyllableBoundaries() =
        runBlocking {
            val response =
                """
                {
                  "primary": {
                    "provider": "apple_music_liriqo",
                    "syncLevel": "syllable",
                    "ttml": "<tt xmlns=\\\"http://www.w3.org/ns/ttml\\\"><body><div><p begin=\\\"1s\\\" end=\\\"4s\\\"><span begin=\\\"1s\\\" end=\\\"1.2s\\\">be</span><span begin=\\\"1.2s\\\" end=\\\"1.45s\\\">au</span><span begin=\\\"1.45s\\\" end=\\\"1.65s\\\">ti</span><span begin=\\\"1.65s\\\" end=\\\"2.2s\\\">ful</span> <span begin=\\\"2.5s\\\" end=\\\"4s\\\">song</span></p></div></body></tt>",
                    "timed": [{
                      "id": 0,
                      "text": "beautiful song",
                      "start": 1000,
                      "end": 4000,
                      "words": [
                        {"text": "beautiful ", "start": 1100, "end": 2400},
                        {"text": "song", "start": 2500, "end": 4000}
                      ]
                    }]
                  },
                  "tracks": []
                }
                """.trimIndent()
            val client = HttpClient(MockEngine { respond(response, HttpStatusCode.OK) })
            try {
                val mlx =
                    assertNotNull(
                        LiriQoLyricsProvider(client).getLyricsResults(
                            "Song",
                            "Artist",
                            null,
                            provider = com.metrolist.shared.model.LyricsProvider.LIRIQO_APPLE_MUSIC,
                        ).singleOrNull()?.lyrics,
                    )
                val line = MlxParser.parse(mlx).lines.single()
                val words = assertNotNull(line.words)

                assertEquals("beautiful song", line.text)
                assertEquals(listOf("be", "au", "ti", "ful", "song"), words.map { it.text })
                assertEquals(listOf(1.0, 1.2, 1.45, 1.65, 2.5), words.map { it.startTime })
                assertEquals(listOf(1.2, 1.45, 1.65, 2.2, 4.0), words.map { it.endTime })
                assertEquals(listOf(false, false, false, true, false), words.map { it.hasTrailingSpace })
                assertEquals("LiriQo/APPLE_MUSIC", MlxParser.parse(mlx).meta.lyricSource)
            } finally {
                client.close()
            }
        }
}
