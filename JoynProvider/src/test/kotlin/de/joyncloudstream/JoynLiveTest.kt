package de.joyncloudstream

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
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
        val home = api.home(1)
        assertTrue("No free homepage cards", home.lanes.any { it.items.isNotEmpty() })
        assertTrue("No next homepage page", home.hasNext)
        assertTrue("Lazy homepage lanes empty", api.home(2).lanes.isNotEmpty())
        val hits = api.search("K11")
        assertTrue("Search returned no series", hits.any { it.kind == "series" })
        val series = home.lanes.flatMap { it.items }.first { it.kind == "series" }
        val detail = api.details(series.path)
        assertTrue("No episodes loaded", detail.episodes.isNotEmpty())
        assertTrue("Episode video IDs missing", detail.episodes.all { !it.videoId.isNullOrBlank() })
        val episode = api.details(detail.episodes.first().path)
        assertNotNull(episode.item.videoId)
        println("Live catalogue OK: ${home.lanes.size} lanes, ${hits.size} search hits, ${detail.episodes.size} episodes.")
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
