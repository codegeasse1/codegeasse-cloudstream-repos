package com.miruro

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.WebViewResolver
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

class MiruroProvider : MainAPI() {
    override var mainUrl = "https://www.miruro.to"
    override var name = "Miruro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // The interceptor ensures Cloudflare Turnstile doesn't block our raw API requests
    private val webView = WebViewResolver(Regex(".*miruro\\.to.*"))

    // ---------------------------------------------------------------
    // JSON DATA CLASSES (Fixed ID type to Int to prevent crashes)
    // ---------------------------------------------------------------
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MiruroSearchResponse(
        @JsonProperty("results") val results: List<MiruroMedia>? = null,
        @JsonProperty("media") val media: List<MiruroMedia>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MiruroMedia(
        @JsonProperty("id") val id: Int? = null, // Changed to Int (Anilist uses integers)
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

    // ---------------------------------------------------------------
    // MAIN PAGE (Added exact "object":null requirement)
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        """{"path":"search","method":"POST","query":{"page":1,"perPage":24,"sort":["UPDATED_AT_DESC"],"type":"ANIME"},"body":null,"object":null,"version":"0.2.0"}""" to "Newest",
        """{"path":"search","method":"POST","query":{"page":1,"perPage":24,"sort":["TRENDING_DESC"],"type":"ANIME"},"body":null,"object":null,"version":"0.2.0"}""" to "Popular",
        """{"path":"search","method":"POST","query":{"page":1,"perPage":24,"sort":["SCORE_DESC"],"type":"ANIME"},"body":null,"object":null,"version":"0.2.0"}""" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val payload = request.data.replace("\"page\":1", "\"page\":$page")
        val encodedPayload = Base64.encodeToString(payload.toByteArray(), Base64.NO_WRAP)
        val apiUrl = "$mainUrl/api/secure/pipe?e=$encodedPayload"

        // Use webView interceptor to bypass Cloudflare API blocking
        val response = app.get(apiUrl, interceptor = webView, headers = mapOf("Referer" to "$mainUrl/")).parsedSafe<MiruroSearchResponse>()
        val items = response?.results ?: response?.media ?: emptyList()

        val home = items.mapNotNull { media ->
            val id = media.id?.toString() ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: media.title?.userPreferred ?: return@mapNotNull null
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
        val payload = """{"path":"search","method":"POST","query":{"query":"$query","page":1,"perPage":24,"type":"ANIME"},"body":null,"object":null,"version":"0.2.0"}"""
        val encodedPayload = Base64.encodeToString(payload.toByteArray(), Base64.NO_WRAP)
        val apiUrl = "$mainUrl/api/secure/pipe?e=$encodedPayload"

        val response = app.get(apiUrl, interceptor = webView, headers = mapOf("Referer" to "$mainUrl/")).parsedSafe<MiruroSearchResponse>()
        val items = response?.results ?: response?.media ?: emptyList()

        return items.mapNotNull { media ->
            val id = media.id?.toString() ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: media.title?.userPreferred ?: return@mapNotNull null
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
        val document = app.get(url, interceptor = webView).document

        val title = document.selectFirst("h1, .title")?.text()?.trim() ?: ""
        val poster = fixUrlNull(document.selectFirst("img[alt*=poster], .poster img")?.attr("src"))
        val synopsis = document.selectFirst("div[id*=description], .description")?.text()

        val anilistId = url.substringAfter("/watch/").substringBefore("/")

        val episodes = document.select("div[data-episode-list=true] button[data-episode-id]").mapNotNull { btn ->
            val epTitle = btn.attr("title").ifBlank { btn.text() }.trim()
            val epNum = Regex("(?i)(?:EP|Episode)\\s*(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            
            val epId = btn.attr("data-episode-id")
            if (epId.isBlank()) return@mapNotNull null

            val linkData = "$epId||$anilistId"

            newEpisode(linkData) {
                this.name = epTitle
                this.episode = epNum
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = synopsis
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS
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
        val anilistId = parts.getOrNull(1)

        val providers = listOf("kiwi", "ally", "bonk") 
        
        providers.forEach { provider ->
            listOf("sub", "dub").forEach { category ->
                val jsonPayload = if (anilistId != null && anilistId.toIntOrNull() != null) {
                    """{"path":"sources","method":"GET","query":{"episodeId":"$epId","provider":"$provider","category":"$category","anilistId":$anilistId},"body":null,"object":null,"version":"0.2.0"}"""
                } else {
                    """{"path":"sources","method":"GET","query":{"episodeId":"$epId","provider":"$provider","category":"$category"},"body":null,"object":null,"version":"0.2.0"}"""
                }
                
                val encodedPayload = Base64.encodeToString(jsonPayload.toByteArray(), Base64.NO_WRAP)
                val apiUrl = "$mainUrl/api/secure/pipe?e=$encodedPayload"

                try {
                    val response = app.get(apiUrl, interceptor = webView).text
                    val urls = Regex(""""url"\s*:\s*"([^"]+)"""").findAll(response).map { it.groupValues[1] }.toList()
                    
                    urls.forEach { parsedUrl ->
                        val fixedUrl = parsedUrl.replace("\\/", "/")
                        if (fixedUrl.startsWith("http")) {
                            loadExtractor(fixedUrl, data, subtitleCallback, callback)
                            found = true
                        }
                    }
                } catch (e: Exception) {}
            }
        }
        
        return found
    }
}
