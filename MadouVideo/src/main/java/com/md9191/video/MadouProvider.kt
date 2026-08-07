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
        
        // Look for the image inside standard MacCMS detail elements
        val poster = document.selectFirst(".lazy, .vod-pic img, .mac_v_pic img, .play-pic img, .video-cover img")?.let {
            it.attr("data-original").ifBlank { it.attr("src") }
        } ?: document.selectFirst("meta[property=og:image], meta[itemprop=image]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            // By only assigning this when it is definitely valid, CloudStream will 
            // automatically fall back to the search page thumbnail instead of going blank.
            if (!poster.isNullOrBlank() && poster.startsWith("http")) {
                this.posterUrl = fixUrlNull(poster)
            }
            this.plot = plot
        }
    }

    // Custom unescape to handle MacCMS %uXXXX encoding which breaks standard URLDecoders
    private fun unescape(text: String): String {
        var result = text
        try {
            result = URLDecoder.decode(result.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {}
        
        val regex = Regex("%u([0-9A-Fa-f]{4})")
        var match = regex.find(result)
        while (match != null) {
            val hex = match.groupValues[1]
            val char = hex.toInt(16).toChar().toString()
            result = result.replace(match.value, char)
            match = regex.find(result)
        }
        return result
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
            val finalUrl = if (streamUrl.startsWith("//")) "https:$streamUrl" else streamUrl
            if (finalUrl.isBlank() || !finalUrl.startsWith("http")) return
            if (!mappedUrls.add(finalUrl)) return

            val isM3u8 = finalUrl.contains(".m3u8")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name + if (isM3u8) " HLS" else " MP4",
                    url = finalUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        suspend fun scanHtmlForStreams(sourceHtml: String, sourceUrl: String) {
            // 1. Scan for raw links
            val streamRegex = Regex("""(https?://[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*)""")
            for (match in streamRegex.findAll(sourceHtml)) {
                addStream(match.groupValues[1].replace("\\/", "/"), sourceUrl)
            }
            
            // 2. Deep Scan: MacCMS external players (like lbjx9.com) often hide the m3u8 in a Base64 string in JS
            val base64Regex = Regex("""['"]([a-zA-Z0-9+/=]+)['"]""")
            for (match in base64Regex.findAll(sourceHtml)) {
                val str = match.groupValues[1]
                // Base64 strings for http (aHR0c) usually start with this
                if (str.length > 20 && str.startsWith("aHR0c")) { 
                    try {
                        val decoded = String(Base64.decode(str, Base64.DEFAULT), Charsets.UTF_8)
                        if (decoded.contains(".m3u8") || decoded.contains(".mp4")) {
                            addStream(decoded, sourceUrl)
                        }
                    } catch(e: Exception){}
                }
            }
        }

        // 1. Process MacCMS Built-in Player Script (Crack the MacCMS encryption)
        val jsonMatch = Regex("""(?:player_a+?|player_data).*?=\s*(\{.*?\})""").find(html)
        if (jsonMatch != null) {
            val jsonStr = jsonMatch.groupValues[1]
            val urlRaw = Regex(""""url"\s*:\s*"([^"]+)"""").find(jsonStr)?.groupValues?.get(1)
            val encrypt = Regex(""""encrypt"\s*:\s*(\d+)""").find(jsonStr)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            if (urlRaw != null) {
                var streamUrl = urlRaw.replace("\\/", "/")
                
                try {
                    if (encrypt == 1) {
                        streamUrl = unescape(streamUrl)
                    } else if (encrypt == 2) {
                        // Standard MacCMS V10 encrypt 2 rule
                        streamUrl = unescape(streamUrl)
                        val decodedBytes = Base64.decode(streamUrl, Base64.DEFAULT)
                        streamUrl = unescape(String(decodedBytes, Charsets.UTF_8))
                    }
                } catch (e: Exception) {}

                var finalUrl = fixUrlNull(streamUrl)
                
                // If the link routes to an external player query parameter
                if (finalUrl != null && finalUrl.contains("url=")) {
                    val queryUrlMatch = Regex("""url=([^&]+)""").find(finalUrl)
                    if (queryUrlMatch != null) {
                        val potentialStream = unescape(queryUrlMatch.groupValues[1])
                        if (potentialStream.contains(".m3u8") || potentialStream.contains(".mp4")) {
                            finalUrl = potentialStream
                        }
                    }
                }

                if (finalUrl != null) {
                    if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
                    
                    if (Regex("""\.(m3u8|mp4)(?:\?.*)?$""").containsMatchIn(finalUrl)) {
                        addStream(finalUrl, data)
                    } else {
                        // It is an external player (e.g. lbjx9.com). Open it and deep scan the HTML!
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
            var src = fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") })
            if (src != null) {
                if (src.startsWith("//")) src = "https:$src"
                if (src.startsWith("http")) {
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
        }

        return found
    }
}
