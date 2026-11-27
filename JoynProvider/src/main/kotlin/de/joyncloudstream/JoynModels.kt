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
    val accessHint: String? = null,
)

internal data class JoynCataloguePage(val items: List<JoynItem>, val hasNext: Boolean)
internal data class JoynDetails(val item: JoynItem, val episodes: List<JoynItem> = emptyList())
internal data class JoynPlayback(val manifest: String, val format: String, val license: String?)

internal class JoynException(message: String) : Exception(message)

internal object JoynParser {
    const val SITE = "https://www.joyn.de"
    val artworkHeaders = mapOf("Accept" to "image/jpeg", "Referer" to "$SITE/")

    private val portraitTypes = listOf(
        "COVER_PORTRAIT", "HERO_PORTRAIT", "IMAGE_VERTICAL_CLEAN", "VOD_ART_PORTRAIT",
        "BANNER_L_1_PORTRAIT", "POSTER",
    )
    private val landscapeTypes = listOf(
        "HERO_LANDSCAPE", "PRIMARY", "IMAGE_HEADER_POSTER", "IMAGE_HORIZONTAL_COVER_CLEAN",
        "COVER_LANDSCAPE", "TEASER_LANDSCAPE", "VOD_ART", "ICONIC", "BANNER_L_1", "LIVE_STILL",
    )

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

    private fun accessHint(node: JsonNode): String? {
        val licenses = node.items("licenseTypes").map { it.asText() } + listOfNotNull(node.string("license"))
        val markings = node.get("markings")
        return when {
            node.items("markings").any { it.asText() in setOf("PLUS", "PLUS_ONLY") } ||
                markings?.path("isPlusOnly")?.path("value")?.asBoolean() == true -> "Joyn+"
            licenses.any { it == "AVOD" || it == "FVOD" } -> null
            "RVOD" in licenses -> "Joyn-Login"
            "SVOD" in licenses -> "Joyn+"
            else -> null
        }
    }

    private fun imageUrl(value: String?, profile: String): String? {
        if (value == null || !https(value)) return null
        val uri = URI(value)
        if (uri.host != "img.joyn.de" || uri.rawPath.isNullOrEmpty()) return value
        // Joyn's CDN requires an image profile for bare index URLs.
        val path = uri.rawPath.substringBefore("/profile:").trimEnd('/')
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "https://${uri.rawAuthority}$path/profile:$profile$query"
    }

    private fun image(node: JsonNode, profile: String, vararg fields: String): String? =
        fields.firstNotNullOfOrNull { field ->
            val art = node.get(field) ?: return@firstNotNullOfOrNull null
            listOf(art.takeIf { it.isTextual }?.asText(), art.string("url"), art.string("urlCard"),
                art.string("urlMobile")).firstNotNullOfOrNull { imageUrl(it, profile) }
        }

    private fun indexedImage(images: List<JsonNode>, types: List<String>, profile: String): String? =
        types.firstNotNullOfOrNull { type ->
            images.filter { it.string("type") == type }
                .firstNotNullOfOrNull { imageUrl(it.string("url"), profile) }
        }

    fun item(node: JsonNode, parentSeason: Int? = null): JoynItem? {
        val rawPath = node.string("path") ?: node.items("routes").let { routes ->
            (routes.firstOrNull { it.string("deviceClass") == "WEB" }
                ?: routes.firstOrNull { it.string("deviceClass") == "MASTER" })?.string("path")
        } ?: return null
        val path = try { contentPath(rawPath) } catch (_: JoynException) { return null }
        val title = node.string("title") ?: node.get("titles")?.string("DE") ?: return null
        val kind = kind(path)
        val searchImages = node.items("images")
        val landscape = image(node, "nextgen-web-herolandscape-1920x", "heroImageDesktop", "heroLandscapeImage")
            ?: indexedImage(searchImages, landscapeTypes, "nextgen-web-herolandscape-1920x")
        val thumbnail = image(node, "nextgen-web-livestill-503x283",
            "cardImage", "primaryImage", "heroImageMobile", "heroImageDesktop", "heroLandscapeImage")
            ?: indexedImage(searchImages, landscapeTypes, "nextgen-web-livestill-503x283")
        val portrait = image(node, "original", "heroPortrait", "heroPortraitImage", "coverPortrait")
            ?: indexedImage(searchImages, portraitTypes, "original")
        val fallback = searchImages.firstNotNullOfOrNull { art ->
            if (art.string("type").orEmpty().contains("LOGO")) null
            else imageUrl(art.string("url"), "nextgen-web-livestill-503x283")
        }
        val season = node.get("season")
        val video = node.get("video")
        return JoynItem(
            title = title,
            path = path,
            kind = kind,
            poster = if (kind == "episode") thumbnail ?: portrait ?: fallback else portrait ?: thumbnail ?: fallback,
            description = node.string("description"),
            year = node.number("productionYear"),
            genres = node.items("genres").mapNotNull { it.string("name") }.ifEmpty {
                node.get("genres")?.items("de")?.map { it.asText() }.orEmpty()
            },
            videoId = video?.string("id"),
            season = parentSeason ?: season?.number("seasonNumber") ?: season?.number("number"),
            episode = node.number("number"),
            durationSeconds = video?.number("duration"),
            background = landscape,
            accessHint = accessHint(node),
        )
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
