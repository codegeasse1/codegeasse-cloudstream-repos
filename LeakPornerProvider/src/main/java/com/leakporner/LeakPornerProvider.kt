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

    // Browser headers – help with referer and lazy loading
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    // ---------- Main Page ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url, headers = headers).document
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
        val document = app.get(searchUrl, headers = headers).document
        return document.select("article.loop-video").mapNotNull { el -> parseVideoItem(el) }
    }

    // ---------- Load Details ----------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst(".entry-title")?.text()
            ?: document.select("meta[property=og:title]").attr("content")
            ?: "No title"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".post-thumbnail img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
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
        val document = app.get(data, headers = headers).document

        // 1. Direct video tags (rare)
        document.select("video source[src]").forEach { src ->
            val videoUrl = src.attr("src")
            if (videoUrl.isNotBlank()) {
                addLink(videoUrl, "Direct", data, callback)
                found = true
            }
        }

        // 2. Process each iframe
        document.select("iframe[src]").forEach { iframe ->
            val srcUrl = iframe.attr("src")
            if (srcUrl.isBlank() || srcUrl.startsWith("blob:")) return@forEach
            val absoluteSrc = fixUrl(srcUrl)

            // Fetch the iframe content
            val iframeHtml = runCatching {
                app.get(absoluteSrc, headers = headers + ("Referer" to data)).text
            }.getOrElse { return@forEach }

            // Search for m3u8 / mp4 links
            val videoUrls = Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*""")
                .findAll(iframeHtml).map { it.value }.toList()

            // Also look for file: or src: in scripts (common in many players)
            val jsUrls = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+)["']""")
                .findAll(iframeHtml).map { it.groupValues[1] }.toList()

            val allUrls = (videoUrls + jsUrls).filter { it.contains(".m3u8") || it.contains(".mp4") }

            for (streamUrl in allUrls) {
                val cleanUrl = streamUrl.replace("&amp;", "&")
                addLink(cleanUrl, "Iframe Server", absoluteSrc, callback)
                found = true
            }

            // If no direct links, try to treat the iframe src itself as a player URL (some embeds directly expose stream)
            if (!found && (srcUrl.contains("/embed/") || srcUrl.contains("/e/"))) {
                // Some embeds work by calling the embed page, we already fetched it above, but if we still have nothing,
                // we can try to pass the embed URL as a direct link (some extractor might handle it, but we can't rely on that)
                // For now we do nothing extra
            }
        }

        return found
    }

    // ---------- Helpers ----------
    private fun parseVideoItem(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href]") ?: return null
        val href = linkEl.attr("href")

        // Try to get poster from img (data-src then src)
        val posterEl = element.selectFirst("img")
        val poster = posterEl?.attr("data-src")?.ifBlank { posterEl.attr("src") } ?: ""

        val title = element.selectFirst(".entry-header span")?.text() ?: "No title"

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

    private fun addLink(url: String, serverName: String, referer: String, callback: (ExtractorLink) -> Unit) {
        callback(
            newExtractorLink(
                source = name,
                name = serverName,
                url = url,
                type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }
}