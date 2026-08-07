package com.md9191.video

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Base64
import java.net.URLDecoder

class MadouProvider : MainAPI() {
    override var mainUrl = "https://www.9191md.me"
    override var name = "麻豆视频"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/index.php/vod/type/id/1" to "麻豆视频",
        "$mainUrl/index.php/vod/type/id/9" to "成人头条",
        "$mainUrl/index.php/vod/type/id/4" to "蜜桃传媒",
        "$mainUrl/index.php/vod/type/id/7" to "精东影业",
        "$mainUrl/index.php/vod/type/id/2" to "91制片厂",
        "$mainUrl/index.php/vod/type/id/3" to "天美传媒",
        "$mainUrl/index.php/vod/type/id/22" to "玩偶姐姐",
        "$mainUrl/index.php/vod/type/id/27" to "糖心Vlog"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "${request.data}.html" else "${request.data}/page/$page.html"
        val document = app.get(url).document

        val items = document.select(".detail_right_div ul li").mapNotNull {
            it.toSearchResult()
        }

        val hasNext = document.select("a:contains(下一页), a.page-next").isNotEmpty()

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items)),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val img = this.selectFirst("img") ?: return null
        
        val title = img.attr("alt").ifBlank { img.attr("title") }.ifBlank { this.select("p").last()?.text() } ?: "Video"
        val posterUrl = fixUrlNull(img.attr("data-original").ifBlank { img.attr("src") })

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/index.php/vod/search.html"
        
        val document = app.post(
            url = searchUrl,
            data = mapOf("wd" to query)
        ).document
        
        return document.select(".detail_right_div ul li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("title")?.text()?.substringBefore("_")?.trim() ?: "Video"
        val plot = document.selectFirst(".desc, .vod-content")?.text()?.trim()
        
        // Search multiple potential locations for a valid poster image
        val poster = document.selectFirst("meta[property=og:image], meta[itemprop=image]")?.attr("content")
            ?: document.selectFirst(".player img, .video-cover img, .detail-pic img")?.attr("src")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            // Only set the poster if we actually found one, so we don't accidentally overwrite 
            // the thumbnail from the search page with a blank string.
            if (!poster.isNullOrBlank()) {
                this.posterUrl = fixUrlNull(poster)
            }
            this.plot = plot
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

        suspend fun addStream(streamUrl: String, referer: String) {
            if (streamUrl.isBlank() || !streamUrl.startsWith("http")) return
            if (!mappedUrls.add(streamUrl)) return

            val isM3u8 = streamUrl.contains(".m3u8")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name + if (isM3u8) " HLS" else " MP4",
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        suspend fun scanHtmlForStreams(sourceHtml: String, sourceUrl: String) {
            val streamRegex = Regex("""(https?://[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*)""")
            for (match in streamRegex.findAll(sourceHtml)) {
                addStream(match.groupValues[1].replace("\\/", "/"), sourceUrl)
            }
            val varRegex = Regex("""(?:url|source|src|link)\s*[:=]\s*['"](https?://[^\s"'<>]+?)['"]""")
            for (match in varRegex.findAll(sourceHtml)) {
                val link = match.groupValues[1].replace("\\/", "/")
                if (link.contains(".m3u8") || link.contains(".mp4")) {
                    addStream(link, sourceUrl)
                }
            }
        }

        // 1. Process MacCMS Built-in Player JSON
        val jsonMatch = Regex("""(?:player_a+?|player_data).*?=\s*(\{.*?\})""").find(html)
        if (jsonMatch != null) {
            val jsonStr = jsonMatch.groupValues[1]
            val urlRaw = Regex(""""url"\s*:\s*"([^"]+)"""").find(jsonStr)?.groupValues?.get(1)
            val encrypt = Regex(""""encrypt"\s*:\s*(\d+)""").find(jsonStr)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            if (urlRaw != null) {
                var streamUrl = urlRaw.replace("\\/", "/")
                
                // Decrypt MacCMS tokens based on their native encoding rules
                try {
                    if (encrypt == 1) {
                        streamUrl = URLDecoder.decode(streamUrl, "UTF-8")
                    } else if (encrypt == 2) {
                        streamUrl = String(Base64.decode(streamUrl, Base64.DEFAULT))
                        streamUrl = URLDecoder.decode(streamUrl, "UTF-8")
                    }
                } catch (e: Exception) {}

                var finalUrl = fixUrlNull(streamUrl)
                
                // Unpack if the stream URL is passed as a nested query parameter (e.g. ?url=https://...)
                if (finalUrl != null && finalUrl.contains("url=")) {
                    val queryUrlMatch = Regex("""url=([^&]+)""").find(finalUrl)
                    if (queryUrlMatch != null) {
                        val potentialStream = URLDecoder.decode(queryUrlMatch.groupValues[1], "UTF-8")
                        if (potentialStream.contains(".m3u8") || potentialStream.contains(".mp4")) {
                            finalUrl = potentialStream
                        }
                    }
                }

                if (finalUrl != null) {
                    // Check if the decrypted URL is a raw media file
                    if (Regex("""\.(m3u8|mp4)(?:\?.*)?$""").containsMatchIn(finalUrl)) {
                        addStream(finalUrl, data)
                    } else {
                        // Otherwise, it is an embedded iframe player (like lbjx9.com) - Fetch it to get the raw stream!
                        try {
                            val iframeHtml = app.get(finalUrl, headers = mapOf("Referer" to data)).text
                            scanHtmlForStreams(iframeHtml, finalUrl)
                        } catch (e: Exception) {}
                    }
                }
            }
        }

        // 2. Scan explicitly embedded streams directly in the main page
        scanHtmlForStreams(html, data)

        // 3. Scan Embedded Iframes in the DOM
        for (iframe in document.select("iframe")) {
            val src = fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") })
            if (src != null && src.startsWith("http")) {
                try {
                    if (loadExtractor(src, data, subtitleCallback, callback)) {
                        found = true
                    } else {
                        val iframeHtml = app.get(src, headers = mapOf("Referer" to data)).text
                        scanHtmlForStreams(iframeHtml, src)
                    }
                } catch (e: Exception) {}
            }
        }

        return found
    }
}
j