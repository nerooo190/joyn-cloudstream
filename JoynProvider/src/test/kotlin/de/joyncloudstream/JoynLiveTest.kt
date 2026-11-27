package de.joyncloudstream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in contract checks against Joyn, using exactly the client's production requests. */
class JoynLiveTest {
    private fun client() = JoynClient(JoynTransport { url, headers, body ->
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            JoynHttpResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally { conn.disconnect() }
    })

    @Test fun `live catalogue search and all seasons`() = runBlocking {
        assumeTrue(System.getenv("JOYN_LIVE_TESTS") == "1")
        val api = client()
        val rows = JoynSection.entries.associateWith { api.home(it, 1) }
        assertTrue("Empty homepage row", rows.values.all { it.items.isNotEmpty() })
        val series = rows.getValue(JoynSection.SERIES)
        assertTrue("No next homepage page", series.hasNext)
        assertTrue("Series row has overlapping pages", api.home(JoynSection.SERIES, 2).items
            .map { it.path }.intersect(series.items.map { it.path }.toSet()).isEmpty())
        val hits = api.search("K11")
        assertTrue("Search returned no series", hits.any { it.kind == "series" })
        val detail = api.details("/serien/hochzeit-auf-den-ersten-blick-spezial")
        assertTrue("No episodes loaded", detail.episodes.isNotEmpty())
        assertTrue("Episode video IDs missing", detail.episodes.all { !it.videoId.isNullOrBlank() })
        val episode = api.details(detail.episodes.first().path)
        assertNotNull(episode.item.videoId)
        // The Android compile classpath omits ImageIO, which is available on the host test JVM.
        val decodeImage = Class.forName("javax.imageio.ImageIO").getMethod("read", InputStream::class.java)
        val artwork = rows.values.flatMap { it.items.take(3) } + hits.take(2) + detail.episodes.take(2)
        artwork.forEach { item ->
            val url = item.poster ?: error("Missing poster for ${item.path}")
            val connection = URL(url).openConnection()
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            JoynParser.artworkHeaders.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            assertNotNull("Unusable poster for ${item.path}", connection.getInputStream().use {
                decodeImage.invoke(null, it)
            })
        }
        println("Live catalogue OK: ${rows.size} rows, ${hits.size} search hits, ${artwork.size} decoded images.")
    }

    @Test fun `live series and film rows reach the end of the current catalogue`() = runBlocking {
        assumeTrue(System.getenv("JOYN_FULL_CATALOG_TESTS") == "1")
        val api = client()
        listOf(JoynSection.SERIES, JoynSection.MOVIES).map { section ->
            async(Dispatchers.IO) {
                val paths = mutableSetOf<String>()
                var posters = 0
                var page = 0
                do {
                    val result = api.home(section, ++page)
                    result.items.forEach {
                        assertTrue("Duplicate catalogue path ${it.path}", paths.add(it.path))
                        if (it.poster != null) posters++
                    }
                } while (result.hasNext)
                assertTrue("Catalogue unexpectedly small for $section", paths.size > 1_000)
                assertTrue("Too few poster URLs in $section", posters >= paths.size * 0.99)
                println("$section: ${paths.size} catalogue entries across $page pages, $posters with artwork URLs.")
            }
        }.awaitAll()
        Unit
    }

    @Test fun `live playback diagnostic`() = runBlocking {
        assumeTrue(System.getenv("JOYN_PLAYBACK_TESTS") == "1")
        val api = client()
        val id = System.getenv("JOYN_TEST_VIDEO_ID") ?: "a_ppl0fdxccba"
        try {
            val playback = api.playback(id)
            assertTrue(playback.manifest.startsWith("https://"))
            println("Playlist received (${playback.format}); Android playback and license remain untested.")
        } catch (e: JoynException) {
            // Diagnostic only: a regional denial is a useful result, never a playback success.
            println("Playback NOT verified: ${e.message}")
        }
    }
}
