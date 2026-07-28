package com.mrds

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.text().substringBefore(" • ").trim()
        
        val style = this.selectFirst(".blog-background")?.attr("style") ?: ""
        val posterMatch = Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)
        val posterUrl = posterMatch?.replace("&quot;", "") ?: this.selectFirst("img")?.attr("src")

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
    // LOAD
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1, .post-title, title")?.text()?.substringBefore("-")?.trim() ?: "Video"
        
        val style = document.selectFirst(".blog-background")?.attr("style") ?: ""
        val posterMatch = Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)
        
        val poster = posterMatch?.replace("&quot;", "")
            ?: document.selectFirst("img[src^=data:image]")?.attr("src") 
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val synopsis = document.selectFirst(".post-content p, article p")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = synopsis
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (Targeted CDN Extraction)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val html = app.get(data).text
        
        // Target the specific CDN domain structure you found with IDM
        // This Regex safely captures URLs containing dscxru.cn and cleans up any JSON escape backslashes
        val cdnRegex = Regex("""https?://[^\s"'<>]+?dscxru\.cn[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
        
        cdnRegex.findAll(html).forEach { match ->
            var cleanUrl = match.value.replace("\\/", "/")
            // Clean out HTML escape artifacts if present
            cleanUrl = cleanUrl.replace("&amp;", "&")
            
            if (cleanUrl.isNotBlank()) {
                // Since this is a direct HLS (.m3u8) stream link with an auth key, 
                // we register it directly as an ExtractorLink
                callback(
                    ExtractorLink(
                        source = "MRDS Stream",
                        name = "MRDS Server",
                        url = cleanUrl,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
                found = true
            }
        }
        
        return found
    }
}
