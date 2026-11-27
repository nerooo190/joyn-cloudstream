package de.joyncloudstream

import org.junit.Assert.*
import org.junit.Test

class JoynParserTest {
    @Test fun `normalizes shared player link to public content link`() {
        assertEquals("/serien/k11/1-2-beispiel", JoynParser.contentPath(
            "https://www.joyn.de/play/v2/serien/k11/1-2-beispiel?from=home#watch"))
    }

    @Test fun `rejects foreign origins malformed urls and non content paths`() {
        listOf("https://joyn.de.evil.example/serien/k11", "https://evil.example/serien/k11",
            "https://evil@joyn.de/serien/k11", "//evil.example/serien/k11", "http://joyn.de/serien/k11",
            "https://joyn.de:8080/serien/k11", "/serien/../filme/film", "/play/live-tv", "not a url").forEach {
            try {
                JoynParser.contentPath(it)
                fail("Accepted invalid link: $it")
            } catch (_: JoynException) { /* expected */ }
        }
    }

    @Test fun `rejects incomplete manifests and invalid license urls`() {
        listOf("{}", """{"manifestUrl":"http://cdn.example/a.mpd"}""",
            """{"manifestUrl":"https://cdn.example/a.mpd","licenseUrl":"file:///secret"}""",
            """{"manifestUrl":"https://cdn.example/a","streamingFormat":"unknown"}""").forEach {
            try {
                JoynParser.playback(joynJson.readTree(it))
                fail("Accepted invalid playback response")
            } catch (_: JoynException) { /* expected */ }
        }
    }

    @Test fun `recognizes direct hls and preserves complete signed urls`() {
        val manifest = "https://cdn.example/master.m3u8?token=example&expires=42"
        val result = JoynParser.playback(joynJson.readTree("""{"manifestUrl":"$manifest"}"""))
        assertEquals(manifest, result.manifest)
        assertEquals("hls", result.format)
        assertNull(result.license)
    }

    @Test fun `uses portrait artwork first and sizes alternate image types`() {
        val node = joynJson.readTree("""{"title":"Film","path":"/filme/film","images":[
            {"type":"IMAGE_HEADER_POSTER","url":"https://img.joyn.de/ingest/hero.jpg"},
            {"type":"VOD_ART_PORTRAIT","url":"https://img.joyn.de/gn/cover.jpg"}
        ]}""")
        val item = JoynParser.item(node)!!
        assertEquals("https://img.joyn.de/gn/cover.jpg/profile:original", item.poster)
        assertEquals("https://img.joyn.de/ingest/hero.jpg/profile:nextgen-web-herolandscape-1920x", item.background)
    }

    @Test fun `uses episode thumbnails and retains encoded image paths`() {
        val node = joynJson.readTree("""{"title":"Folge","path":"/serien/serie/folge",
            "cardImage":{"url":"invalid","urlMobile":"https://img.joyn.de/path%2Btest.png/profile:original?x=2"},
            "primaryImage":{"url":"https://img.joyn.de/backup.jpg"}}""")
        assertEquals("https://img.joyn.de/path%2Btest.png/profile:nextgen-web-livestill-503x283?x=2",
            JoynParser.item(node)?.poster)
    }

    @Test fun `catalogue metadata describes access while retaining protected titles`() {
        val nodes = joynJson.readTree("""{"assets":[
            {"title":"Free","path":"/filme/free","licenseTypes":["AVOD"]},
            {"title":"Paid","path":"/filme/paid","licenseTypes":["SVOD"]},
            {"title":"Channel","path":"/mediatheken/sat1","licenseTypes":["AVOD"]},
            {"title":"Plus","path":"/filme/plus","licenseTypes":["AVOD"],"markings":["PLUS_ONLY"]},
            {"title":"Login","path":"/filme/login","license":"RVOD"}
        ]}""")
        val items = nodes.items("assets").mapNotNull { JoynParser.item(it) }
        assertEquals(listOf("Free", "Paid", "Plus", "Login"), items.map { it.title })
        assertEquals(listOf(null, "Joyn+", "Joyn+", "Joyn-Login"), items.map { it.accessHint })
    }
}
