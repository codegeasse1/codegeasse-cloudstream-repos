package com.leakporner

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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
        val title = document.selectFirst(".entry-title")?.text()
            ?: document.select("meta[property=og:title]").attr("content")
            ?: "No title"
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".post-thumbnail img")?.attr("src")

        // In modern CS3, load() returns metadata, loadLinks() handles the video streams.
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

        // Try direct video tags first
        document.select("video source").forEach { src ->
            val url = src.attr("src")
            if (url.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Direct",
                        url = fixUrl(url),
                        referer = data,
                        quality = Qualities.P1080.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
                found = true
            }
        }

        // Try iframe sources (multiframe player)
        document.select("iframe[src]").forEach { iframe ->
            val srcUrl = iframe.attr("src")
            if (srcUrl.isNotBlank()) {
                runCatching {
                    val iframeDoc = app.get(fixUrl(srcUrl)).document
                    iframeDoc.select("video source").forEach { src ->
                        val videoUrl = src.attr("src")
                        if (videoUrl.isNotBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "Iframe Server",
                                    url = fixUrl(videoUrl),
                                    referer = fixUrl(srcUrl),
                                    quality = Qualities.P720.value,
                                    type = ExtractorLinkType.VIDEO
                                )
                            )
                            found = true
                        }
                    }
                }
            }
        }

        return found
    }

    // ---------- Helpers ----------
    private fun parseVideoItem(element: org.jsoup.nodes.Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href]") ?: return null
        val href = linkEl.attr("href")
        
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
}
