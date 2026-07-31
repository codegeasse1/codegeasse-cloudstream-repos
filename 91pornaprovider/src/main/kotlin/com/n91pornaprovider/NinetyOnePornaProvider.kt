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

    // Mimic a real browser to bypass Cloudflare and API blocks
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept-Language" to "en-US,en;q=0.9"
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

    // Universal aggressive extraction to bypass the broken HTML layout
    private fun parseVideoItems(htmlData: String): List<SearchResponse> {
        val doc = Jsoup.parse(htmlData)
        val items = mutableListOf<SearchResponse>()
        
        // Grab ANY link that points to a video player
        val videoLinks = doc.select("a[href*=/detail?video_key=], a[href*=/avdetail], a[href*=/video/]")
        
        for (aTag in videoLinks) {
            val href = aTag.attr("href")
            val fullHref = fixUrl(href)
            if (fullHref.isBlank()) continue

            // Climb the DOM tree slightly to capture the container holding the image and title
            val container = aTag.parent()?.parent() ?: aTag
            val containerHtml = container.outerHtml()

            // 1. Aggressive Image Hunt (Preserves the crucial ?auth_key= tokens)
            var poster = container.selectFirst("img")?.let { img ->
                img.attr("data-src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-original").takeIf { it.isNotBlank() }
                    ?: img.attr("src").takeIf { it.isNotBlank() }
            } ?: Regex("""https?://[^"'\s<>]+\.(?:jpeg|jpg|png|webp|gif)[^"'\s<>]*""").find(containerHtml)?.value ?: ""

            // 2. Aggressive Title Hunt (Bypasses the "30:14" text bug)
            var title = aTag.attr("title").takeIf { it.isNotBlank() }
                ?: container.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: container.selectFirst(".title, h1, h2, h3, h4, .video-title, .line-clamp-2")?.text()?.takeIf { it.isNotBlank() }
                ?: container.text()

            // Obliterate video durations (e.g., 30:14, 1:20:05) from the title
            title = title.replace(Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\b"""), "").trim()
            
            // Filter out obvious Casino/Gambling ads (PG棋牌)
            if (title.isBlank() || title.contains("棋牌") || title.contains("赌场") || title.contains("PG")) continue

            items.add(
                newMovieSearchResponse(title, fullHref, TvType.NSFW) {
                    this.posterUrl = fixUrl(poster)
                }
            )
        }
        
        // Remove duplicates in case the image and title both had the same link
        return items.distinctBy { it.url }
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
        val html = response.text.replace("\\/", "/") // Unescape JavaScript immediately

        // 1. Unbreakable Title Extraction
        var title = Regex("""data-video_title\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""<title>([^<]+)</title>""").find(html)?.groupValues?.get(1)?.replace("- 91porna", "")?.trim()
            ?: "No Title"

        // 2. Unbreakable Poster Extraction (Preserves auth_key)
        var poster = Regex("""meta property=["']og:image["'] content=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-poster\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""https?://[^"'\s<>]+\.(?:jpeg|jpg|png|webp)[^"'\s<>]*""").find(html)?.value
            ?: ""

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrl(poster)
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS: Hunts the entire document for encrypted .m3u8 links
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = defaultHeaders)
        // Aggressively unescape all JSON and Javascript formatting to reveal hidden links
        val html = response.text.replace("\\/", "/") 
        var found = false

        // 1. Hunt directly for ANY .m3u8 link (including those with ?auth_key tokens)
        val m3u8Matches = Regex("""https?://[^"'\s\[\]\{\}<>]+\.m3u8[^"'\s\[\]\{\}<>]*""").findAll(html).map { it.value }.toList()
        for (videoUrl in m3u8Matches) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "M3U8 Stream",
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                }
            )
            found = true
        }

        // 2. Fallback Hunt for raw .mp4 or .ts streams
        if (!found) {
            val mp4Matches = Regex("""https?://[^"'\s\[\]\{\}<>]+\.(?:mp4|ts)[^"'\s\[\]\{\}<>]*""").findAll(html).map { it.value }.toList()
            for (videoUrl in mp4Matches) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Direct Stream",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = "$mainUrl/"
                    }
                )
                found = true
            }
        }

        // 3. Subtitle Hunt
        val doc = Jsoup.parse(html)
        doc.select("track[kind=subtitles]").forEach { track ->
            val subUrl = track.attr("src")
            val subLabel = track.attr("label").ifBlank { "Subtitle" }
            if (subUrl.isNotBlank() && !subUrl.startsWith("blob:") && subUrl.startsWith("http")) {
                subtitleCallback(SubtitleFile(subLabel, subUrl))
            }
        }

        return found
    }

    private fun fixUrl(href: String): String {
        if (href.isBlank() || href.startsWith("blob:")) return ""
        if (href.startsWith("//")) return "https:$href"
        return if (href.startsWith("http")) href else mainUrl + href
    }
}
