package de.joyncloudstream

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.util.UUID

internal data class JoynHttpResponse(val status: Int, val body: String)

internal fun interface JoynTransport {
    suspend fun post(url: String, headers: Map<String, String>, body: String): JoynHttpResponse
}

/** Public catalogue and anonymous playback API. No user credentials are stored. */
internal class JoynClient(
    private val http: JoynTransport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val headers = mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "Origin" to JoynParser.SITE,
        "Referer" to "${JoynParser.SITE}/",
        "Joyn-Platform" to "web",
        "Joyn-Distribution-Tenant" to "JOYN",
        "Joyn-Country" to "DE",
    )
    private val authLock = Mutex()
    private val searchLock = Mutex()
    private val homeLock = Mutex()
    private val clientId = UUID.randomUUID().toString()
    private var anonymous: Pair<String, Long>? = null
    private var searchKey: Pair<String, Long>? = null
    private var homeCache: Pair<JsonNode, Long>? = null

    private suspend fun post(url: String, body: Any, extra: Map<String, String> = emptyMap()): Pair<Int, JsonNode> {
        val response = http.post(url, headers + extra, joynJson.writeValueAsString(body))
        val json = try { joynJson.readTree(response.body) } catch (_: Exception) {
            throw JoynException("Joyn antwortet unerwartet (HTTP ${response.status}).")
        } ?: throw JoynException("Joyn hat eine leere Antwort gesendet.")
        return response.status to json
    }

    private suspend fun query(operation: String, variables: Map<String, Any?> = emptyMap()): JsonNode {
        val hash = queries[operation] ?: throw JoynException("Unbekannte Joyn-Abfrage.")
        val (status, root) = post("https://api.joyn.de/graphql", mapOf(
            "operationName" to operation,
            "variables" to variables,
            "extensions" to mapOf("persistedQuery" to mapOf("version" to 1, "sha256Hash" to hash)),
        ), mapOf("x-api-key" to PUBLIC_WEB_API_KEY) +
            if (operation == "Blocks") mapOf("Authorization" to "Bearer ${token()}") else emptyMap())
        if (status !in 200..299) throw JoynException("Joyn-Katalog nicht erreichbar (HTTP $status).")
        if (root.items("errors").isNotEmpty()) {
            val code = root.items("errors").firstOrNull()?.get("extensions")?.string("code")
            throw JoynException(if (code == "NOT_FOUND") "Dieser Joyn-Inhalt wurde nicht gefunden."
                else "Die Joyn-Katalogschnittstelle hat sich geändert oder ist vorübergehend nicht verfügbar.")
        }
        return root.get("data")?.takeUnless { it.isNull }
            ?: throw JoynException("Joyn hat keine Katalogdaten geliefert.")
    }

    suspend fun home(page: Int): JoynHome {
        require(page >= 1)
        val root = homeLock.withLock {
            homeCache?.takeIf { it.second > now() }?.first ?: query(
                "LandingPageClient", mapOf("path" to "/neu-beliebt"),
            ).get("page")?.takeUnless { it.isNull }?.also { homeCache = it to now() + 300_000 }
            ?: throw JoynException("Die Joyn-Startseite ist nicht verfügbar.")
        }
        val lazy = root.items("lazyBlocks").mapNotNull { it.string("id") }
        if (page == 1) return JoynHome(JoynParser.lanes(root.items("blocks")), lazy.isNotEmpty())
        val start = (page - 2).toLong() * HOME_BATCH
        if (start >= lazy.size) return JoynHome(emptyList(), false)
        val ids = lazy.drop(start.toInt()).take(HOME_BATCH)
        val blocks = query("Blocks", mapOf("ids" to ids)).items("blocks")
        return JoynHome(JoynParser.lanes(blocks), start + ids.size < lazy.size)
    }

    private suspend fun algoliaKey(): String = searchLock.withLock {
        searchKey?.takeIf { it.second > now() }?.first ?: query("AlgoliaApiKey")
            .string("searchApiKey")?.also { searchKey = it to now() + 600_000 }
            ?: throw JoynException("Joyn hat keinen Suchschlüssel geliefert.")
    }

    suspend fun search(term: String): List<JoynItem> {
        if (term.isBlank()) return emptyList()
        // Refresh an expired server-side search key once; avoid silent empty results.
        repeat(2) { attempt ->
            val key = algoliaKey()
            val params = mapOf(
                "query" to term.trim(), "hitsPerPage" to "50",
                "numericFilters" to joynJson.writeValueAsString(listOf(
                    "timeslot.start <= ${now()}", "timeslot.end >= ${now()}",
                )),
            ).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            val (status, root) = post(
                "https://ffqrv35svv-dsn.algolia.net/1/indexes/*/queries",
                mapOf("requests" to listOf(mapOf("indexName" to "indexion_prod_vod", "params" to params))),
                mapOf("x-algolia-application-id" to "FFQRV35SVV", "x-algolia-api-key" to key),
            )
            if (status in setOf(401, 403) && attempt == 0) {
                searchLock.withLock { searchKey = null }
            } else {
                val result = root.items("results").firstOrNull()
                if (status !in 200..299 || result == null || result.has("error")) {
                    throw JoynException("Joyn-Suche nicht verfügbar (HTTP $status).")
                }
                return result.items("hits").filter(JoynParser::isFree)
                    .mapNotNull { JoynParser.item(it) }.distinctBy { it.path }
            }
        }
        throw JoynException("Joyn-Suchschlüssel ist abgelaufen.")
    }

    suspend fun details(url: String): JoynDetails {
        val path = JoynParser.contentPath(url)
        val kind = JoynParser.kind(path)
        val (operation, field) = when (kind) {
            "series" -> "SeriesDetailNewPageStatic" to "series"
            "episode" -> "EpisodeDetailPageStatic" to "episode"
            else -> "PageMovieDetailStatic" to "movie"
        }
        val node = query(operation, mapOf("path" to path)).get("page")?.get(field)
            ?.takeUnless { it.isNull } ?: throw JoynException("Dieser Joyn-Inhalt ist nicht verfügbar.")
        if (!JoynParser.isFree(node)) throw JoynException("Dieser Inhalt gehört zum kostenpflichtigen Joyn-Angebot.")
        val item = JoynParser.item(node) ?: throw JoynException("Unvollständige Joyn-Inhaltsdaten.")
        if (kind != "series") return JoynDetails(item)
        // The detail response embeds only a few preview episodes per season.
        val seasons = node.items("freeSeasons").distinctBy { it.string("id") }
        val episodes = seasons.flatMap { season ->
            val id = season.string("id") ?: return@flatMap emptyList()
            seasonEpisodes(id, season.number("number"))
        }.distinctBy { it.path }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        if (episodes.isEmpty()) throw JoynException("Für diese Serie sind aktuell keine kostenlosen Folgen verfügbar.")
        return JoynDetails(item, episodes)
    }

    internal suspend fun seasonEpisodes(id: String, number: Int?): List<JoynItem> {
        val result = mutableListOf<JoynItem>()
        val seen = mutableSetOf<String>()
        var offset = 0
        while (true) {
            val season = query("Season", mapOf("id" to id, "first" to EPISODE_BATCH,
                "offset" to offset, "licenseFilter" to "FREE")).get("season")
                ?.takeUnless { it.isNull } ?: throw JoynException("Eine Joyn-Staffel konnte nicht geladen werden.")
            val batch = season.items("episodes")
            val fresh = batch.filter { node ->
                val identity = node.string("id") ?: node.string("path")
                    ?: throw JoynException("Joyn liefert eine Folge ohne Kennung.")
                seen.add(identity)
            }
            if (batch.isNotEmpty() && fresh.isEmpty()) {
                throw JoynException("Joyn wiederholt dieselbe Folgenseite. Bitte später erneut versuchen.")
            }
            result += fresh.filter(JoynParser::isFree).mapNotNull { JoynParser.item(it, number) }
            offset += batch.size
            // Do not use numberOfEpisodes: it may include unavailable/paid episodes.
            if (batch.size < EPISODE_BATCH) return result
            if (offset > 50_000) throw JoynException("Joyn meldet zu viele Folgen für eine einzelne Staffel.")
        }
    }

    private suspend fun token(): String = authLock.withLock {
        anonymous?.takeIf { it.second > now() }?.first ?: run {
            val (status, data) = post("https://auth.joyn.de/auth/anonymous", mapOf(
                "client_id" to clientId, "client_name" to "web", "anon_device_id" to clientId,
            ))
            if (status !in 200..299) throw JoynException("Joyn-Sitzung konnte nicht gestartet werden (HTTP $status).")
            val token = data.string("access_token") ?: throw JoynException("Joyn hat keine anonyme Sitzung erteilt.")
            val ttl = (data.number("expires_in") ?: 300).coerceAtLeast(60)
            anonymous = token to now() + (ttl - 30) * 1_000L
            token
        }
    }

    suspend fun playback(videoId: String): JoynPlayback {
        if (!Regex("[a-zA-Z0-9_-]{1,128}").matches(videoId)) throw JoynException("Ungültige Joyn-Video-ID.")
        var entitlement: String? = null
        repeat(2) { attempt ->
            if (entitlement != null) return@repeat
            val (status, data) = post("https://entitlement.p7s1.io/api/user/entitlement-token",
                mapOf("content_id" to videoId, "content_type" to "VOD"),
                mapOf("Authorization" to "Bearer ${token()}"))
            if (status == 401 && attempt == 0) {
                authLock.withLock { anonymous = null }
            } else {
                val error = if (data.isArray) data.firstOrNull()?.string("code") else data.string("code")
                if (status !in 200..299 || error != null) throw JoynException(JoynParser.entitlementMessage(error))
                entitlement = data.string("entitlement_token")
                    ?: throw JoynException("Joyn hat keine Wiedergabeberechtigung geliefert.")
            }
        }
        val granted = entitlement ?: throw JoynException("Joyn-Sitzung ist abgelaufen.")
        // Normal token-authenticated request. Current web-player signing is not implemented.
        // A successful entitlement alone does NOT establish successful video playback.
        val (status, playlist) = post("https://api.vod-prd.s.joyn.de/v1/asset/$videoId/playlist", mapOf(
            "manufacturer" to "unknown", "model" to "unknown", "platform" to "browser",
            "maxSecurityLevel" to 1, "streamingFormat" to "dash", "protectionSystem" to "widevine",
            "encryptionScheme" to "cenc", "enableDolbyAudio" to false,
            "enableMultiAudio" to true, "enableSubtitles" to true, "variantName" to "default", "version" to "v1",
        ), mapOf("Authorization" to "Bearer $granted"))
        if (status !in 200..299) throw JoynException(
            "Joyn-Playlist abgelehnt (HTTP $status). Die aktuelle Wiedergabe-Signierung wird noch nicht unterstützt. Bitte die offizielle Joyn-App verwenden.",
        )
        return JoynParser.playback(playlist)
    }

    companion object {
        internal const val EPISODE_BATCH = 100
        private const val HOME_BATCH = 6
        // Public identifier shipped by Joyn's web frontend; not a user credential.
        private const val PUBLIC_WEB_API_KEY = "4f0fd9f18abbe3cf0e87fdb556bc39c8"
        private val queries = mapOf(
            "LandingPageClient" to "d126aa8da9aae9a7abdabe014e8641ce54c17fd0a39c37f8a7bbcab258821508",
            "Blocks" to "b136380c332b3c0ec5b8e074546bad5f12715dfb77076296ed72d4977e2a7f58",
            "AlgoliaApiKey" to "21a962eb1b3f05c32b85cf8d015f1814563af3d4aede35d6e2f211838fdcfb61",
            "SeriesDetailNewPageStatic" to "e867452d17ef36e5c077db5cdcad7563a9aebede497c24ac8fae779723bc462d",
            "EpisodeDetailPageStatic" to "c4bcacee94d38133e87879dad8d69bd8a74c7326262a1848cceb964b871c1551",
            "PageMovieDetailStatic" to "9ae6bcd8c45a5e350438d1cc415a022fe053e938c93438509f60ae3abb425fa7",
            "Season" to "ee2396bb1b7c9f800e5cefd0b341271b7213fceb4ebe18d5a30dab41d703009f",
        )
    }
}
