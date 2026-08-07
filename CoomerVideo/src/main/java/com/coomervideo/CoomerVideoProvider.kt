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
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updates/" to "Latest",
        "$mainUrl/top-rated/" to "Top Rated",
        "$mainUrl/most-popular/" to "Most Viewed",
        "$mainUrl/shorts/" to "Shorts"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}$page/"
        val document = app.get(url).document

        val items = document.select("div.vx-main-card, div.vx-card, a.vx-card-short").mapNotNull {
            it.toSearchResult()
        }

        val hasNext = document.select(".pagination a.vx-next, .pagination a[rel=next]").isNotEmpty()

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items)),
            hasNext = hasNext
        )
    }

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

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = app.get(searchUrl).document
        
        return document.select("div.vx-main-card, div.vx-card, a.vx-card-short").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

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

        val streamRegex = Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*""")

        // 1. Process Main HTML for direct links
        for (match in streamRegex.findAll(html)) {
            val streamUrl = match.value.replace("&amp;", "&").replace("\\/", "/")
            
            if (mappedUrls.add(streamUrl)) {
                val isM3u8 = streamUrl.contains(".m3u8")
                val qualityStr = extractQualityFromUrl(streamUrl)
                val qualityVal = getQualityFromString(qualityStr)
                val sourceName = if (qualityStr != "Unknown") "$name ${qualityStr}p" else if (isM3u8) "$name HLS" else "$name MP4"

                callback.invoke(
                    newExtractorLink(name, sourceName, streamUrl, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.referer = data
                        this.quality = qualityVal
                    }
                )
                found = true

                // Dynamically build 1080p, 720p, and 480p variants
                if (streamUrl.contains(".mp4")) {
                    listOf("1080p", "720p", "480p").forEach { q ->
                        val variantUrl = when {
                            streamUrl.contains(Regex("""_\d{3,4}p\.mp4""")) -> streamUrl.replace(Regex("""_\d{3,4}p\.mp4"""), "_${q}.mp4")
                            streamUrl.contains(".mp4") -> streamUrl.replace(".mp4", "_${q}.mp4")
                            else -> null
                        }
                        if (variantUrl != null && mappedUrls.add(variantUrl)) {
                            val vQualityVal = getQualityFromString(q)
                            callback.invoke(
                                newExtractorLink(name, "$name ${q}", variantUrl, ExtractorLinkType.VIDEO) {
                                    this.referer = data
                                    this.quality = vQualityVal
                                }
                            )
                        }
                    }
                }
            }
        }

        // 2. Process embedded Iframes
        val iframes = document.select("iframe")
        for (iframe in iframes) {
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && src.startsWith("http")) {
                try {
                    if (loadExtractor(src, data, subtitleCallback, callback)) {
                        found = true
                    } else {
                        // Deep scrape the iframe
                        val iframeHtml = app.get(src, headers = mapOf("Referer" to data)).text
                        for (match in streamRegex.findAll(iframeHtml)) {
                            val streamUrl = match.value.replace("&amp;", "&").replace("\\/", "/")
                            
                            if (mappedUrls.add(streamUrl)) {
                                val isM3u8 = streamUrl.contains(".m3u8")
                                val qualityStr = extractQualityFromUrl(streamUrl)
                                val qualityVal = getQualityFromString(qualityStr)
                                val sourceName = if (qualityStr != "Unknown") "$name ${qualityStr}p" else if (isM3u8) "$name HLS" else "$name MP4"

                                callback.invoke(
                                    newExtractorLink(name, sourceName, streamUrl, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                        this.referer = src
                                        this.quality = qualityVal
                                    }
                                )
                                found = true

                                // Dynamically build 1080p, 720p, and 480p variants
                                if (streamUrl.contains(".mp4")) {
                                    listOf("1080p", "720p", "480p").forEach { q ->
                                        val variantUrl = when {
                                            streamUrl.contains(Regex("""_\d{3,4}p\.mp4""")) -> streamUrl.replace(Regex("""_\d{3,4}p\.mp4"""), "_${q}.mp4")
                                            streamUrl.contains(".mp4") -> streamUrl.replace(".mp4", "_${q}.mp4")
                                            else -> null
                                        }
                                        if (variantUrl != null && mappedUrls.add(variantUrl)) {
                                            val vQualityVal = getQualityFromString(q)
                                            callback.invoke(
                                                newExtractorLink(name, "$name ${q}", variantUrl, ExtractorLinkType.VIDEO) {
                                                    this.referer = src
                                                    this.quality = vQualityVal
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return found
    }

    // ---------- Quality Parsing Helpers ----------
    private fun extractQualityFromUrl(url: String): String {
        val qualityMatch = Regex("""(?i)(?:_|-|/)(\d{3,4})p?(?:\.mp4|/)""").find(url) 
            ?: Regex("""(?i)(\d{3,4})p""").find(url)
        return qualityMatch?.groupValues?.get(1) ?: "Unknown"
    }

    private fun getQualityFromString(qualityString: String): Int {
        return when {
            qualityString.contains("2160") -> Qualities.P2160.value
            qualityString.contains("1080") -> Qualities.P1080.value
            qualityString.contains("720") -> Qualities.P720.value
            qualityString.contains("480") -> Qualities.P480.value
            qualityString.contains("360") -> Qualities.P360.value
            qualityString.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }
}
