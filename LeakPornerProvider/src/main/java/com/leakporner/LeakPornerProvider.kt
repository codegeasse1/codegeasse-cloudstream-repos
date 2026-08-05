package com.leakporner

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class LeakPornerProvider : MainAPI() {
    override var mainUrl = "https://leakporner.org"
    override var name = "LeakPorner"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.NSFW) // or TvType.Movie / TvType.Others
    override val providerType = ProviderType.NSFW

    // ============== Main Page (Latest Videos) ==============
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document
        val items = document.select("article.loop-video").mapNotNull { element ->
            parseVideoItem(element) ?: return@mapNotNull null
        }
        val hasNext = document.select(".pagination a.next").isNotEmpty()
        return HomePageResponse(items, hasNext)
    }

    // ============== Search ==============
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("article.loop-video").mapNotNull { element ->
            parseVideoItem(element)?.let { video ->
                // Map from HomePage item to SearchResponse
                SearchResponse(
                    name = video.name,
                    url = video.url,
                    apiName = this.name,
                    type = TvType.NSFW,
                    posterUrl = video.posterUrl,
                    quality = null,
                    releaseDate = null
                )
            }
        }
    }

    // ============== Load Video Details & Sources ==============
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".entry-title")?.text()
            ?: document.select("meta[property=og:title]")?.attr("content")
            ?: "No title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".post-thumbnail img")?.attr("src")

        // Extract the video sources from the multi‑iframe player
        val sources = extractVideoSources(document)

        return MovieLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = TvType.NSFW,
            dataUrl = url,
            posterUrl = poster,
            sources = sources
        )
    }

    // ============== Helper: Parse a video card from the list ==============
    private fun parseVideoItem(element: Element): HomePageEntry? {
        val linkEl = element.selectFirst("a[href]") ?: return null
        val href = linkEl.attr("href")
        val posterEl = element.selectFirst("img")
        val poster = posterEl?.attr("data-src") ?: posterEl?.attr("src") ?: ""
        val title = element.selectFirst(".entry-header span")?.text() ?: "No title"
        val durationText = element.selectFirst(".duration")?.text()?.trim() ?: ""
        return HomePageEntry(
            name = title,
            url = href,
            posterUrl = fixUrl(poster),
            quality = null,
            duration = durationText
        )
    }

    // ============== Extract video sources from the muliframe player ==============
    private fun extractVideoSources(document: org.jsoup.nodes.Document): List<VideoSource> {
        val sources = mutableListOf<VideoSource>()

        // 1. Try finding iframe elements inside .muliframe-container or directly
        val iframes = document.select("iframe[src]")
        if (iframes.isEmpty()) {
            // Fallback: search for direct video tags
            val videoTags = document.select("video source")
            videoTags.forEach { src ->
                val url = src.attr("src")
                if (url.isNotBlank()) sources.add(VideoSource(url, "Direct", 1080))
            }
        }

        // 2. For each iframe, attempt to fetch and extract a video URL
        iframes.forEach { iframe ->
            val srcUrl = iframe.attr("src")
            if (srcUrl.isBlank()) return@forEach
            try {
                val iframeDoc = app.get(fixUrl(srcUrl)).document
                // Look for video tags or common video URL patterns
                val videoUrl = extractVideoFromIframe(iframeDoc)
                if (videoUrl != null) {
                    sources.add(VideoSource(videoUrl, "Server ${sources.size + 1}", 720))
                }
            } catch (e: Exception) {
                // ignore single iframe failures, continue with others
            }
        }

        // 3. If no sources found, provide a fallback with the page URL (user can use WebView)
        if (sources.isEmpty()) {
            sources.add(VideoSource("https://error.invalid/", "No sources found", 0))
        }
        return sources
    }

    // Extract a video URL from an iframe page
    private fun extractVideoFromIframe(doc: org.jsoup.nodes.Document): String? {
        // Common patterns:
        // - <video><source src="...">
        // - var player = jwplayer("player").setup({ file: "..." });
        // - data-src or href pointing to .mp4/.m3u8
        // For now we just search for video source tags
        val videoSource = doc.selectFirst("video source") ?: return null
        return videoSource.attr("src").takeIf { it.isNotBlank() }
    }

    // Simple URL fix (adds missing scheme)
    private fun fixUrl(url: String): String {
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return mainUrl.trimEnd('/') + url
        return url
    }
}
