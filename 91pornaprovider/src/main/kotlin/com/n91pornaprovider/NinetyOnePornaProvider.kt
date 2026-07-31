package com.n91pornaprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class NinetyOnePornaProvider : MainAPI() {
    override var mainUrl = "https://91porna.com"
    override var name = "91porna"
    override var lang = "zh-Hans"
    override val hasMainPage = true
    override val hasChromecastSupport = false
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.NSFW)

    // Critical Headers to bypass CDN hotlink protections
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
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
    // PARSER: Restored HTML DOM Scraping for Accurate Titles & Images
    // ---------------------------------------------------------------
    private fun parseVideoItems(htmlData: String): List<SearchResponse> {
        val doc = Jsoup.parse(htmlData)
        val items = mutableListOf<SearchResponse>()
        
        val elements = doc.select("ul.video-items > li, .video-list > li, article.post-item")
        
        for (el in elements) {
            val aTag = el.selectFirst("a[href]") ?: el.takeIf { it.tagName() == "a" } ?: continue
            val href = aTag.attr("href")
            if (href.isBlank() || href == "#") continue
            val fullHref = fixUrl(href)
            
            val img = el.selectFirst("img")
            
            // 1. Poster Hunt (Preserves auth_key tokens)
            var poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                ?: Regex("""https?://[^\s"'<>]+\.(?:jpeg|jpg|png|webp)[^\s"'<>]*\?auth_key=[^\s"'<>]+""").find(el.outerHtml())?.value
                ?: ""

            // 2. Title Hunt (Targets exact HTML layout to avoid "Video ID" fallback)
            var title = aTag.attr("title").takeIf { it.isNotBlank() }
                ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: el.selectFirst(".line-clamp-2, .video-title, h2.post-item-title, .title")?.text()?.takeIf { it.isNotBlank() }
                ?: ""

            // If empty, grab raw text and strip the duration badges (e.g. 30:14)
            if (title.isBlank()) {
                title = aTag.text().replace(Regex("""\d{1,2}:\d{2}:\d{2}|\d{1,2}:\d{2}"""), "").trim()
            }
            
            // Filter out obvious Casino/Gambling ads
            if (title.isBlank() || title.contains("棋牌") || title.contains("赌场") || title.contains("PG")) continue

            items.add(
                newMovieSearchResponse(title, fullHref, TvType.NSFW) {
                    this.posterUrl = fixUrl(poster)
                    this.posterHeaders = defaultHeaders // CRITICAL: This bypasses the CDN Gray Square block
                }
            )
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionUrl = homeSections.find { it.first == request.data }?.second ?: return newHomePageResponse(emptyList())
        val url = mainUrl + sectionUrl
        val response = app.get(url, headers = defaultHeaders)
        
        val parsedItems = parseVideoItems(response.text)
        return newHomePageResponse(listOf(HomePageList(request.data, parsedItems)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/comic/index/search?keyword=$encodedQuery"
        val response = app.get(url, headers = defaultHeaders)
        
        return parseVideoItems(response.text)
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = defaultHeaders)
        val html = response.text.replace("\\/", "/") 
        val doc = Jsoup.parse(html)

        // Title Extraction (Targets the data-video_title attribute you discovered earlier)
        var title = doc.selectFirst("#mse")?.attr("data-video_title")?.takeIf { it.isNotBlank() }
            ?: Regex("""data-video_title\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: doc.selectFirst("title")?.text()?.replace("- 91porna", "")?.trim() 
            ?: "No Title"

        // Poster Extraction
        var poster = Regex("""https?://[^\s"'<>]+\.(?:jpeg|jpg|png|webp)[^\s"'<>]*\?auth_key=[^\s"'<>]+""").find(html)?.value
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrl(poster)
            this.posterHeaders = defaultHeaders
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS: Executes the /index/videoEnter POST API Request
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // Extract the unique video ID from the URL (e.g. video_key=60158)
        val videoId = Regex("""video_key=([^&"']+)""").find(data)?.groupValues?.get(1)
        
        if (videoId != null) {
            val apiHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to data,
                "Origin" to mainUrl,
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest"
            )

            try {
                // Execute the precise POST request captured in your Network tab screenshot
                val postResponse = app.post(
                    url = "$mainUrl/index/videoEnter",
                    headers = apiHeaders,
                    data = mapOf("video_id" to videoId)
                ).text
                
                val cleanResponse = postResponse.replace("\\/", "/")
                
                // Grab the raw M3U8 link from the JSON response
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
                            this.referer = "$mainUrl/"
                        }
                    )
                    found = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Subtitle Extraction
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
