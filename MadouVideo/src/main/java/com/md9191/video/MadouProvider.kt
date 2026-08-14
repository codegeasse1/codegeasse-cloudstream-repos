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
import java.net.URI

class MadouProvider : MainAPI() {
    override var mainUrl = "https://www.9191md.me"
    override var name = "麻豆视频"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val mobileHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    )

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
            var cleanUrl = streamUrl.trim()
            var actualReferer = referer
            
            // Unwrapper: Remember the wrapper URL (lbjx9.com) so we can use it as the required referer later
            if (cleanUrl.contains("url=http")) {
                actualReferer = cleanUrl.substringBefore("?url=")
                val target = cleanUrl.substringAfter("url=").substringBefore("&")
                try { 
                    val decoded = URLDecoder.decode(target, "UTF-8") 
                    if (decoded.startsWith("http")) cleanUrl = decoded
                } catch(e:Exception){}
            } else if (cleanUrl.contains("v=http")) {
                actualReferer = cleanUrl.substringBefore("?v=")
                val target = cleanUrl.substringAfter("v=").substringBefore("&")
                try { 
                    val decoded = URLDecoder.decode(target, "UTF-8") 
                    if (decoded.startsWith("http")) cleanUrl = decoded
                } catch(e:Exception){}
            }

            // Fallback: If it's a known protected CDN, force lbjx9.com headers to bypass 403 Forbidden errors
            if (cleanUrl.contains("cdn2020.com") || cleanUrl.contains("97img.com")) {
                actualReferer = "https://lbjx9.com/"
            }
            if (actualReferer.isBlank() || !actualReferer.startsWith("http")) {
                actualReferer = mainUrl
            }

            if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
            if (!cleanUrl.startsWith("http")) return
            if (!cleanUrl.contains(".m3u8") && !cleanUrl.contains(".mp4")) return
            if (!mappedUrls.add(cleanUrl)) return

            val isM3u8 = cleanUrl.contains(".m3u8")
            
            val origin = try {
                val uri = URI(actualReferer)
                "${uri.scheme}://${uri.authority}"
            } catch (e: Exception) {
                "https://lbjx9.com"
            }
            
            callback.invoke(
                newExtractorLink(name, name + if (isM3u8) " HLS" else " MP4", cleanUrl, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = actualReferer
                    // Injecting headers directly into the player prevents ERROR_CODE_IO_BAD_HTTP_STATUS
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
                        "Origin" to origin,
                        "Referer" to actualReferer
                    )
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        suspend fun scrapeHtmlForStreams(sourceHtml: String, refererUrl: String) {
            val stringsToTest = mutableSetOf<String>()
            
            Regex("""(https?[\\/]+[^\s"'<>]+)""").findAll(sourceHtml).forEach { stringsToTest.add(it.groupValues[1]) }
            Regex("""(aHR0c[a-zA-Z0-9+/=]+)""").findAll(sourceHtml).forEach { stringsToTest.add(it.groupValues[1]) }
            Regex("""(https?%3A%2F%2F[^\s"'<>]+)""").findAll(sourceHtml).forEach { stringsToTest.add(it.groupValues[1]) }
            Regex("""['"]url['"]\s*:\s*['"]([^'"]+)['"]""").findAll(sourceHtml).forEach { stringsToTest.add(it.groupValues[1]) }

            for (str in stringsToTest) {
                var decoded = str.replace("\\/", "/")
                
                if (decoded.contains("%u")) decoded = jsUnescape(decoded)
                
                if (decoded.startsWith("aHR0c")) {
                    try { decoded = String(Base64.decode(decoded, Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) {}
                }
                
                if (decoded.contains("%3A", ignoreCase = true)) {
                    try { decoded = URLDecoder.decode(decoded, "UTF-8") } catch (e: Exception) {}
                }
                
                if (decoded.contains("%u")) decoded = jsUnescape(decoded) 
                
                if (decoded.startsWith("aHR0c")) {
                    try { decoded = String(Base64.decode(decoded, Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) {}
                }

                addStream(decoded, refererUrl)
            }
        }

        scrapeHtmlForStreams(html, data)

        val document = Jsoup.parse(html)
        document.setBaseUri(data)
        
        for (iframe in document.select("iframe")) {
            val srcRaw = iframe.attr("abs:src").ifBlank { iframe.attr("src") }.ifBlank { iframe.attr("data-src") }
            val src = fixUrlNull(srcRaw)
            
            if (src != null && src.startsWith("http")) {
                addStream(src, data) 
                
                if (!src.contains(".m3u8") && !src.contains(".mp4")) {
                    try {
                        if (!loadExtractor(src, data, subtitleCallback, callback)) {
                            val iframeHtml = app.get(src, headers = mobileHeaders).text
                            scrapeHtmlForStreams(iframeHtml, src)
                        } else {
                            found = true
                        }
                    } catch (e: Exception) {}
                }
            }
        }

        return found
    }
}
