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

    // Critical: These headers mimic a real browser to bypass Cloudflare and CDN Hotlink protections
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

    // Unbreakable item parser designed to bypass duration badges and capture CDN tokens
    private fun parseVideoItems(htmlData: String): List<SearchResponse> {
        val doc = Jsoup.parse(htmlData)
        val items = mutableListOf<SearchResponse>()
        
        // Grab ANY link that points to a valid video/detail page
        val videoLinks = doc.select("a[href*=/detail?video_key=], a[href*=/avdetail], a[href*=/video/]")
        
        for (aTag in videoLinks) {
            val href = aTag.attr("href")
            val fullHref = fixUrl(href)
            if (fullHref.isBlank()) continue

            // Climb the DOM tree to find the outermost card container
            val container = aTag.closest("li") ?: aTag.closest(".video-item") ?: aTag.parent() ?: continue
            val containerHtml = container.outerHtml()

            // 1. Poster Hunt: Prioritize CDN images with ?auth_key tokens to prevent blank images
            var poster = Regex("""https?://[^\s"'<>]+\.(?:jpeg|jpg|png|webp)[^\s"'<>]*\?auth_key=[^\s"'<>]+""").find(containerHtml)?.value
            
            if (poster == null) {
                val img = container.selectFirst("img")
                poster = img?.attr("data-src").takeIf { !it.isNullOrBlank() }
                    ?: img?.attr("data-original").takeIf { !it.isNullOrBlank() }
                    ?: img?.attr("src").takeIf { !it.isNullOrBlank() }
                    ?: Regex("""url\(['"]?(https?://[^'"]+)['"]?\)""").find(containerHtml)?.groupValues?.get(1)
                    ?: ""
            }

            // 2. Title Hunt: Excludes duration badges (30:14) by targeting specific text nodes
            var title = container.selectFirst(".title, .video-title, h1, h2, h3, h4, .line-clamp-2")?.text()?.trim()
            if (title.isNullOrBlank()) title = container.selectFirst("img")?.attr("alt")?.trim()
            if (title.isNullOrBlank()) title = aTag.attr("title").trim()
            if (title.isNullOrBlank()) {
                title = container.text().replace(Regex("""\d{1,2}:\d{2}(?::\d{2})?"""), "").trim()
            }

            // Filter out obvious Casino/Gambling ads mapping to video links
            if (title.isBlank() || title.contains("棋牌") || title.contains("赌场") || title.contains("PG")) continue

            items.add(
                newMovieSearchResponse(title, fullHref, TvType.NSFW) {
                    this.posterUrl = fixUrl(poster)
                    this.posterHeaders = defaultHeaders // Fixes the gray square / 403 Forbidden image issue
                }
            )
        }
        
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
        val html = response.text.replace("\\/", "/") 

        // 1. Title Extraction
        var title = Regex("""data-video_title\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""<title>([^<]+)</title>""").find(html)?.groupValues?.get(1)?.replace("- 91porna", "")?.trim()
            ?: "No Title"

        // 2. Poster Extraction (Preserves auth_key tokens)
        var poster = Regex("""https?://[^\s"'<>]+\.(?:jpeg|jpg|png|webp)[^\s"'<>]*\?auth_key=[^\s"'<>]+""").find(html)?.value
            ?: Regex("""meta property=["']og:image["'] content=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-poster\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: ""

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrl(poster)
            this.posterHeaders = defaultHeaders // Applies the Referer header to inside pages as well
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS: Replicates the POST /index/videoEnter request
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = defaultHeaders)
        val html = response.text
        val cleanHtml = html.replace("\\/", "/").replace("&amp;", "&")
        var found = false

        // 1. DIRECT REGEX HUNT (Catches exposed data-url attributes)
        val m3u8Matches = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").findAll(cleanHtml).map { it.value }.toList()
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

        // 2. POST API FALLBACK (Executes the exact network request you found in your screenshot)
        if (!found) {
            val videoId = Regex("""video_key=([^&"']+)""").find(data)?.groupValues?.get(1)
                ?: Regex("""data-video_id=["']([^"']+)["']""").find(html)?.groupValues?.get(1)

            if (videoId != null) {
                val apiHeaders = defaultHeaders.toMutableMap()
                apiHeaders["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
                apiHeaders["X-Requested-With"] = "XMLHttpRequest"

                try {
                    val postResponse = app.post(
                        url = "$mainUrl/index/videoEnter",
                        headers = apiHeaders,
                        data = mapOf("video_id" to videoId)
                    ).text
                    
                    val postClean = postResponse.replace("\\/", "/").replace("&amp;", "&")
                    val postM3u8Matches = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").findAll(postClean).map { it.value }.toList()
                    
                    for (videoUrl in postM3u8Matches) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "API Stream",
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
        }

        // 3. MP4 / TS FALLBACK
        if (!found) {
            val mp4Matches = Regex("""https?://[^\s"'<>\\]+\.(?:mp4|ts)[^\s"'<>\\]*""").findAll(cleanHtml).map { it.value }.toList()
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

        // 4. SUBTITLE HUNT
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

    private fun fixUrl(href: String?): String {
        if (href.isNullOrBlank() || href.startsWith("blob:")) return ""
        if (href.startsWith("//")) return "https:$href"
        return if (href.startsWith("http")) href else mainUrl + href
    }
}
