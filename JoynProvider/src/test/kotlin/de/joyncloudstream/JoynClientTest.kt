package de.joyncloudstream

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class JoynClientTest {
    private fun json(value: Any) = joynJson.writeValueAsString(value)
    private fun episode(index: Int, license: String = "AVOD") = mapOf(
        "id" to "episode-$index", "title" to "Folge $index", "number" to index,
        "path" to "/serien/beispiel/1-$index-folge", "licenseTypes" to listOf(license),
        "video" to mapOf("id" to "video-$index", "duration" to 1200),
    )

    @Test fun `loads all episode pages instead of trusting the preview or total`() = runBlocking {
        val offsets = mutableListOf<Int>()
        val client = JoynClient(JoynTransport { _, _, body ->
            val request = joynJson.readTree(body)
            val offset = request["variables"]["offset"].asInt()
            offsets += offset
            val batch = if (offset == 0) (1..100).map { episode(it) }
                else listOf(episode(101), episode(102, "SVOD"), episode(103))
            JoynHttpResponse(200, json(mapOf("data" to mapOf("season" to mapOf(
                // Intentionally wrong: a catalogue total need not equal the free episode count.
                "numberOfEpisodes" to 2000, "episodes" to batch,
            )))))
        })
        val result = client.seasonEpisodes("season-1", 1)
        assertEquals(listOf(0, 100), offsets)
        assertEquals(102, result.size)
        assertEquals(103, result.last().episode)
        assertTrue(result.all { it.season == 1 })
    }

    @Test fun `fails explicitly if server ignores pagination instead of returning partial series`() = runBlocking {
        val batch = (1..100).map { episode(it) }
        var calls = 0
        val client = JoynClient(JoynTransport { _, _, _ ->
            calls++
            JoynHttpResponse(200, json(mapOf("data" to mapOf("season" to mapOf("episodes" to batch)))))
        })
        try {
            client.seasonEpisodes("season-1", 1)
            fail("Expected a pagination error")
        } catch (e: JoynException) {
            assertTrue(e.message!!.contains("wiederholt"))
        }
        assertEquals(2, calls)
    }

    @Test fun `geoblock stops before any playlist request`() = runBlocking {
        val urls = mutableListOf<String>()
        val client = JoynClient(JoynTransport { url, _, _ ->
            urls += url
            if (url.contains("auth/anonymous")) JoynHttpResponse(200, """{"access_token":"anonymous","expires_in":3600}""")
            else JoynHttpResponse(400, """[{"code":"ENT_AssetNotAvailableInCountry"}]""")
        })
        try {
            client.playback("video-1")
            fail("Expected geoblock")
        } catch (e: JoynException) { assertTrue(e.message!!.contains("Standort")) }
        assertEquals(2, urls.size)
        assertFalse(urls.any { it.contains("playlist") })
    }

    @Test fun `refreshes expired anonymous session once and uses entitlement only for playlist`() = runBlocking {
        var authCalls = 0
        var entitlementCalls = 0
        val client = JoynClient(JoynTransport { url, headers, _ ->
            when {
                url.contains("auth/anonymous") -> {
                    authCalls++
                    JoynHttpResponse(200, """{"access_token":"anonymous-$authCalls","expires_in":3600}""")
                }
                url.contains("entitlement-token") -> {
                    entitlementCalls++
                    assertEquals("Bearer anonymous-$authCalls", headers["Authorization"])
                    if (entitlementCalls == 1) JoynHttpResponse(401, "{}")
                    else JoynHttpResponse(200, """{"entitlement_token":"granted"}""")
                }
                else -> {
                    assertEquals("Bearer granted", headers["Authorization"])
                    JoynHttpResponse(200, """{"manifestUrl":"https://cdn.example/video.mpd","streamingFormat":"dash","licenseUrl":"https://license.example/widevine"}""")
                }
            }
        })
        val result = client.playback("video-1")
        assertEquals(2, authCalls)
        assertEquals("dash", result.format)
        assertEquals("https://license.example/widevine", result.license)
    }

    @Test fun `playlist denial never reports a playable video`() = runBlocking {
        val client = JoynClient(JoynTransport { url, _, _ ->
            when {
                url.contains("auth/anonymous") -> JoynHttpResponse(200, """{"access_token":"anonymous"}""")
                url.contains("entitlement-token") -> JoynHttpResponse(200, """{"entitlement_token":"granted"}""")
                else -> JoynHttpResponse(403, "{}")
            }
        })
        try {
            client.playback("video-1")
            fail("Expected playlist rejection")
        } catch (e: JoynException) { assertTrue(e.message!!.contains("Signierung")) }
    }

    @Test fun `search refreshes rejected key and reads current indexion response`() = runBlocking {
        var keyCalls = 0
        var searchCalls = 0
        val client = JoynClient(JoynTransport { url, headers, body ->
            if (url.contains("graphql")) {
                keyCalls++
                JoynHttpResponse(200, """{"data":{"searchApiKey":"key-$keyCalls"}}""")
            } else {
                searchCalls++
                assertEquals("key-$keyCalls", headers["x-algolia-api-key"])
                assertEquals("indexion_prod_vod", joynJson.readTree(body)["requests"][0]["indexName"].asText())
                if (searchCalls == 1) JoynHttpResponse(403, "{}")
                else JoynHttpResponse(200, """{"results":[{"hits":[
                    {"titles":{"DE":"K 11"},"routes":[{"deviceClass":"WEB","path":"/serien/k11"}],
                    "license":"AVOD","images":[{"type":"HERO_PORTRAIT","url":"https://img.joyn.de/poster.jpg"}]},
                    {"titles":{"DE":"Paid"},"routes":[{"deviceClass":"WEB","path":"/filme/paid"}],"license":"SVOD"}
                ]}]}""")
            }
        })
        val result = client.search("K11 & Co")
        assertEquals(2, keyCalls)
        assertEquals(listOf("K 11"), result.map { it.title })
        assertEquals("https://img.joyn.de/poster.jpg", result.single().poster)
    }

    @Test fun `graphql failure is not presented as an empty catalogue`() = runBlocking {
        val client = JoynClient(JoynTransport { _, _, _ ->
            JoynHttpResponse(200, """{"data":null,"errors":[{"message":"PersistedQueryNotFound"}]}""")
        })
        try {
            client.home(1)
            fail("Expected API error")
        } catch (e: JoynException) { assertTrue(e.message!!.contains("Katalogschnittstelle")) }
    }
}
