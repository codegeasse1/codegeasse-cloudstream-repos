package com.n91pornaprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder

class NinetyOnePornaProvider : MainAPI() {
    override var mainUrl = "https://91porna.com"
    override var name = "91porna"
    override var lang = "zh-Hans"
    override val hasMainPage = true
    override val hasChromecastSupport = false
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.NSFW)

    // Critical Headers to emulate a mobile browser handling API requests
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With" to "XMLHttpRequest"
    )

    private val homeSections = listOf(
        "黑料吃瓜" to "/黑料吃瓜/推荐",
        "推荐给您" to "/comic/index/video?category=now_month_hot",
        "正在播放" to "/comic/index/video?category=play",
        "最近更新" to "/comic/index/video?category=new_update",
        "乱伦" to "/comic/index/search?keyword=乱伦"
    )

    override val mainPage = mainPageOf(
        *homeSections.toTypedArray()
    )

    // ---------------------------------------------------------------
    // PARSER: Uses ultra-aggressive Regex to bypass CryptoJS DOM rendering
    // ---------------------------------------------------------------
    private fun parseVideoItems(htmlData: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        // Unescape JSON/Unicode that CryptoJS relies on
        val decodedHtml = htmlData.replace("\\/", "/").replace("\\\"", "\"")

        // 1. Hunt for any raw URL containing "video_key=" (which you found in your extraction)
        val videoKeys = Regex("""video_key=([a-zA-Z0-9_]+)""").findAll(decodedHtml).map { it.groupValues[1] }.toSet()

        for (key in videoKeys) {
            val url = "$mainUrl/comic/index/detail?video_key=$key"
            
            // 2. Hunt for the image with the ?auth_key token associated with this block
            // Since DOM is broken, we scan the raw text for the CDN links
            val posterMatch = Regex("""https?://[^\s"'<>]+\.(?:jpeg|jpg|png|webp)[^\s"'<>]*\?auth_key=[^\s"'<>]+""").find(decodedHtml)?.value ?: ""
            
            // 3. Fallback Title Extraction
            val titleMatch = Regex("""["']title["']\s*:\s*["']([^"']+)["']""").find(decodedHtml)?.groupValues?.get(1) ?: "Video $key"

            items.add(
                newMovieSearchResponse(titleMatch, url, TvType.NSFW) {
                    this.posterUrl = posterMatch
                    this.posterHeaders = defaultHeaders
                }
            )
        }

        // Fallback: If the JSON/CryptoJS blocks everything, scrape raw a-tags
        if (items.isEmpty()) {
            val doc = Jsoup.parse(htmlData)
            doc.select("a[href*=/detail?video_key=], a[href*=/video/]").forEach { aTag ->
                val href = aTag.attr("href")
                if (href.isBlank() || href == "#") return@forEach
                
                val title = aTag.attr("title").ifBlank { aTag.text() }.replace(Regex("""\d{1,2}:\d{2}(?::\d{2})?"""), "").trim()
                if (title.isBlank() || title.contains("棋牌") || title.contains("赌场")) return@forEach
                
                val poster = Regex("""https?://[^\s"'<>]+\.(?:jpeg|jpg|png|webp)[^\s"'<>]*\?auth_key=[^\s"'<>]+""").find(aTag.parent()?.outerHtml() ?: "")?.value ?: ""

                items.add(
                    newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
                        this.posterUrl = poster
                        this.posterHeaders = defaultHeaders
                    }
                )
            }
        }
        return items.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionUrl = homeSections.find { it.first == request.data }?.second ?: return newHomePageResponse(emptyList())
        val url = mainUrl + sectionUrl
        val response = app.get(url, headers = defaultHeaders).text
        return newHomePageResponse(listOf(HomePageList(request.data, parseVideoItems(response))))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/comic/index/search?keyword=${URLEncoder.encode(query, "UTF-8")}"
        val response = app.get(url, headers = defaultHeaders).text
        return parseVideoItems(response)
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = defaultHeaders).text
        val html = response.replace("\\/", "/")

        val title = Regex("""<title>([^<]+)</title>""").find(html)?.groupValues?.get(1)?.replace("- 91porna", "")?.trim() ?: "No Title"
        val poster = Regex("""https?://[^\s"'<>]+\.(?:jpeg|jpg|png|webp)[^\s"'<>]*\?auth_key=[^\s"'<>]+""").find(html)?.value ?: ""

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = defaultHeaders
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS: Executes the /index/videoEnter POST Request
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // 1. We must execute the exact POST request you found in the Network Tab
        val videoKey = Regex("""video_key=([^&"']+)""").find(data)?.groupValues?.get(1)
        
        if (videoKey != null) {
            try {
                // Hitting the exact API endpoint you discovered: https://91porna.com/index/videoEnter
                val postHeaders = defaultHeaders.toMutableMap()
                postHeaders["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"

                val postResponse = app.post(
                    url = "$mainUrl/index/videoEnter",
                    headers = postHeaders,
                    data = mapOf("video_id" to videoKey)
                ).text
                
                val cleanResponse = postResponse.replace("\\/", "/")
                
                // Hunt the API response for the ?auth_key authenticated .m3u8 stream
                val m3u8Matches = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*\?auth_key=[^\s"'<>\\]+""").findAll(cleanResponse).map { it.value }.toList()
                
                for (videoUrl in m3u8Matches) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "API Stream (Auth)",
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = "$mainUrl/"
                        }
                    )
                    found = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Fallback: Scan the raw page for surviving unencrypted .m3u8 or .ts links
        if (!found) {
            val html = app.get(data, headers = defaultHeaders).text.replace("\\/", "/")
            val backupStreams = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|ts|mp4)[^\s"'<>\\]*""").findAll(html).map { it.value }.toList()
            
            for (videoUrl in backupStreams) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Direct Stream",
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = "$mainUrl/"
                    }
                )
                found = true
            }
        }

        return found
    }

    private fun fixUrl(href: String?): String {
        if (href.isNullOrBlank() || href.startsWith("blob:")) return ""
        if (href.startsWith("//")) return "https:$href"
        return if (href.startsWith("http")) href else mainUrl + href
    }
}
