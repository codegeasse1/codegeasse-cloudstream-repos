package com.miruro

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MiruroProvider : MainAPI() {
    override var mainUrl = "https://www.miruro.to"
    override var name = "Miruro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // ---------------------------------------------------------------
    // CONFIRMED from env2.js:
    //   VITE_PIPE_OBF_KEY = "71951034f8fbcf53d89db52ceb3dc22c"
    //   32 hex chars = 16 bytes = AES-128-GCM
    //
    // Encryption structure confirmed from captured responses:
    //   - All responses share same 9-byte base64 prefix after decode
    //   - First 12 bytes of decoded blob = GCM nonce/IV
    //   - Remaining bytes = ciphertext + 16-byte GCM auth tag
    // ---------------------------------------------------------------
    private val PIPE_KEY_HEX = "71951034f8fbcf53d89db52ceb3dc22c"

    private val pipeKey: SecretKeySpec by lazy {
        val keyBytes = PIPE_KEY_HEX.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        SecretKeySpec(keyBytes, "AES")
    }

    private fun decryptPipeResponse(encryptedBase64Url: String): String? {
        return try {
            // Convert URL-safe base64 → standard base64 with padding
            val standardB64 = encryptedBase64Url
                .trim()
                .replace('-', '+')
                .replace('_', '/')
                .let { it + "=".repeat((4 - it.length % 4) % 4) }

            val encrypted = Base64.decode(standardB64, Base64.DEFAULT)

            // First 12 bytes = GCM nonce
            val iv = encrypted.sliceArray(0..11)
            // Rest = ciphertext + 16-byte auth tag
            val ciphertext = encrypted.sliceArray(12 until encrypted.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, pipeKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ---------------------------------------------------------------
    // PIPE REQUEST
    // Plain HTTP GET + client-side AES-128-GCM decrypt.
    // No WebView needed — fast and reliable.
    // ---------------------------------------------------------------
    private suspend fun pipeRequest(payload: String): String? {
        val rawBase64 = Base64.encodeToString(payload.toByteArray(), Base64.NO_WRAP)
        val encodedPayload = URLEncoder.encode(rawBase64, "UTF-8")
        val apiUrl = "$mainUrl/api/secure/pipe?e=$encodedPayload"

        return try {
            val encryptedResponse = app.get(
                apiUrl,
                headers = mapOf(
                    "Accept" to "*/*",
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl
                )
            ).text.trim()

            decryptPipeResponse(encryptedResponse)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ---------------------------------------------------------------
    // JSON DATA CLASSES — search/list response
    // ---------------------------------------------------------------
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MiruroSearchResponse(
        @JsonProperty("data") val data: MiruroSearchResponse? = null,
        @JsonProperty("page") val page: MiruroSearchResponse? = null,
        @JsonProperty("results") val results: List<MiruroMedia>? = null,
        @JsonProperty("media") val media: List<MiruroMedia>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MiruroMedia(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: MiruroTitle? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("cover") val cover: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MiruroTitle(
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("userPreferred") val userPreferred: String? = null
    )

    private fun extractMediaList(response: MiruroSearchResponse?): List<MiruroMedia> {
        if (response == null) return emptyList()
        if (!response.results.isNullOrEmpty()) return response.results
        if (!response.media.isNullOrEmpty()) return response.media
        if (response.data != null) return extractMediaList(response.data)
        if (response.page != null) return extractMediaList(response.page)
        return emptyList()
    }

    // ---------------------------------------------------------------
    // SSR DATA — confirmed in plain HTML, no decryption needed
    // ---------------------------------------------------------------
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SsrData(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: MiruroTitle? = null,
        @JsonProperty("coverImage") val coverImage: CoverImage? = null,
        @JsonProperty("bannerImage") val bannerImage: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("episodes") val episodeCount: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CoverImage(
        @JsonProperty("extraLarge") val extraLarge: String? = null,
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("medium") val medium: String? = null
    )

    private fun extractSsrData(html: String): SsrData? {
        val json = Regex(
            """window\.__SSR_DATA__\s*=\s*(\{.*?\});?\s*(?:</script>|window\.__SSR_CONFIG__)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.get(1) ?: return null
        return tryParseJson<SsrData>(json)
    }

    // ---------------------------------------------------------------
    // EPISODES response data classes
    // ---------------------------------------------------------------
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesResponse(
        @JsonProperty("data") val data: EpisodesResponse? = null,
        @JsonProperty("episodes") val episodes: List<EpisodeItem>? = null,
        @JsonProperty("results") val results: List<EpisodeItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("episodeId") val episodeId: String? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("image") val image: String? = null
    )

    private fun extractEpisodes(response: EpisodesResponse?): List<EpisodeItem> {
        if (response == null) return emptyList()
        if (!response.episodes.isNullOrEmpty()) return response.episodes
        if (!response.results.isNullOrEmpty()) return response.results
        if (response.data != null) return extractEpisodes(response.data)
        return emptyList()
    }

    // ---------------------------------------------------------------
    // SOURCES response data classes
    // ---------------------------------------------------------------
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourcesResponse(
        @JsonProperty("data") val data: SourcesResponse? = null,
        @JsonProperty("sources") val sources: List<SourceItem>? = null,
        @JsonProperty("subtitles") val subtitles: List<SubtitleItem>? = null,
        @JsonProperty("tracks") val tracks: List<SubtitleItem>? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourceItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("isM3U8") val isM3U8: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SubtitleItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )

    private fun unwrapSources(response: SourcesResponse?): SourcesResponse? {
        if (response == null) return null
        if (!response.sources.isNullOrEmpty()) return response
        if (response.data != null) return unwrapSources(response.data)
        return response
    }

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        """{"path":"search","method":"POST","query":{"page":1,"perPage":24,"sort":["UPDATED_AT_DESC"],"type":"ANIME"},"body":null,"version":"0.2.0"}""" to "Newest",
        """{"path":"search","method":"POST","query":{"page":1,"perPage":24,"sort":["TRENDING_DESC"],"type":"ANIME"},"body":null,"version":"0.2.0"}""" to "Popular",
        """{"path":"search","method":"POST","query":{"page":1,"perPage":24,"sort":["SCORE_DESC"],"type":"ANIME"},"body":null,"version":"0.2.0"}""" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val payload = request.data.replace("\"page\":1", "\"page\":$page")
        val decrypted = pipeRequest(payload)
            ?: return newHomePageResponse(request.name, emptyList())
        val response = tryParseJson<MiruroSearchResponse>(decrypted)
        val items = extractMediaList(response)

        val home = items.mapNotNull { media ->
            val id = media.id?.toString() ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji
                ?: media.title?.userPreferred ?: return@mapNotNull null
            val poster = media.image ?: media.cover
            newAnimeSearchResponse(title, "$mainUrl/watch/$id", TvType.Anime) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(request.name, home)
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val payload = """{"path":"search","method":"POST","query":{"query":"$query","page":1,"perPage":24,"type":"ANIME"},"body":null,"version":"0.2.0"}"""
        val decrypted = pipeRequest(payload) ?: return emptyList()
        val response = tryParseJson<MiruroSearchResponse>(decrypted)
        val items = extractMediaList(response)

        return items.mapNotNull { media ->
            val id = media.id?.toString() ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji
                ?: media.title?.userPreferred ?: return@mapNotNull null
            val poster = media.image ?: media.cover
            newAnimeSearchResponse(title, "$mainUrl/watch/$id", TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    // ---------------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val anilistId = url.substringAfter("/watch/").substringBefore("/")

        // Metadata: plain HTML SSR data, no decryption needed
        val rawHtml = try { app.get(url).text } catch (e: Exception) { "" }
        val ssr = extractSsrData(rawHtml)

        val title = ssr?.title?.english ?: ssr?.title?.romaji
            ?: ssr?.title?.userPreferred ?: "Unknown"
        val poster = ssr?.coverImage?.extraLarge ?: ssr?.coverImage?.large
        val plot = ssr?.description
            ?.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            ?.replace(Regex("<[^>]*>"), "")

        // Episode list via pipe API — now fully decryptable
        val episodesPayload = """{"path":"episodes","method":"GET","query":{"anilistId":"$anilistId"},"body":null,"version":"0.2.0"}"""
        val episodesDecrypted = try { pipeRequest(episodesPayload) } catch (e: Exception) { null }
        val episodesResponse = episodesDecrypted?.let { tryParseJson<EpisodesResponse>(it) }
        val episodeItems = extractEpisodes(episodesResponse)

        val episodes = if (episodeItems.isNotEmpty()) {
            episodeItems.mapNotNull { ep ->
                val epId = ep.episodeId ?: ep.id ?: return@mapNotNull null
                newEpisode("$epId||$anilistId") {
                    this.name = ep.title?.ifBlank { null } ?: "Episode ${ep.number ?: ""}"
                    this.episode = ep.number
                    this.posterUrl = ep.image
                }
            }
        } else {
            // Fallback: DOM scraping if pipe API returns nothing
            val renderedDoc = try {
                app.get(
                    url,
                    interceptor = WebViewResolver(Regex(Regex.escape(url)))
                ).document
            } catch (e: Exception) { null }

            renderedDoc?.select("button[data-episode-id]")?.mapNotNull { btn ->
                val epId = btn.attr("data-episode-id").ifBlank { return@mapNotNull null }
                val rawTitle = btn.attr("title")
                val epNum = Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
                newEpisode("$epId||$anilistId") {
                    this.name = rawTitle.substringBefore(" · ").trim()
                        .ifBlank { "Episode ${epNum ?: ""}" }
                    this.episode = epNum
                }
            } ?: (1..(ssr?.episodeCount ?: 0)).map { epNum ->
                newEpisode("$epNum||$anilistId") {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS
    // Confirmed query shape from your captured request:
    //   { episodeId, provider, category } — no anilistId
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val parts = data.split("||")
        val epId = parts[0]

        val providers = listOf("kiwi", "ally", "bonk", "moo", "pewe")

        providers.forEach { provider ->
            listOf("sub", "dub").forEach { category ->
                val payload = """{"path":"sources","method":"GET","query":{"episodeId":"$epId","provider":"$provider","category":"$category"},"body":null,"version":"0.2.0"}"""

                try {
                    val decrypted = pipeRequest(payload) ?: return@forEach
                    val rawResponse = unwrapSources(tryParseJson<SourcesResponse>(decrypted))

                    // Subtitles
                    val allTracks = (rawResponse?.subtitles ?: emptyList()) +
                            (rawResponse?.tracks ?: emptyList())
                    allTracks.forEach { track ->
                        val trackUrl = track.url ?: return@forEach
                        val label = track.label ?: track.lang ?: "Unknown"
                        subtitleCallback(SubtitleFile(label, trackUrl))
                    }

                    // Video sources
                    val sources = rawResponse?.sources ?: emptyList()
                    if (sources.isNotEmpty()) {
                        sources.forEach { source ->
                            val sourceUrl = source.url
                                ?.replace("\\/", "/") ?: return@forEach
                            if (sourceUrl.startsWith("http")) {
                                loadExtractor(
                                    sourceUrl,
                                    "$mainUrl/",
                                    subtitleCallback,
                                    callback
                                )
                                found = true
                            }
                        }
                    } else {
                        // Regex fallback if JSON structure differs
                        Regex(""""url"\s*:\s*"([^"]+)"""")
                            .findAll(decrypted)
                            .map { it.groupValues[1].replace("\\/", "/") }
                            .filter { it.startsWith("http") }
                            .forEach { sourceUrl ->
                                loadExtractor(
                                    sourceUrl,
                                    "$mainUrl/",
                                    subtitleCallback,
                                    callback
                                )
                                found = true
                            }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return found
    }
}
