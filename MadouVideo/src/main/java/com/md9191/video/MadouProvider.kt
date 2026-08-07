package com.md9191.video

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import android.util.Base64

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
        val document = app.post(searchUrl, data = mapOf("wd" to query)).document
        return document.select(".detail_right_div ul li").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("title")?.text()?.substringBefore("_")?.trim() ?: "Video"
        val plot = document.selectFirst(".desc, .vod-content")?.text()?.trim()
        
        val posterRaw = document.selectFirst(".lazy, .vod-pic img, .mac_v_pic img, .play-pic img, .video-cover img, .detail_pic img")?.let {
            it.attr("data-original").ifBlank { it.attr("src") }
        } ?: document.selectFirst("meta[property=og:image], meta[itemprop=image]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            val fixedPoster = fixUrlNull(posterRaw)
            if (!fixedPoster.isNullOrBlank()) {
                this.posterUrl = fixedPoster
                this.backgroundPosterUrl = fixedPoster 
            }
            this.plot = plot
        }
    }

    // A crash-proof unescape function that mimics JavaScript behavior
    private fun jsUnescape(str: String): String {
        val sb = java.lang.StringBuilder()
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (c == '%' && i + 1 < str.length) {
                if (str[i + 1] == 'u' && i + 5 < str.length) {
                    try {
                        sb.append(str.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 6
                        continue
                    } catch (e: Exception) {}
                } else if (i + 2 < str.length) {
                    try {
                        sb.append(str.substring(i + 1, i + 3).toInt(16).toChar())
                        i += 3
                        continue
                    } catch (e: Exception) {}
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val html = app.get(data).text
        val mappedUrls = mutableSetOf<String>()

        suspend fun addStream(streamUrl: String, referer: String) {
            var cleanUrl = streamUrl.replace("\\/", "/")
            cleanUrl = cleanUrl.substringBefore("\"").substringBefore("'").trim()
            
            // Aggressive Unwrapper: Strips away lbjx9.com or other query parameter wrappers
            while (cleanUrl.contains("url=http") || cleanUrl.contains("v=http")) {
                val target = if (cleanUrl.contains("url=http")) "url=" else "v="
                cleanUrl = cleanUrl.substringAfter(target).substringBefore("&")
                cleanUrl = jsUnescape(cleanUrl)
            }

            if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
            
            if (cleanUrl.isBlank() || !cleanUrl.startsWith("http")) return
            if (!cleanUrl.contains(".m3u8") && !cleanUrl.contains(".mp4")) return
            if (!mappedUrls.add(cleanUrl)) return

            val isM3u8 = cleanUrl.contains(".m3u8")
            callback.invoke(
                newExtractorLink(name, name + if (isM3u8) " HLS" else " MP4", cleanUrl, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        // 1. Force decrypt the main MacCMS player script
        val jsonMatch = Regex("""player_[a-z0-9_]+\s*=\s*(\{.*?\})""").find(html)
        if (jsonMatch != null) {
            val jsonStr = jsonMatch.groupValues[1]
            val urlRaw = Regex(""""url"\s*:\s*"([^"]+)"""").find(jsonStr)?.groupValues?.get(1)
            val encrypt = Regex(""""encrypt"\s*:\s*(\d+)""").find(jsonStr)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            if (urlRaw != null) {
                var streamUrl = urlRaw
                try {
                    if (encrypt == 1) {
                        streamUrl = jsUnescape(streamUrl)
                    } else if (encrypt == 2) {
                        streamUrl = jsUnescape(streamUrl)
                        streamUrl = String(Base64.decode(streamUrl, Base64.DEFAULT), Charsets.UTF_8)
                        streamUrl = jsUnescape(streamUrl)
                    }
                } catch (e: Exception) {}
                
                addStream(streamUrl, data)
            }
        }

        // 2. Scan explicitly for any m3u8/mp4 URLs left in the open
        Regex("""(https?[\\/]+[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*)""").findAll(html).forEach {
            addStream(it.groupValues[1], data)
        }
        
        // 3. Scan for stray Base64 strings starting with http (aHR0c)
        Regex("""(aHR0c[a-zA-Z0-9+/=]+)""").findAll(html).forEach {
            try {
                val decoded = String(Base64.decode(it.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
                addStream(decoded, data)
            } catch (e: Exception) {}
        }
        
        // 4. Scan hardcoded iframes (like the lbjx9.com iframe)
        val document = Jsoup.parse(html)
        document.setBaseUri(data)
        for (iframe in document.select("iframe")) {
            val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
            if (src.startsWith("http")) {
                addStream(src, data)
            }
        }

        return found
    }
}
