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

    // Using browser headers prevents the site from blocking the scraper and returning blank pages
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

    // Universal parser using aggressive Regex to bypass hidden DOM structures
    private fun parseVideoItems(elements: List<Element>): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        for (el in elements) {
            val html = el.outerHtml()
            
            // Extract Link
            var href = Regex("""href=["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: continue
            if (href == "#") continue
            val fullHref = fixUrl(href)
            
            // 1. Aggressive Image Extraction: Scans all possible lazy-load attributes or raw image links
            var poster = Regex("""data-(?:src|original|bg)=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""src=["']([^"']+(?:jpeg|jpg|png|webp|gif)[^"']*)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""url\(['"]?(https?://[^'"]+)['"]?\)""").find(html)?.groupValues?.get(1)
                ?: ""
                
            // 2. Aggressive Title Extraction: Grabs title/alt tags before falling back to text
            var title = Regex("""title=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""alt=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: el.selectFirst(".line-clamp-2, .video-title, h2.post-item-title")?.text()
                ?: el.text()

            // Clean up false titles like "30:14" durations
            title = title.replace(Regex("""\d{1,2}:\d{2}:\d{2}|\d{1,2}:\d{2}"""), "").trim()
            if (title.isBlank()) continue

            items.add(
                newMovieSearchResponse(title, fullHref, TvType.NSFW) {
                    this.posterUrl = fixUrl(poster)
                }
            )
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionUrl = homeSections.find { it.first == request.data }?.second ?: return newHomePageResponse(emptyList())
        val url = mainUrl + sectionUrl
        val response = app.get(url, headers = defaultHeaders)
        val doc = Jsoup.parse(response.text)

        val allItems = doc.select("ul.video-items > li, .video-list > li, article.post-item, .video-item").toList()
        val parsedItems = parseVideoItems(allItems)

        return newHomePageResponse(listOf(HomePageList(request.data, parsedItems)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/comic/index/search?keyword=$encodedQuery"
        val response = app.get(url, headers = defaultHeaders)
        val doc = Jsoup.parse(response.text)

        val videoItems = doc.select("ul.video-items > li, .video-list > li, .video-item").toList()
        return parseVideoItems(videoItems)
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = defaultHeaders)
        val html = response.text

        // 1. Title Extraction: Targets the exact custom attribute you found in the source code
        var title = Regex("""data-video_title\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Jsoup.parse(html).selectFirst("title")?.text()?.replace("- 91porna", "")?.trim() 
            ?: "No Title"

        // 2. Poster Extraction: Hunts for meta tags or any valid image link near the player
        var poster = Regex("""meta property=["']og:image["'] content=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-poster\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: ""

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrl(poster)
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS: Completely overhauled to hunt raw stream links
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = defaultHeaders)
        val html = response.text
        var found = false

        // 1. EXTRACT DIRECT M3U8 LINKS
        // Because the links are buried in 'data-url' attributes and JS blocks, we scan the entire raw HTML.
        val m3u8Matches = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").findAll(html).map { it.value }.toList()
        for (videoUrl in m3u8Matches) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "Direct M3U8",
                    url = videoUrl.replace("\\/", "/"),
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                }
            )
            found = true
        }

        // 2. EXTRACT MP4/TS LINKS (Fallback)
        if (!found) {
            val rawVideoMatches = Regex("""https?://[^\s"'<>\\]+\.(?:mp4|ts)[^\s"'<>\\]*""").findAll(html).map { it.value }.toList()
            for (videoUrl in rawVideoMatches) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Direct Stream",
                        url = videoUrl.replace("\\/", "/"),
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = "$mainUrl/"
                    }
                )
                found = true
            }
        }

        // 3. EXTRACT SUBTITLES
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
