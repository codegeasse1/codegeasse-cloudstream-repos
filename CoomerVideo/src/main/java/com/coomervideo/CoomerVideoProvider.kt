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
        
        // This tracks IDs so we don't accidentally generate duplicate link blocks
        val baseVideoIds = mutableSetOf<String>()

        // FIX: Added 'suspend' keyword here!
        suspend fun extractStreams(sourceHtml: String, referer: String): Boolean {
            var localFound = false
            
            // Regex strictly captures the base video ID and prevents matching preview clips
            val mp4Regex = Regex("""(https?://[^\s"'<>]+?/(\d+)(?:_\d{3,4}p)?\.mp4[^\s"'<>]*)""")
            
            for (match in mp4Regex.findAll(sourceHtml)) {
                val fullUrl = match.groupValues[1].replace("&amp;", "&").replace("\\/", "/")
                val videoId = match.groupValues[2]
                
                // Ignore preview files or previously processed IDs
                if (fullUrl.contains("preview", ignoreCase = true)) continue
                if (baseVideoIds.contains(videoId)) continue
                baseVideoIds.add(videoId)

                // Strip any existing _1080p / _720p tags to get the pure base URL (which is 480p)
                val baseUrl = fullUrl.replace(Regex("""${videoId}_\d{3,4}p\.mp4"""), "$videoId.mp4")

                // Generate exactly three variants using the base video ID
                val url1080 = baseUrl.replace("$videoId.mp4", "${videoId}_1080p.mp4")
                val url720 = baseUrl.replace("$videoId.mp4", "${videoId}_720p.mp4")
                val url480 = baseUrl

                callback.invoke(newExtractorLink(name, "CoomerVideo 1080p", url1080, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = Qualities.P1080.value
                })
                
                callback.invoke(newExtractorLink(name, "CoomerVideo 720p", url720, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = Qualities.P720.value
                })
                
                callback.invoke(newExtractorLink(name, "CoomerVideo 480p", url480, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = Qualities.P480.value
                })
                
                localFound = true
            }

            // M3U8 Catch-all (Just in case the site provides HLS playlists)
            val m3u8Regex = Regex("""(https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*)""")
            for (match in m3u8Regex.findAll(sourceHtml)) {
                val m3Url = match.groupValues[1].replace("&amp;", "&").replace("\\/", "/")
                if (baseVideoIds.add(m3Url)) {
                    callback.invoke(newExtractorLink(name, "CoomerVideo HLS", m3Url, ExtractorLinkType.M3U8) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                    })
                    localFound = true
                }
            }
            
            return localFound
        }

        // 1. Check main page HTML
        if (extractStreams(html, data)) {
            found = true
        }

        // 2. Scan for embedded Iframes
        for (iframe in document.select("iframe")) {
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && src.startsWith("http")) {
                try {
                    if (loadExtractor(src, data, subtitleCallback, callback)) {
                        found = true
                    } else {
                        val iframeHtml = app.get(src, headers = mapOf("Referer" to data)).text
                        if (extractStreams(iframeHtml, src)) {
                            found = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return found
    }
}
