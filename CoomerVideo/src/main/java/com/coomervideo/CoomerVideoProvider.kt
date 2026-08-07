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
        val foundQualities = mutableSetOf<Int>()

        suspend fun extractStreams(sourceHtml: String, referer: String) {
            // Regex 1: KVS Flashvars (video_url, video_alt_url, etc.)
            val flashvarsRegex = Regex("""(?:video_url|video_alt_url\d*)\s*[:=]\s*['"](http[^'"]+)['"]""")
            // Regex 2: Any explicit get_file or .mp4 links sitting directly in the scripts/HTML
            val generalRegex = Regex("""['"](https?://[^'"]+?(?:/get_file/|\.mp4)[^'"]*)['"]""")

            val allMatches = flashvarsRegex.findAll(sourceHtml).map { it.groupValues[1] } +
                             generalRegex.findAll(sourceHtml).map { it.groupValues[1] }

            for (rawUrl in allMatches) {
                val streamUrl = rawUrl.replace("&amp;", "&").replace("\\/", "/")

                // Skip non-video assets and preview thumbnails
                if (!streamUrl.contains(".mp4") && !streamUrl.contains("get_file") && !streamUrl.contains(".m3u8")) continue
                if (streamUrl.contains("preview", ignoreCase = true)) continue
                
                if (mappedUrls.add(streamUrl)) {
                    val isM3u8 = streamUrl.contains(".m3u8")
                    
                    val qualityVal = when {
                        streamUrl.contains("2160p") || streamUrl.contains("4k", ignoreCase = true) -> Qualities.P2160.value
                        streamUrl.contains("1080p") -> Qualities.P1080.value
                        streamUrl.contains("720p") -> Qualities.P720.value
                        streamUrl.contains("480p") || streamUrl.contains("360p") -> Qualities.P480.value
                        else -> if (isM3u8) Qualities.Unknown.value else Qualities.P480.value // Base mp4 is typically 480p
                    }
                    
                    // Prevent duplicate quality links from cluttering the UI menu
                    if (foundQualities.add(qualityVal) || isM3u8) {
                        val qLabel = when(qualityVal) {
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

        // 1. Process Main HTML for stream data
        extractStreams(html, data)

        // 2. Scan for embedded Iframes
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
