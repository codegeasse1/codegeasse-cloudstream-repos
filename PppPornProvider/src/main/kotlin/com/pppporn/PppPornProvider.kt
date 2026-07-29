package com.pppporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element
import java.net.URLEncoder

class PppPornProvider : MainAPI() {
    override var mainUrl = "https://asiangirl.porn"
    override var name = "ppp.porn"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/latest-updates/" to "Latest Updates",
        "$mainUrl/top-rated/" to "Top Rated",
        "$mainUrl/most-popular/" to "Most Popular"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}${if(request.data.endsWith("/")) "" else "/"}?page=$page"
        val document = app.get(url).document

        val elements = document.select("div.item, div.video-item, div.post, div.model, li.item")
        
        val homeItems = elements.mapNotNull { element ->
            element.toSearchResult()
        }
        
        return newHomePageResponse(request.name, homeItems)
    }

    // ---------------------------------------------------------------
    // ITEM PARSING
    // ---------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a[href*=/videos/], a[href*=/video/]") ?: this.selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        
        val img = this.selectFirst("img")
        val title = img?.attr("alt")?.ifBlank { img.attr("title") }?.ifBlank { this.text() }?.trim() ?: "Video"

        val rawPoster = img?.attr("data-original")?.ifBlank { img.attr("data-src") }?.ifBlank { img.attr("src") }
        val posterUrl = fixUrlNull(rawPoster)

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?q=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url).document
        
        return document.select("div.item, div.video-item, div.post, div.model, li.item").mapNotNull { element ->
            element.toSearchResult()
        }
    }

    // ---------------------------------------------------------------
    // LOAD (Detail Page with Plyr.io Extraction)
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val pageHtml = document.html()

        val title = document.selectFirst("h1")?.text() 
            ?: document.selectFirst("title")?.text()?.substringBefore("-")?.trim() 
            ?: "Video"

        // 1. Try standard OpenGraph meta image
        var posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        
        // 2. Try the Plyr.io background-image style
        if (posterUrl.isNullOrBlank()) {
            val plyrStyle = document.selectFirst(".plyr__poster")?.attr("style")
            if (plyrStyle != null) {
                posterUrl = Regex("""url\(['"]?([^'"()]+)['"]?\)""").find(plyrStyle)?.groupValues?.get(1)
            }
        }

        // 3. Fallback to HTML5 video tag or standard KVS flashvars
        if (posterUrl.isNullOrBlank()) {
            posterUrl = Regex("""poster="([^"]+)"""").find(pageHtml)?.groupValues?.get(1) 
                ?: Regex("""preview_url:\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
        }

        val tags = document.select("a[href*=/tags/], a[href*=/categories/]").map { it.text().trim() }.filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.plot = title
            this.tags = tags
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (KVS Scraper)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val response = app.get(data)
        val pageHtml = response.text

        // 1. Broad Regex to catch any standard or escaped CDN links
        val cdnRegex = Regex("""https?:\\?/\\?/[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*""")
        
        cdnRegex.findAll(pageHtml).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/").replace("&amp;", "&")
            
            if (cleanUrl.isNotBlank()) {
                val isM3u8 = cleanUrl.contains(".m3u8")
                
                callback(
                    newExtractorLink(
                        source = name,
                        name = if (isM3u8) "$name HLS" else "$name MP4",
                        url = cleanUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        // 2. Look for iframe embeds
        if (!found) {
            val document = response.document
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.startsWith("http")) {
                    try {
                        val iframeHtml = app.get(src, headers = mapOf("Referer" to data)).text
                        cdnRegex.findAll(iframeHtml).forEach { match ->
                            val cleanUrl = match.value.replace("\\/", "/").replace("&amp;", "&")
                            if (cleanUrl.isNotBlank()) {
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name Embed",
                                        url = cleanUrl,
                                        type = if (cleanUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = src
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                found = true
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore dead iframes
                    }
                }
            }
        }

        return found
    }
}
