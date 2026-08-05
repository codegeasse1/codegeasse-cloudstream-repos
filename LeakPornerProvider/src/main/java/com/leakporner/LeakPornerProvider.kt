package com.leakporner

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class LeakPornerProvider : MainAPI() {
    override var mainUrl = "https://leakporner.org"
    override var name = "LeakPorner"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.NSFW)

    // ---------- Main Page ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document
        val items = document.select("article.loop-video").mapNotNull { el -> parseVideoItem(el) }
        val hasNext = document.select(".pagination a.next").isNotEmpty()
        
        return newHomePageResponse(
            list = listOf(HomePageList(name, items)),
            hasNext = hasNext
        )
    }

    // ---------- Search ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("article.loop-video").mapNotNull { el ->
            parseVideoItem(el)
        }
    }

    // ---------- Load Details ----------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst(".entry-title, .video-title")?.text()
            ?: document.select("meta[property=og:title]").attr("content")
            ?: "No title"
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".post-thumbnail img, .video-player img")?.let {
                it.attr("data-src")
                    .ifBlank { it.attr("data-lazy-src") }
                    .ifBlank { it.attr("data-original") }
                    .ifBlank { it.attr("src") }
            }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrl(poster)
        }
    }

    // ---------- Load Video Sources ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val document = app.get(data).document

        // 1. Try direct video tags (skip blob: URLs as they cannot be played directly)
        document.select("video source, video").forEach { src ->
            val url = src.attr("src")
            if (url.isNotBlank() && !url.startsWith("blob:")) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Direct",
                        url = fixUrl(url),
                        type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        // 2. Try iframe sources (HGCloud, AbyssPlayer, EmbedSeek, etc.)
        document.select("iframe[src]").forEach { iframe ->
            val srcUrl = iframe.attr("src")
            if (srcUrl.isNotBlank()) {
                val fixedUrl = fixUrl(srcUrl)
                runCatching {
                    // Let CloudStream's built-in extractors handle known domains
                    if (loadExtractor(fixedUrl, data, subtitleCallback, callback)) {
                        found = true
                    } else {
                        // Manual fallback: Fetch iframe HTML and dig for hidden m3u8/mp4 URLs
                        val iframeHtml = app.get(fixedUrl, headers = mapOf("Referer" to data)).text
                        val streamMatch = Regex("""(https?://[^"']+\.(?:m3u8|mp4)[^"']*)""").find(iframeHtml)
                        
                        streamMatch?.groupValues?.get(1)?.let { streamUrl ->
                            val cleanUrl = streamUrl.replace("\\/", "/")
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "Server",
                                    url = cleanUrl,
                                    type = if (cleanUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = fixedUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        }
                    }
                }
            }
        }

        // 3. Fallback: Search the main page HTML for raw .m3u8 or .mp4 links
        if (!found) {
            val html = document.html()
            val m3u8Match = Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(html)
            m3u8Match?.groupValues?.get(1)?.let { streamUrl ->
                val cleanUrl = streamUrl.replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "HLS",
                        url = cleanUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        return found
    }

    // ---------- Helpers ----------
    private fun parseVideoItem(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href]") ?: return null
        val href = linkEl.attr("href")
        
        // Handle Lazy Loading Images
        val posterEl = element.selectFirst("img")
        val poster = posterEl?.attr("data-src")
            ?.ifBlank { posterEl.attr("data-lazy-src") }
            ?.ifBlank { posterEl.attr("data-original") }
            ?.ifBlank { posterEl.attr("src") } ?: ""
        
        val title = element.selectFirst(".entry-header span, .post-title, .title")?.text() ?: "No title"

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrl(poster)
        }
    }

    private fun fixUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl.trimEnd('/') + url
            else -> url
        }
    }
}
