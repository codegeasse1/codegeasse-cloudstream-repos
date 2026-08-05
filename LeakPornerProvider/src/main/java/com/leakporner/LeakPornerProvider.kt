package com.leakporner

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64

class LeakPornerProvider : MainAPI() {
    override var mainUrl = "https://leakporner.org"
    override var name = "LeakPorner"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.NSFW)

    companion object {
        // Image CDN requires a Referer header to load properly
        private const val IMG_REFERER = "https://leakporner.org/"
    }

    // ---------- Main Page ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document
        val items = document.select("article.loop-video, article.post").mapNotNull { el -> parseVideoItem(el) }
        
        val hasNext = document.select(".pagination a").any { 
            it.text().trim().equals("Next", ignoreCase = true) 
        }
        
        return newHomePageResponse(
            list = listOf(HomePageList(name, items)),
            hasNext = hasNext
        )
    }

    // ---------- Search ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("article.loop-video, article.post").mapNotNull { el ->
            parseVideoItem(el)
        }
    }

    // ---------- Load Details ----------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst(".entry-title, .video-title, h1")?.text()
            ?: document.select("meta[property=og:title]").attr("content")
            ?: "No title"
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
            ?: document.selectFirst(".post-thumbnail img, .video-player img")?.let {
                it.attr("data-src").ifBlank { null }
                    ?: it.attr("data-lazy-src").ifBlank { null }
                    ?: it.attr("data-original").ifBlank { null }
                    ?: it.attr("srcset").substringBefore(" ").ifBlank { null }
                    ?: it.attr("src")
            }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrl(poster)
            this.posterHeaders = mapOf("Referer" to IMG_REFERER) // Fixes Details page image
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
        val iframeUrls = mutableSetOf<String>()

        // 1. Scrape custom spans with data-embed
        document.select("span.change-video[data-embed], div.change-video[data-embed], a.change-video[data-embed]").forEach {
            val src = it.attr("data-embed")
            if (src.isNotBlank() && !src.startsWith("blob:")) iframeUrls.add(fixUrl(src))
        }

        // 2. Scrape standard iframes
        document.select("iframe").forEach {
            val src = it.attr("src").ifBlank { it.attr("data-src") }
            if (src.isNotBlank() && !src.startsWith("blob:")) iframeUrls.add(fixUrl(src))
        }

        // 3. Scrape Base64 encoded iframes
        val docText = document.html()
        val base64Regex = Regex("""(?:data-embed|value)=["']([A-Za-z0-9+/=]+)["']""")
        base64Regex.findAll(docText).forEach { match ->
            runCatching {
                val decoded = String(Base64.getDecoder().decode(match.groupValues[1]))
                val srcMatch = Regex("""src=["']([^"']+)["']""").find(decoded)
                srcMatch?.groupValues?.get(1)?.let { 
                    if (!it.startsWith("blob:")) iframeUrls.add(fixUrl(it)) 
                }
            }
        }

        // 4. Process all gathered iframes 
        // MUST use standard for-loop here to properly await suspend functions!
        for (iframeUrl in iframeUrls) {
            runCatching {
                // Pass to built-in CloudStream extractors first
                if (loadExtractor(iframeUrl, data, subtitleCallback, callback)) {
                    found = true
                } else {
                    // Deep scrape: Fetch the 3rd-party iframe HTML
                    val iframeHtml = app.get(iframeUrl, headers = mapOf("Referer" to data)).text
                    
                    // Dig for hidden file objects
                    val streamRegex = Regex("""(?:file|src|url|source)["']?\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                    val rawUrlRegex = Regex("""(https?://[^"'\s>]+\.(?:m3u8|mp4)[^"'\s>]*)""")

                    var iframeFound = false
                    
                    for (match in streamRegex.findAll(iframeHtml)) {
                        val streamUrl = match.groupValues[1].replace("\\/", "/")
                        addStreamLink(streamUrl, iframeUrl, callback)
                        iframeFound = true
                    }

                    if (!iframeFound) {
                        for (match in rawUrlRegex.findAll(iframeHtml)) {
                            val streamUrl = match.groupValues[1].replace("\\/", "/")
                            addStreamLink(streamUrl, iframeUrl, callback)
                        }
                    }
                }
            }
        }

        // 5. Main Page Fallback Regex
        val directStreamRegex = Regex("""(?:file|src|url)["']?\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
        for (match in directStreamRegex.findAll(docText)) {
            val streamUrl = match.groupValues[1].replace("\\/", "/")
            addStreamLink(streamUrl, data, callback)
            found = true
        }

        return found
    }

    // ---------- Helpers ----------
    
    private suspend fun addStreamLink(rawUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val safeUrl = if (rawUrl.contains(".txt") && !rawUrl.contains(".m3u8")) "$rawUrl#.m3u8" else rawUrl
        val isM3u8 = safeUrl.contains(".m3u8")

        callback.invoke(
            newExtractorLink(
                source = name,
                name = if (isM3u8) "HLS Server" else "MP4 Server",
                url = safeUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun parseVideoItem(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href]") ?: return null
        val href = linkEl.attr("href")
        
        val posterEl = element.selectFirst("img")
        val poster = posterEl?.let {
            it.attr("data-src").ifBlank { null }
                ?: it.attr("data-lazy-src").ifBlank { null }
                ?: it.attr("data-original").ifBlank { null }
                ?: it.attr("srcset").substringBefore(" ").ifBlank { null }
                ?: it.attr("src")
        } ?: ""
        
        val title = element.selectFirst(".entry-header span, .post-title, .title, .entry-title")?.text() 
            ?: linkEl.attr("title")
            ?: "No title"

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrl(poster)
            this.posterHeaders = mapOf("Referer" to IMG_REFERER) // Fixes Main Page / Search image
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
