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
        
        // Grab the explicit player poster to avoid grabbing sidebar thumbnails
        val posterRaw = document.selectFirst(".lazy, .vod-pic img, .mac_v_pic img, .play-pic img, .video-cover img, .detail_pic img")?.let {
            it.attr("data-original").ifBlank { it.attr("src") }
        } ?: document.selectFirst("meta[property=og:image], meta[itemprop=image]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            val fixedPoster = fixUrlNull(posterRaw)
            if (!fixedPoster.isNullOrBlank()) {
                this.posterUrl = fixedPoster
                // This explicitly fixes the "black background" where the play button shows!
                this.backgroundPosterUrl = fixedPoster 
            }
            this.plot = plot
        }
    }

    // Custom unescape function necessary to break MacCMS %uXXXX JavaScript encoding
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
        document.setBaseUri(data) // Crucial for resolving relative iframe URLs
        
        val mappedUrls = mutableSetOf<String>()

        suspend fun addStream(streamUrl: String, referer: String) {
            val finalUrl = if (streamUrl.startsWith("//")) "https:$streamUrl" else streamUrl
            val cleanUrl = finalUrl.substringBefore("\"").substringBefore("'").substringBefore("\\").trim()
            
            if (cleanUrl.isBlank() || !cleanUrl.startsWith("http")) return
            if (!cleanUrl.contains(".m3u8") && !cleanUrl.contains(".mp4")) return
            if (!mappedUrls.add(cleanUrl)) return

            val isM3u8 = cleanUrl.contains(".m3u8")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name + if (isM3u8) " HLS" else " MP4",
                    url = cleanUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        suspend fun scanHtmlForStreams(sourceHtml: String, sourceUrl: String) {
            // 1. Raw M3U8 / MP4 Links (handles escaped \/ slashes)
            val streamRegex = Regex("""(https?[\\/]+[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*)""")
            for (match in streamRegex.findAll(sourceHtml)) {
                addStream(match.groupValues[1].replace("\\/", "/"), sourceUrl)
            }
            
            // 2. Base64 Encoded Links (aHR0c = http)
            val base64Regex = Regex("""(aHR0c[a-zA-Z0-9+/=]+)""")
            for (match in base64Regex.findAll(sourceHtml)) {
                try {
                    val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
                    if (decoded.contains(".m3u8") || decoded.contains(".mp4")) {
                        addStream(decoded, sourceUrl)
                    }
                } catch (e: Exception) {}
            }

            // 3. URL Encoded Links
            val urlEncodedRegex = Regex("""(https?%3A%2F%2F[^\s"'<>]+)""")
            for (match in urlEncodedRegex.findAll(sourceHtml)) {
                try {
                    val decoded = URLDecoder.decode(match.groupValues[1], "UTF-8")
                    if (decoded.contains(".m3u8") || decoded.contains(".mp4")) {
                        addStream(decoded, sourceUrl)
                    }
                } catch (e: Exception) {}
            }
        }

        // --- STEP 1: Process MacCMS Built-in Player Script ---
        val jsonMatch = Regex("""player_[a-z0-9_]+\s*=\s*(\{.*?\})""").find(html)
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
                        streamUrl = unescape(streamUrl)
                        val decodedBytes = Base64.decode(streamUrl, Base64.DEFAULT)
                        streamUrl = unescape(String(decodedBytes, Charsets.UTF_8))
                    }
                } catch (e: Exception) {}

                var finalUrl = fixUrlNull(streamUrl)
                
                // If the decrypted link is actually an external player query parameter
                if (finalUrl != null && finalUrl.contains("url=")) {
                    val queryUrlMatch = Regex("""url=([^&]+)""").find(finalUrl)
                    if (queryUrlMatch != null) {
                        val potentialStream = unescape(queryUrlMatch.groupValues[1])
                        scanHtmlForStreams(potentialStream, data) 
                    }
                }

                if (finalUrl != null) {
                    if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
                    
                    if (finalUrl.contains(".m3u8") || finalUrl.contains(".mp4")) {
                        addStream(finalUrl, data)
                    } else if (finalUrl.startsWith("http")) {
                        // Deep scrape the external player HTML (e.g. lbjx9.com)
                        try {
                            val iframeHtml = app.get(finalUrl, headers = mapOf("Referer" to data)).text
                            scanHtmlForStreams(iframeHtml, finalUrl)
                        } catch (e: Exception) {}
                    }
                }
            }
        }

        // --- STEP 2: Aggressive sweep of the main page source code ---
        scanHtmlForStreams(html, data)

        // --- STEP 3: Find and deeply scan all nested Iframes ---
        for (iframe in document.select("iframe")) {
            // Using abs:src ensures we catch relative player URLs properly
            val srcRaw = iframe.attr("abs:src").ifBlank { iframe.attr("src") }.ifBlank { iframe.attr("data-src") }
            val src = fixUrlNull(srcRaw)
            
            if (src != null && src.startsWith("http")) {
                scanHtmlForStreams(src, data)
                try {
                    if (!loadExtractor(src, data, subtitleCallback, callback)) {
                        val iframeHtml = app.get(src, headers = mapOf("Referer" to data)).text
                        scanHtmlForStreams(iframeHtml, src)
                    } else {
                        found = true
                    }
                } catch (e: Exception) {}
            }
        }

        return found
    }
}
