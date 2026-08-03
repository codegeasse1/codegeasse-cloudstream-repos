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
    // REMOTE TRANSLATION TOGGLE
    // ---------------------------------------------------------------
    private var cachedTranslateFlag: Boolean? = null

    private suspend fun isTranslationEnabled(): Boolean {
        cachedTranslateFlag?.let { return it }
        val flag = try {
            val json = app.get(
                "https://gist.githubusercontent.com/codegeasse1/02333c773cbd933b02e1779e6a1222fe/raw/config.json"
            ).text
            Regex(""""translate"\s*:\s*(true|false)""").find(json)
                ?.groupValues?.get(1)?.toBoolean() ?: true
        } catch (e: Exception) {
            true
        }
        cachedTranslateFlag = flag
        return flag
    }

    // ---------------------------------------------------------------
    // GOOGLE TRANSLATE HELPER
    // ---------------------------------------------------------------
    private suspend fun translateText(text: String?, toEnglish: Boolean = true): String? {
        if (!isTranslationEnabled()) return text
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

        // Handle the discover (categories) page
        if (request.data.contains("discover")) {
            val categories = document.select("a[href*=/cn/category/]").mapNotNull { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val rawName = a.text().trim()
                if (rawName.isBlank()) return@mapNotNull null
                val translatedName = translateText(rawName, true) ?: rawName
                
                // Extract image for the categories properly
                val img = a.selectFirst("img")
                var poster = img?.attr("src")
                if (poster.isNullOrBlank() || poster.contains("data:image")) {
                    poster = img?.attr("data-src") ?: ""
                }

                newMovieSearchResponse(translatedName, href, TvType.NSFW) {
                    this.posterUrl = fixUrlNull(poster)
                }
            }.distinctBy { it.url }
            return newHomePageResponse(request.name, categories, hasNext = false)
        }

        // Original movie-card parsing for "Hot Videos" etc.
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

        var posterUrl = img?.attr("src") ?: ""
        if (posterUrl.isBlank() || posterUrl.contains("data:image")) {
            posterUrl = img?.attr("data-src") ?: ""
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(posterUrl)
        }
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val chineseQuery = translateText(query, false) ?: query
        val encodedQuery = URLEncoder.encode(chineseQuery, "UTF-8")
        val maxPages = 5

        suspend fun fetchPage(page: Int): List<SearchResponse> {
            val url = if (page == 1)
                "$mainUrl/cn/search?q=$encodedQuery"
            else
                "$mainUrl/cn/search?q=$encodedQuery&page=$page"
            val document = app.get(url).document
            return document.select("div.movie-card").mapNotNull { element ->
                element.toSearchResultAsync()
            }
        }

        val results = mutableListOf<SearchResponse>()
        for (page in 1..maxPages) {
            val pageResults = fetchPage(page)
            if (pageResults.isEmpty()) break
            results.addAll(pageResults)
        }
        return results
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
    // LOAD LINKS (API + Fallback Scraper)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        val movieIdMatch = Regex("""/(\d+)(?:/|\.|\?|$)""").find(data)
        val movieId = movieIdMatch?.groupValues?.get(1) ?: data.substringAfterLast("/").substringBefore("?")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to data
        )

        fun emitLink(rawUrl: String) {
            var fixedM3u8Url = rawUrl.replace("\\/", "/")
            if (fixedM3u8Url.startsWith("//")) fixedM3u8Url = "https:$fixedM3u8Url"
            else if (fixedM3u8Url.startsWith("/")) fixedM3u8Url = "$mainUrl$fixedM3u8Url"

            val videoOrigin = try {
                val uri = java.net.URI(fixedM3u8Url)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) { mainUrl }

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name HD",
                    url = fixedM3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf(
                        "User-Agent" to headers["User-Agent"]!!,
                        "Origin" to videoOrigin,
                        "Referer" to "$videoOrigin/"
                    )
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        // 1. Extract directly from the HTML source of the page (Bypasses API blocks)
        try {
            val html = app.get(data, headers = headers).text
            
            // Look for m3u8 directly in the JS config blocks
            val htmlRegex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
            htmlRegex.findAll(html).forEach { match ->
                emitLink(match.groupValues[1])
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fallback to API if HTML scraper fails
        if (!found && movieId.isNotBlank()) {
            try {
                val apiUrl = "$mainUrl/api/getmovie?type=1280&id=$movieId"
                val response = app.get(apiUrl, headers = headers.plus("X-Requested-With" to "XMLHttpRequest")).text
                
                if (response.trim().startsWith("{")) {
                    val json = JSONObject(response)
                    val m3u8UrlRaw = json.optString("m3u8", "").ifBlank { json.optString("url", "") }
                    if (m3u8UrlRaw.isNotBlank()) {
                        emitLink(m3u8UrlRaw)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return found
    }
}
