package de.joyncloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class JoynProvider : MainAPI() {
    override var mainUrl = JoynParser.SITE
    override var name = "Joyn (experimentell)"
    override var lang = "de"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf("/neu-beliebt" to "Joyn Mediathek")

    private val client = JoynClient(JoynTransport { url, headers, body ->
        val response = app.post(url, headers = headers,
            requestBody = body.toRequestBody("application/json".toMediaType()))
        JoynHttpResponse(response.code, response.text)
    })

    private suspend fun <T> showErrors(block: suspend () -> T): T = try { block() } catch (error: JoynException) {
        throw ErrorLoadingException(error.message ?: "Joyn ist nicht verfügbar.")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = showErrors {
        val home = client.home(page)
        newHomePageResponse(home.lanes.map { lane ->
            HomePageList(lane.title, lane.items.map { it.searchResponse() })
        }, home.hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> = showErrors {
        if (query.trim().startsWith("https://")) {
            listOf(client.details(query.trim()).item.searchResponse())
        } else client.search(query).map { it.searchResponse() }
    }

    private fun JoynItem.searchResponse(): SearchResponse = if (kind == "series") {
        newTvSeriesSearchResponse(title, "$mainUrl$path", TvType.TvSeries) { posterUrl = poster }
    } else {
        newMovieSearchResponse(title, "$mainUrl$path", TvType.Movie) { posterUrl = poster }
    }

    override suspend fun load(url: String): LoadResponse = showErrors {
        val detail = client.details(url)
        val item = detail.item
        if (item.kind == "series") {
            val episodes = detail.episodes.map { ep ->
                // Only stable IDs and a public URL are saved; session tokens are fetched on play.
                newEpisode(playData(ep)) {
                    name = ep.title
                    season = ep.season
                    episode = ep.episode
                    posterUrl = ep.poster
                    description = ep.description
                }
            }
            newTvSeriesLoadResponse(item.title, "$mainUrl${item.path}", TvType.TvSeries, episodes) {
                posterUrl = item.poster
                backgroundPosterUrl = item.background
                plot = item.description
                year = item.year
                tags = item.genres
            }
        } else {
            newMovieLoadResponse(item.title, "$mainUrl${item.path}", TvType.Movie, playData(item)) {
                posterUrl = item.poster
                backgroundPosterUrl = item.background
                plot = item.description
                year = item.year
                tags = item.genres
                duration = item.durationSeconds?.div(60)
            }
        }
    }

    private fun playData(item: JoynItem): String = joynJson.writeValueAsString(mapOf(
        "url" to "$mainUrl${item.path}", "videoId" to item.videoId,
    ))

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = showErrors {
        if (isCasting) throw JoynException("Joyn-Wiedergabe wird beim Casten noch nicht unterstützt.")
        val saved = try { joynJson.readTree(data) } catch (_: Exception) {
            throw JoynException("Ungültige Joyn-Wiedergabedaten. Bitte den Inhalt erneut öffnen.")
        }
        val url = saved?.string("url") ?: throw JoynException("Der Joyn-Inhaltslink fehlt.")
        JoynParser.contentPath(url)
        val id = saved.string("videoId") ?: client.details(url).item.videoId
            ?: throw JoynException("Für diesen Joyn-Inhalt ist kein Video verfügbar.")
        val playback = client.playback(id)
        val type = if (playback.format == "dash") ExtractorLinkType.DASH else ExtractorLinkType.M3U8
        val streamHeaders = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
        val link = if (playback.license != null) {
            // Android's Widevine CDM requests Joyn's license; no keys are extracted.
            newDrmExtractorLink(name, "Joyn Widevine", playback.manifest, type,
                UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")) {
                licenseUrl = playback.license
                keyRequestParameters = hashMapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
                headers = streamHeaders
                referer = "$mainUrl/"
            }
        } else {
            newExtractorLink(name, "Joyn", playback.manifest, type) {
                headers = streamHeaders
                referer = "$mainUrl/"
            }
        }
        callback(link)
        true
    }
}
