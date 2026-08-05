package com.leakporner

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document as JsoupDocument

class LeakPornerProvider : MainAPI() {
    override var mainUrl = "https://leakporner.org"
    override var name = "LeakPorner"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val providerType = ProviderType.NSFW

    // ---------- Main Page ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document
        val items = document.select("article.loop-video").mapNotNull { el -> parseVideoItem(el) }
        val hasNext = document.select(".pagination a.next").isNotEmpty()
        return HomePageResponse(items, hasNext)
    }

    // ---------- Search ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("article.loop-video").mapNotNull { el ->
            parseVideoItem(el)?.let { video ->
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

    // ---------- Load video details & sources ----------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst(".entry-title")?.text()
            ?: document.select("meta[property=og:title]").attr("content")
            ?: "No title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".post-thumbnail img")?.attr("src")

        val sources = extractVideoSources(document)

        return MovieLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = TvType.NSFW,
            dataUrl = url,
            posterUrl = fixUrl(poster),
            sources = sources
        )
    }

    // ---------- Helpers ----------
    private fun parseVideoItem(element: org.jsoup.nodes.Element): HomePageEntry? {
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

    private fun extractVideoSources(document: JsoupDocument): List<VideoSource> {
        val sources = mutableListOf<VideoSource>()

        // Try direct video tags first
        document.select("video source").forEach { src ->
            val url = src.attr("src")
            if (url.isNotBlank()) sources.add(VideoSource(url, "Direct", 1080))
        }

        // Try iframe sources (muliframe player)
        val iframes = document.select("iframe[src]")
        iframes.forEach { iframe ->
            val srcUrl = iframe.attr("src")
            if (srcUrl.isBlank()) return@forEach
            try {
                val iframeDoc = app.get(fixUrl(srcUrl)).document
                iframeDoc.select("video source").forEach { src ->
                    val videoUrl = src.attr("src")
                    if (videoUrl.isNotBlank()) {
                        sources.add(VideoSource(videoUrl, "Server ${sources.size + 1}", 720))
                    }
                }
            } catch (_: Exception) {
                // ignore broken iframes
            }
        }

        // Fallback
        if (sources.isEmpty()) {
            sources.add(VideoSource("https://error.invalid/", "No sources found", 0))
        }
        return sources
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
