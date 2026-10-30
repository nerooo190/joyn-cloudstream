package de.joyncloudstream

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI

internal val joynJson = ObjectMapper()

internal fun JsonNode.string(key: String): String? = get(key)
    ?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

internal fun JsonNode.number(key: String): Int? = get(key)
    ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()

internal fun JsonNode.items(key: String): List<JsonNode> = get(key)
    ?.takeIf { it.isArray }?.toList().orEmpty()

internal data class JoynItem(
    val title: String,
    val path: String,
    val kind: String,
    val poster: String? = null,
    val description: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val durationSeconds: Int? = null,
    val background: String? = null,
)

internal data class JoynLane(val title: String, val items: List<JoynItem>)
internal data class JoynHome(val lanes: List<JoynLane>, val hasNext: Boolean)
internal data class JoynDetails(val item: JoynItem, val episodes: List<JoynItem> = emptyList())
internal data class JoynPlayback(val manifest: String, val format: String, val license: String?)

internal class JoynException(message: String) : Exception(message)

internal object JoynParser {
    const val SITE = "https://www.joyn.de"

    /** Accept public Joyn content links only, including links shared by the player. */
    fun contentPath(value: String): String {
        val uri = try { URI(value) } catch (_: Exception) {
            throw JoynException("Ungültiger Joyn-Link.")
        }
        if (uri.isAbsolute && (uri.scheme != "https" ||
                uri.host !in setOf("www.joyn.de", "joyn.de") || uri.userInfo != null ||
                uri.port !in setOf(-1, 443))) {
            throw JoynException("Nur HTTPS-Inhaltslinks von joyn.de werden unterstützt.")
        }
        if (uri.rawAuthority != null && !uri.isAbsolute) {
            throw JoynException("Ungültiger Joyn-Link.")
        }
        val path = uri.path.orEmpty().removePrefix("/play/v2").removePrefix("/play").trimEnd('/')
        if (!Regex("^/(serien|filme)/[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)?$").matches(path)) {
            throw JoynException("Dieser Joyn-Inhaltstyp wird noch nicht unterstützt.")
        }
        return path
    }

    fun kind(path: String): String = when {
        path.startsWith("/filme/") -> "movie"
        path.count { it == '/' } > 2 -> "episode"
        else -> "series"
    }

    fun isFree(node: JsonNode): Boolean {
        val licenses = node.items("licenseTypes").map { it.asText() } + listOfNotNull(node.string("license"))
        val plus = node.items("markings").any { it.asText() == "PLUS" || it.asText() == "PLUS_ONLY" }
        return !plus && licenses.any { it == "AVOD" || it == "FVOD" }
    }

    private fun image(node: JsonNode, vararg fields: String): String? = fields.firstNotNullOfOrNull { key ->
        node.get(key)?.let { it.string("url") ?: it.string("urlCard") }
    }

    fun item(node: JsonNode, parentSeason: Int? = null): JoynItem? {
        val rawPath = node.string("path") ?: node.items("routes").let { routes ->
            (routes.firstOrNull { it.string("deviceClass") == "WEB" }
                ?: routes.firstOrNull { it.string("deviceClass") == "MASTER" })?.string("path")
        } ?: return null
        val path = try { contentPath(rawPath) } catch (_: JoynException) { return null }
        val title = node.string("title") ?: node.get("titles")?.string("DE") ?: return null
        val searchImages = node.items("images")
        val searchPoster = listOf("HERO_PORTRAIT", "COVER_PORTRAIT", "PRIMARY").firstNotNullOfOrNull { type ->
            searchImages.firstOrNull { it.string("type") == type }?.string("url")
        }
        val season = node.get("season")
        val video = node.get("video")
        return JoynItem(
            title = title,
            path = path,
            kind = kind(path),
            poster = image(node, "heroPortrait", "heroPortraitImage", "primaryImage", "cardImage", "heroImageMobile")
                ?: searchPoster ?: image(node, "heroLandscapeImage", "heroImageDesktop"),
            description = node.string("description"),
            year = node.number("productionYear"),
            genres = node.items("genres").mapNotNull { it.string("name") }.ifEmpty {
                node.get("genres")?.items("de")?.map { it.asText() }.orEmpty()
            },
            videoId = video?.string("id"),
            season = parentSeason ?: season?.number("seasonNumber") ?: season?.number("number"),
            episode = node.number("number"),
            durationSeconds = video?.number("duration"),
            background = image(node, "heroImageDesktop", "heroLandscapeImage"),
        )
    }

    fun lanes(blocks: List<JsonNode>): List<JoynLane> = blocks.mapNotNull { block ->
        val items = block.items("assets").filter(::isFree).mapNotNull { item(it) }.distinctBy { it.path }
        if (items.isEmpty()) null else JoynLane(block.string("headline") ?: "Joyn Highlights", items)
    }

    fun playback(node: JsonNode): JoynPlayback {
        val manifest = node.string("manifestUrl")?.takeIf(::https)
            ?: throw JoynException("Joyn liefert kein abspielbares Manifest.")
        val license = node.string("licenseUrl")
        if (license != null && !https(license)) throw JoynException("Ungültige Joyn-Lizenzadresse.")
        val format = node.string("streamingFormat") ?: when {
            URI(manifest).path.endsWith(".mpd") -> "dash"
            URI(manifest).path.endsWith(".m3u8") -> "hls"
            else -> throw JoynException("Unbekanntes Joyn-Streamformat.")
        }
        if (format !in setOf("hls", "dash")) throw JoynException("Unbekanntes Joyn-Streamformat.")
        return JoynPlayback(manifest, format, license)
    }

    private fun https(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
    } catch (_: Exception) { false }

    fun entitlementMessage(code: String?): String = when (code) {
        "ENT_AssetNotAvailableInCountry", "ENT_EUMediaPortability" ->
            "Joyn: Dieser Inhalt ist an deinem Standort nicht verfügbar."
        "ENT_USER_VPN_DETECTED" -> "Joyn hat die Wiedergabe für diese Verbindung gesperrt."
        "ENT_BUSINESS_MODEL_NOT_SUITABLE" -> "Für diesen Inhalt ist ein Joyn-Abonnement erforderlich."
        "ENT_RVOD_Playback_Restricted", "ENT_AccountValidation" ->
            "Dieser Inhalt erfordert eine Anmeldung in der offiziellen Joyn-App."
        "ENT_PINRequired", "ENT_PINSetupRequired", "ENT_PINInvalid", "ENT_YouAreNotOldEnough",
        "ENT_AgeVerificationSetupRequired" ->
            "Dieser Inhalt erfordert die Altersfreigabe in der offiziellen Joyn-App."
        "ENT_AssetNotFound", "ENT_ASSET_NOT_AVAILABLE", "ENT_PublishingRightsNotUsable" ->
            "Dieser Inhalt ist bei Joyn nicht mehr oder noch nicht verfügbar."
        else -> "Joyn hat keine Wiedergabeberechtigung erteilt."
    }
}
