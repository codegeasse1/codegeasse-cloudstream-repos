package com.taiav

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLEncoder

class TaiAVProvider : MainAPI() {
    override var mainUrl = "https://taiav.com"
    override var name = "TaiAV"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Others)

    // ---------------------------------------------------------------
    // GOOGLE TRANSLATE HELPER
    // ---------------------------------------------------------------
    private suspend fun translateText(text: String?, toEnglish: Boolean = true): String? {
        if (text.isNullOrBlank()) return text
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val targetLang = if (toEnglish) "en" else "zh-CN"
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encodedText"
            
            val response = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0")).text

            val matches = Regex("""\["([^"\\]*(?:\\.[^"\\]*)*)","[^"]*"""").findAll(response)
            val translated = matches.map { 
                it.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n") 
            }.joinToString("")

            if (translated.isNotBlank()) translated else text
        } catch (e: Exception) {
            text
        }
    }

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/cn/hots" to "Hot Videos",
        "$mainUrl/cn/discover" to "Discover Categories"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        val document = app.get(url).document

        val homeItems = document.select("div.movie-card").mapNotNull { element ->
            element.toSearchResultAsync()
        }
        return newHomePageResponse(request.name, homeItems)
    }

    // ---------------------------------------------------------------
    // ITEM PARSING
    // ---------------------------------------------------------------
    private suspend fun Element.toSearchResultAsync(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        
        val img = this.selectFirst("img")
        val rawTitle = img?.attr("alt")?.ifBlank { this.selectFirst(".movie-title")?.text() }?.trim() ?: ""
        val title = translateText(rawTitle, true) ?: rawTitle

        val posterUrl = fixUrlNull(img?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val chineseQuery = translateText(query, false) ?: query
        val url = "$mainUrl/cn/search?q=${URLEncoder.encode(chineseQuery, "UTF-8")}"
        val document = app.get(url).document
        
        return document.select("div.movie-card").mapNotNull { element ->
            element.toSearchResultAsync()
        }
    }

    // ---------------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val pageHtml = document.html()

        val rawTitle = document.selectFirst("h1.uk-h4")?.text() ?: "Video"
        val title = translateText(rawTitle, true) ?: rawTitle

        var posterUrl = Regex("""poster:\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
        if (posterUrl.isNullOrBlank()) {
            posterUrl = Regex(""""thumbnailUrl"\s*:\s*"([^"]+)"""").find(pageHtml)?.groupValues?.get(1)
        }
        posterUrl = fixUrlNull(posterUrl)

        val tagElements = document.select("a[href*=/cn/tag/]")
        val rawTags = tagElements.map { it.text().trim() }.filter { it.isNotBlank() }
        
        val tagsList = if (rawTags.isNotEmpty()) {
            val combinedTags = rawTags.joinToString(" ~ ")
            val translatedCombinedTags = translateText(combinedTags, true) ?: combinedTags
            translatedCombinedTags.split(" ~ ").map { it.trim() }
        } else {
            emptyList()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = title
            this.tags = tagsList
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (TaiAV Key Injector)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // Extract the exact movieId from the URL
        val movieId = data.substringAfterLast("/").substringBefore("?")
        
        if (movieId.isNotBlank()) {
            try {
                val apiUrl = "$mainUrl/api/getmovie?type=1280&id=$movieId"
                
                val response = app.get(
                    apiUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Referer" to data
                    )
                )

                // 1. Grab the JSON Response and extract the URL safely
                val json = JSONObject(response.text)
                val m3u8UrlRaw = json.optString("m3u8", "")
                
                if (m3u8UrlRaw.isNotBlank()) {
                    
                    // 2. Format the URL to ensure it has a valid protocol
                    val fixedM3u8Url = when {
                        m3u8UrlRaw.startsWith("//") -> "https:$m3u8UrlRaw"
                        m3u8UrlRaw.startsWith("/") -> "$mainUrl$m3u8UrlRaw"
                        else -> m3u8UrlRaw
                    }

                    // 3. Dynamically grab the exact host for the Origin header (e.g., m.taiav.com)
                    val videoOrigin = try {
                        val uri = java.net.URI(fixedM3u8Url)
                        "${uri.scheme}://${uri.host}"
                    } catch (e: Exception) {
                        mainUrl
                    }

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name HD",
                            url = fixedM3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            // Inject strict headers directly into CloudStream's ExoPlayer
                            // Cookies are automatically handled by CloudStream's global cookie jar
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                "Origin" to videoOrigin,
                                "Referer" to "$videoOrigin/"
                            )
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return found
    }
}
