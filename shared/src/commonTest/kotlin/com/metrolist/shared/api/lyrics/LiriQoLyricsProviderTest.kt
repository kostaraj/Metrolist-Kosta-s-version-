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
                assertEquals("LIRIQO/KUGOU", document.meta.lyricSource)
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
                assertTrue(kugou.all { it.provider == "LIRIQO/KUGOU" })
                assertTrue(qq.all { it.provider == "LIRIQO/QQ_MUSIC" })
            } finally {
                client.close()
            }
        }

}
