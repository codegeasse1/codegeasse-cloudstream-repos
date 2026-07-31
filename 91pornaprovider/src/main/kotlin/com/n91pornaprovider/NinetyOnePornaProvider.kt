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

    // Force strict browser headers to bypass CDN blocks for images and video streams
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private val homeSections = listOf(
        "黑料吃瓜" to "/黑料吃瓜/推荐",
        "推荐给您" to "/comic/index/video?category=now_month_hot",
        "正在播放" to "/comic/index/video?category=play",
        "最近更新" to "/comic/index/video?category=new_update",
        "乱伦" to "/comic/index/search?keyword=乱伦",
        "熟女" to "/comic/index/search?keyword=熟女",
        "萝莉" to "/comic/index/search?keyword=萝莉",
        "动漫" to "/comic/index/search?keyword=动漫",
        "黑人" to "/comic/index/search?keyword=黑人",
        "巨乳" to "/comic/index/search?keyword=巨乳",
        "调教" to "/comic/index/search?keyword=调教",
        "换妻" to "/comic/index/search?keyword=换妻",
        "内射" to "/comic/index/search?keyword=内射",
        "按摩" to "/comic/index/search?keyword=按摩",
        "吃瓜黑料" to "/comic/index/search?keyword=吃瓜+黑料+爆料+明星+网红"
    )

    override val mainPage = mainPageOf(
        *homeSections.toTypedArray()
    )

    // ---------------------------------------------------------------
    // PARSER: Smart HTML Scraping 
    // ---------------------------------------------------------------
    private fun parseVideoItems(htmlData: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        val doc = Jsoup.parse(htmlData)
        
        doc.select("ul.video-items > li, .video-list > li, article.post-item").forEach { card ->
            val aTag = card.selectFirst("a[href*=/detail?video_key=], a[href*=/video/]") ?: return@forEach
            val url = fixUrl(aTag.attr("href"))
            if (url.isBlank()) return@forEach

            val img = card.selectFirst("img")

            // 1. Title Hunt: If attributes fail, grab raw text and strip out time/duration badges
            var title = aTag.attr("title").ifBlank { null }
                ?: img?.attr("alt")?.ifBlank { null }
                ?: card.selectFirst(".title, .video-title, h1, h2, h3, .line-clamp-2")?.text()?.ifBlank { null }
                ?: card.text()

            // Remove timestamps like "30:14" or "01:20:05" so we only keep the real title
            title = title.replace(Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\b"""), "").trim()
            if (title.isBlank() || title.contains("棋牌") || title.contains("赌场")) return@forEach

            // 2. Poster Hunt: Grabs the data-src to ensure we capture the CDN auth_key
            val poster = img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("data-original")?.ifBlank { null }
                ?: img?.attr("src")?.ifBlank { null }
                ?: Regex("""https?://[^\s"'<>]+\.(?:jpeg|jpg|png|webp)[^\s"'<>]*\?auth_key=[^\s"'<>]+""").find(card.outerHtml())?.value
                ?: ""

            items.add(
                newMovieSearchResponse(title, url, TvType.NSFW) {
                    this.posterUrl = fixUrl(poster)
                    this.posterHeaders = defaultHeaders // Forces CloudStream to pass Referer to unblock images
                }
            )
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
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/comic/index/search?keyword=$encodedQuery"
        val response = app.get(url, headers = defaultHeaders).text
        return parseVideoItems(response)
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = defaultHeaders).text
        val html = response.replace("\\/", "/") 
        val doc = Jsoup.parse(html)

        // Exact attribute you extracted earlier
        val title = doc.selectFirst("#mse")?.attr("data-video_title")?.ifBlank { null }
            ?: Regex("""data-video_title\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: doc.selectFirst("title")?.text()?.replace("- 91porna", "")?.trim() 
            ?: "No Title"

        val poster = Regex("""https?://[^\s"'<>]+\.(?:jpeg|jpg|png|webp)[^\s"'<>]*\?auth_key=[^\s"'<>]+""").find(html)?.value
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrl(poster)
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
        val videoId = Regex("""video_key=([^&"']+)""").find(data)?.groupValues?.get(1)
        
        if (videoId != null) {
            val apiHeaders = defaultHeaders.toMutableMap()
            apiHeaders["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
            apiHeaders["X-Requested-With"] = "XMLHttpRequest"

            try {
                // Execute the precise POST request captured in your Network tab
                val postResponse = app.post(
                    url = "$mainUrl/index/videoEnter",
                    headers = apiHeaders,
                    data = mapOf("video_id" to videoId)
                ).text
                
                val cleanResponse = postResponse.replace("\\/", "/")
                val m3u8Matches = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").findAll(cleanResponse).map { it.value }.toList()
                
                for (videoUrl in m3u8Matches) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Direct Stream",
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.Unknown.value
                            // Inject headers into the map to ensure CloudStream's player actually passes them
                            this.headers = defaultHeaders
                        }
                    )
                    found = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback: Scan raw HTML for direct streams if the API call fails
        if (!found) {
            val html = app.get(data, headers = defaultHeaders).text.replace("\\/", "/")
            val backupStreams = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4|ts)[^\s"'<>\\]*""").findAll(html).map { it.value }.toList()
            
            for (videoUrl in backupStreams) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Direct Stream",
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = defaultHeaders
                    }
                )
                found = true
            }
        }

        // Subtitles
        val doc = Jsoup.parse(app.get(data, headers = defaultHeaders).text)
        doc.select("track[kind=subtitles]").forEach { track ->
            val subUrl = track.attr("src")
            val subLabel = track.attr("label").ifBlank { "Subtitle" }
            if (subUrl.isNotBlank() && !subUrl.startsWith("blob:") && subUrl.startsWith("http")) {
                subtitleCallback(SubtitleFile(subLabel, subUrl))
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
