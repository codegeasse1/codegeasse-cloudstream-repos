package com.mrds

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element

class MrdsProvider : MainAPI() {
    override var mainUrl = "https://mrds.com"
    override var name = "MRDS"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Others)

    // The interceptor to execute the site's image decryption JavaScript
    private val webView = WebViewResolver(Regex(".*mrds\\.com.*"))

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/category/trending/" to "Trending"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        
        // Use interceptor = webView to let the JavaScript decrypt the images!
        val document = app.get(url, interceptor = webView).document

        val home = document.select("a:has(.post-card)").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    // ---------------------------------------------------------------
    // ITEM PARSING (Scraping the Decrypted Base64)
    // ---------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.text().substringBefore(" • ").trim()

        // Now that JS has run, the decrypted Base64 is injected into the style attribute
        val style = this.selectFirst(".blog-background")?.attr("style") ?: ""
        var posterUrl = Regex("""url\(['"]?(data:image[^'"]+)['"]?\)""").find(style)?.groupValues?.get(1)

        // Fallback to normal img tags if they exist
        if (posterUrl.isNullOrBlank()) {
            posterUrl = this.selectFirst("img")?.attr("src")
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        // Use interceptor = webView
        val document = app.get("$mainUrl/?s=$query", interceptor = webView).document
        return document.select("a:has(.post-card)").mapNotNull { it.toSearchResult() }
    }

    // ---------------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        // Use interceptor = webView
        val document = app.get(url, interceptor = webView).document

        val title = document.selectFirst("h1, .post-title, title")?.text()?.substringBefore("-")?.trim() ?: "Video"

        var poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        
        if (poster.isNullOrBlank() || poster.startsWith("data:")) {
            val style = document.selectFirst(".blog-background")?.attr("style") ?: ""
            poster = Regex("""url\(['"]?(data:image[^'"]+)['"]?\)""").find(style)?.groupValues?.get(1)
        }
        
        if (poster.isNullOrBlank()) {
            poster = document.selectFirst("img")?.attr("src")
        }

        val synopsis = document.selectFirst(".post-content p, article p")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = synopsis
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (Keep Raw HTML extraction for fast video loading)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val html = app.get(data).text

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
