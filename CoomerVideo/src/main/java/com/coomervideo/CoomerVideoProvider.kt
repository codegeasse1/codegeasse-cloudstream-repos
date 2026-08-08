package com.coomervideo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class CoomerVideoProvider : MainAPI() {
    override var mainUrl = "https://official.coomer.com.co"
    override var name = "CoomerVideo"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.TvSeries)

    // Main page tabs: added "Models"
    override val mainPage = mainPageOf(
        "$mainUrl/latest-updates/" to "Latest",
        "$mainUrl/top-rated/" to "Top Rated",
        "$mainUrl/most-popular/" to "Most Viewed",
        "$mainUrl/shorts/" to "Shorts",
        "$mainUrl/models/" to "Models"
    )

    // ---------------------------------------------------------------
    // MAIN PAGE HANDLER
    // ---------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document

        val isModelsPage = request.data.contains("/models/")

        // Compiler safety fix: Replaced mapNotNull with a flat loop
        val items = mutableListOf<SearchResponse>()
        
        if (isModelsPage) {
            for (element in document.select("a[href*=/models/], a[href*=/model/]")) {
                val res = parseModelItem(element)
                if (res != null) items.add(res)
            }
        } else {
            for (element in document.select("div.vx-main-card, div.vx-card, a.vx-card-short")) {
                val res = element.toSearchResult()
                if (res != null) items.add(res)
            }
        }

        val hasNext = document.select(".pagination a.vx-next, .pagination a[rel=next]").isNotEmpty()

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items.distinctBy { it.url })),
            hasNext = hasNext
        )
    }

    // ---------------------------------------------------------------
    // ITEM PARSERS
    // ---------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("a.vx-media") ?: this.takeIf { it.tagName() == "a" } ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null

        val imgEl = this.selectFirst("img") ?: return null
        val posterUrl = fixUrlNull(
            imgEl.attr("data-webp").ifBlank {
                imgEl.attr("data-original").ifBlank {
                    imgEl.attr("src")
                }
            }
        )
        val finalPoster = if (posterUrl?.startsWith("data:image") == true) null else posterUrl

        val title = imgEl.attr("alt").ifBlank {
            this.selectFirst(".vx-text")?.text()?.trim()
        } ?: "Video"

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = finalPoster
        }
    }

    private fun parseModelItem(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*=/models/], a[href*=/model/]") ?: element.takeIf { it.tagName() == "a" && it.attr("href").contains("model") } ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null

        val imgEl = element.selectFirst("img")
        val poster = imgEl?.let {
            it.attr("data-webp").ifBlank {
                it.attr("data-original").ifBlank {
                    it.attr("src")
                }
            }
        } ?: ""

        val title = element.selectFirst(".model-name, .name, .title, .vx-text")?.text()?.trim()
            ?: linkEl.attr("title")
            ?: linkEl.text().trim()
            ?: "Model"

        // IMPORTANT: Return TvSeries so CloudStream knows to show the Episode List UI
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl).document

        // Compiler safety fix: Replaced mapNotNull with flat loops
        val results = mutableListOf<SearchResponse>()
        
        for (element in document.select("div.vx-main-card, div.vx-card, a.vx-card-short")) {
            element.toSearchResult()?.let { results.add(it) }
        }
        
        for (element in document.select("a[href*=/models/], a[href*=/model/]")) {
            parseModelItem(element)?.let { results.add(it) }
        }

        return results.distinctBy { it.url }
    }

    // ---------------------------------------------------------------
    // LOAD (Detail Page)
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val videoElements = document.select("div.vx-main-card, div.vx-card, a.vx-card-short")
        
        // Detect if this is an actor profile page (like LeakPorner)
        val isModelPage = url.contains("/models/") || url.contains("/model/") || 
                (document.select("iframe, video, .player").isEmpty() && videoElements.isNotEmpty())

        if (isModelPage) {
            val modelTitle = document.selectFirst("h1, .page-title, .title, .model-name")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("-")?.trim()
                ?: "Model Videos"

            val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".model-image img, .post-thumbnail img, .avatar img")?.let {
                    it.attr("data-webp").ifBlank {
                        it.attr("data-original").ifBlank {
                            it.attr("src")
                        }
                    }
                }

            val episodes = mutableListOf<Episode>()
            videoElements.forEachIndexed { index, el ->
                val linkEl = el.selectFirst("a.vx-media") ?: el.takeIf { it.tagName() == "a" } ?: return@forEachIndexed
                val videoHref = fixUrlNull(linkEl.attr("href")) ?: return@forEachIndexed
                if (videoHref == url) return@forEachIndexed // Prevent infinite loops

                val imgEl = el.selectFirst("img")
                val videoTitle = imgEl?.attr("alt")?.ifBlank { null }
                    ?: el.selectFirst(".vx-text")?.text()?.trim()
                    ?: "Video ${index + 1}"

                val videoPoster = imgEl?.let {
                    it.attr("data-webp").ifBlank {
                        it.attr("data-original").ifBlank {
                            it.attr("src")
                        }
                    }
                }

                episodes.add(
                    newEpisode(videoHref) {
                        this.name = videoTitle
                        this.episode = index + 1
                        this.posterUrl = videoPoster
                    }
                )
            }

            return newTvSeriesLoadResponse(modelTitle, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = "Videos from $modelTitle"
            }
        }

        // ---------------------------------------------------------------
        // NORMAL SINGLE VIDEO PAGE
        // ---------------------------------------------------------------
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Video"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = document.select(".vx-tags-list a").map { it.text().trim() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            this.tags = tags
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val html = app.get(data).text
        val document = Jsoup.parse(html)
        val mappedUrls = mutableSetOf<String>()
        val foundQualities = mutableSetOf<Int>()

        fun extractStreams(sourceHtml: String, referer: String) {
            val flashvarsRegex = Regex("""(?:video_url|video_alt_url\d*)\s*[:=]\s*['"](http[^'"]+)['"]""")
            val generalRegex = Regex("""['"](https?://[^'"]+?(?:/get_file/|\.mp4)[^'"]*)['"]""")

            val allMatches = mutableListOf<String>()
            flashvarsRegex.findAll(sourceHtml).forEach { allMatches.add(it.groupValues[1]) }
            generalRegex.findAll(sourceHtml).forEach { allMatches.add(it.groupValues[1]) }

            for (rawUrl in allMatches) {
                val streamUrl = rawUrl.replace("&amp;", "&").replace("\\/", "/")

                if (!streamUrl.contains(".mp4") && !streamUrl.contains("get_file") && !streamUrl.contains(".m3u8")) continue
                if (streamUrl.contains("preview", ignoreCase = true)) continue

                if (mappedUrls.add(streamUrl)) {
                    val isM3u8 = streamUrl.contains(".m3u8")

                    val qualityVal = when {
                        streamUrl.contains("2160p") || streamUrl.contains("4k", ignoreCase = true) -> Qualities.P2160.value
                        streamUrl.contains("1080p") -> Qualities.P1080.value
                        streamUrl.contains("720p") -> Qualities.P720.value
                        streamUrl.contains("480p") || streamUrl.contains("360p") -> Qualities.P480.value
                        else -> if (isM3u8) Qualities.Unknown.value else Qualities.P480.value
                    }

                    if (foundQualities.add(qualityVal) || isM3u8) {
                        val qLabel = when (qualityVal) {
                            Qualities.P2160.value -> "4K"
                            Qualities.P1080.value -> "1080p"
                            Qualities.P720.value -> "720p"
                            Qualities.P480.value -> "480p"
                            else -> "MP4"
                        }

                        val labelName = if (isM3u8) "CoomerVideo HLS" else "CoomerVideo $qLabel"

                        callback.invoke(
                            newExtractorLink(name, labelName, streamUrl, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                this.referer = referer
                                this.quality = qualityVal
                            }
                        )
                        found = true
                    }
                }
            }
        }

        extractStreams(html, data)

        for (iframe in document.select("iframe")) {
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && src.startsWith("http")) {
                try {
                    if (loadExtractor(src, data, subtitleCallback, callback)) {
                        found = true
                    } else {
                        val iframeHtml = app.get(src, headers = mapOf("Referer" to data)).text
                        extractStreams(iframeHtml, src)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return found
    }
}
