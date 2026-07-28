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
    // Assuming posts act as single videos (Movies)
    override val supportedTypes = setOf(TvType.Movie, TvType.Others)

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/category/trending/" to "Trending" // Add other categories if they exist
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document

        // Locate anchor tags that wrap the .post-card elements
        val home = document.select("a:has(.post-card)").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        
        // Grab the title from the visible text inside the card
        val title = this.text().substringBefore(" • ").trim()
        
        // Extract Base64 background image from the style attribute
        val bgDiv = this.selectFirst(".blog-background")
        val style = bgDiv?.attr("style") ?: ""
        
        // Regex to pull the data URI out of url('...')
        val posterUrl = Regex("""url\(['"]?(data:image[^'"]+)['"]?\)""").find(style)?.groupValues?.get(1) 
            ?: this.selectFirst("img")?.attr("src")

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
    // LOAD (Detail Page)
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1, .post-title, title")?.text()?.substringBefore("-")?.trim() ?: "Video"
        
        // Grab the Base64 image from the <p> tags if present
        val poster = document.selectFirst("img[src^=data:image]")?.attr("src") 
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val synopsis = document.selectFirst(".post-content p, article p")?.text()

        // Treating it as a movie since it's a single video post
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = synopsis
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (Video Extraction)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Locate the div containing the JSON video configuration
        val dataConfig = document.selectFirst(".data-video")?.attr("data-config")
        
        if (!dataConfig.isNullOrBlank()) {
            // Use Regex to pluck the URL directly from the JSON string
            val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
            val match = urlRegex.find(dataConfig)
            
            if (match != null) {
                // The URL contains escaped slashes (e.g. https:\/\/hls...). We clean them here.
                val rawUrl = match.groupValues[1]
                val cleanUrl = rawUrl.replace("\\/", "/")
                
                if (cleanUrl.isNotBlank()) {
                    loadExtractor(cleanUrl, data, subtitleCallback, callback)
                    return true
                }
            }
        }
        
        return false
    }
}
