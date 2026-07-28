package com.mrds

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class MrdsProvider : MainAPI() {
    override var mainUrl = "https://mrds.com"
    override var name = "MRDS"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Others)

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/category/trending/" to "Trending"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document

        val home = document.select("a:has(.post-card)").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    // ---------------------------------------------------------------
    // SEARCH & HOMEPAGE ITEM PARSING (Thumbnail Fix)
    // ---------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.text().substringBefore(" • ").trim()

        // 1. Search for a standard <img> tag, but STRICTLY EXCLUDE Base64 data images
        var posterUrl = this.selectFirst("img:not([src^=data:image])")?.attr("src")

        // 2. Fallback: Search the background style, ignoring Base64 data strings
        if (posterUrl.isNullOrBlank()) {
            val style = this.selectFirst(".blog-background")?.attr("style") ?: ""
            val rawMatch = Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)
            
            if (rawMatch != null && !rawMatch.startsWith("data:")) {
                posterUrl = rawMatch.replace("&quot;", "")
            }
        }
        
        // 3. Fallback: Check for lazy-loaded data attributes
        if (posterUrl.isNullOrBlank()) {
            posterUrl = this.selectFirst("[data-src]:not([data-src^=data:image])")?.attr("data-src")
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("a:has(.post-card)").mapNotNull { it.toSearchResult() }
    }

    // ---------------------------------------------------------------
    // LOAD (Detail Page Thumbnail Fix)
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1, .post-title, title")?.text()?.substringBefore("-")?.trim() ?: "Video"

        // 1. Prioritize OpenGraph image (usually the highest quality standard URL)
        var poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        
        // 2. Fallback to background styles if meta tag is missing or is Base64
        if (poster.isNullOrBlank() || poster.startsWith("data:")) {
            val style = document.selectFirst(".blog-background")?.attr("style") ?: ""
            val rawMatch = Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)
            if (rawMatch != null && !rawMatch.startsWith("data:")) {
                poster = rawMatch.replace("&quot;", "")
            }
        }
        
        // 3. Final fallback to any non-Base64 standard image on the page
        if (poster.isNullOrBlank()) {
            poster = document.selectFirst("img:not([src^=data:image])")?.attr("src")
        }

        val synopsis = document.selectFirst(".post-content p, article p")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = synopsis
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (Uses newExtractorLink builder)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val html = app.get(data).text

        // CDN host varies per video/session, AND the url sits inside JSON with escaped slashes
        // (e.g. "url":"https:\/\/hls.dscxru.cn\/videos5\/...m3u8"), so tolerate optional backslashes before each slash
        val cdnRegex = Regex("""https?:\\?/\\?/[^\s"'<>]+?\.m3u8[^\s"'<>]*""")

        cdnRegex.findAll(html).forEach { match ->
            var cleanUrl = match.value.replace("\\/", "/")
            cleanUrl = cleanUrl.replace("&amp;", "&")

            if (cleanUrl.isNotBlank()) {
                callback(
                    newExtractorLink(
                        source = "MRDS Server",
                        name = "MRDS Server",
                        url = cleanUrl,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        return found
    }
}
