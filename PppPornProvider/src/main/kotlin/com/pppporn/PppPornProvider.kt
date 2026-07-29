package com.pppporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class PppPornProvider : MainAPI() {
    override var mainUrl = "https://asiangirl.porn"
    override var name = "ppp.porn"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

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
        val homeItems = elements.mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, homeItems)
    }

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
    // CloudStream's search() has NO scroll/pagination hook — that only
    // exists for getMainPage(). So instead of true infinite scroll,
    // we just pull a few pages of results up front and merge them.
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val maxPages = 3

        suspend fun fetchPage(page: Int): List<SearchResponse> {
            val url = if (page == 1)
                "$mainUrl/search/?q=$encodedQuery"
            else
                "$mainUrl/search/?q=$encodedQuery&page=$page"
            val document = app.get(url).document
            return document.select("div.item, div.video-item, div.post, div.model, li.item")
                .mapNotNull { it.toSearchResult() }
        }

        val results = mutableListOf<SearchResponse>()
        for (page in 1..maxPages) {
            val pageResults = fetchPage(page)
            if (pageResults.isEmpty()) break
            results.addAll(pageResults)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val pageHtml = document.html()

        val title = document.selectFirst("h1")?.text()
            ?: document.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: "Video"

        var posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")

        if (posterUrl.isNullOrBlank()) {
            val plyrStyle = document.selectFirst(".plyr__poster")?.attr("style")
            if (plyrStyle != null) {
                posterUrl = Regex("""url\(['"]?([^'"()]+)['"]?\)""").find(plyrStyle)?.groupValues?.get(1)
            }
        }

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val response = app.get(data)
        val pageHtml = response.text

        val cdnRegex = Regex("""https?:\\?/\\?/[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*""")

        cdnRegex.findAll(pageHtml).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/").replace("&amp;", "&")
            if (cleanUrl.isNotBlank() && !cleanUrl.endsWith(".jpg") && !cleanUrl.endsWith(".png") && !cleanUrl.endsWith(".webp")) {
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

        if (!found) {
            val document = response.document
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.startsWith("http")) {
                    try {
                        val iframeHtml = app.get(src, headers = mapOf("Referer" to data)).text
                        cdnRegex.findAll(iframeHtml).forEach { match ->
                            val cleanUrl = match.value.replace("\\/", "/").replace("&amp;", "&")
                            if (cleanUrl.isNotBlank() && !cleanUrl.endsWith(".jpg") && !cleanUrl.endsWith(".png")) {
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
                    } catch (_: Exception) { }
                }
            }
        }

        return found
    }
}