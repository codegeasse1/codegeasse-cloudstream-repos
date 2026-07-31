package com.n91pornaprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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

    // Helper to extract clean titles and posters from standard item cards
    private fun parseVideoItems(elements: List<Element>): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        for (el in elements) {
            val aTag = el.selectFirst("a[href]") ?: el.takeIf { it.tagName() == "a" } ?: continue
            val href = aTag.attr("href")
            if (href.isBlank() || href == "#") continue
            val fullHref = fixUrl(href)
            
            val img = el.selectFirst("img")
            
            // 1. Aggressive Poster Extraction
            var poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                ?: Regex("""https?://[^"'\s]+\.(?:jpeg|jpg|png|webp)[^"'\s]*""").find(el.outerHtml())?.value 
                ?: ""

            // 2. Aggressive Title Extraction (Fixes "30:14" duration bug)
            var title = aTag.attr("title").takeIf { it.isNotBlank() }
                ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: el.selectFirst(".line-clamp-2, .video-title, h2.post-item-title")?.text()?.takeIf { it.isNotBlank() }
                ?: ""

            // Fallback if title is still empty: get text but strip out duration badges
            if (title.isBlank()) {
                title = aTag.text().replace(Regex("""\d{1,2}:\d{2}:\d{2}|\d{1,2}:\d{2}"""), "").trim()
            }
            if (title.isBlank()) continue

            items.add(
                newMovieSearchResponse(title, fullHref, TvType.NSFW) {
                    this.posterUrl = if (poster.startsWith("http")) poster else "$mainUrl$poster"
                }
            )
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionUrl = homeSections.find { it.first == request.data }?.second ?: return newHomePageResponse(emptyList())
        val url = mainUrl + sectionUrl
        val response = app.get(url)
        val doc = Jsoup.parse(response.text)

        // Parse both regular videos and article styles using the unified helper
        val allItems = doc.select("ul.video-items > li, .video-list > li, article.post-item").toList()
        val parsedItems = parseVideoItems(allItems)

        return newHomePageResponse(listOf(HomePageList(request.data, parsedItems)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/comic/index/search?keyword=$encodedQuery"
        val response = app.get(url)
        val doc = Jsoup.parse(response.text)

        val videoItems = doc.select("ul.video-items > li, .video-list > li").toList()
        return parseVideoItems(videoItems)
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val doc = Jsoup.parse(response.text)
        val html = doc.html()

        // 1. Title Extraction (Uses the exact data-video_title attribute)
        var title = doc.selectFirst("#mse")?.attr("data-video_title")?.takeIf { it.isNotBlank() }
            ?: Regex("""data-video_title\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: doc.selectFirst("title")?.text()?.replace("- 91porna", "")?.trim() 
            ?: "No Title"

        // 2. Poster Extraction
        var poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("video")?.attr("poster")?.takeIf { it.isNotBlank() }
            ?: ""

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = if (poster.startsWith("http")) poster else "$mainUrl$poster"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val doc = Jsoup.parse(response.text)
        val html = doc.html()
        var found = false

        // Extract Video URL (Specifically targets the custom data-url attribute)
        val videoUrl = doc.selectFirst("div[data-url]")?.attr("data-url")?.takeIf { it.isNotBlank() }
            ?: Regex("""data-url\s*=\s*["'](https?://[^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""source\s+src\s*=\s*["'](https?://[^"']+)["']""").find(html)?.groupValues?.get(1)

        if (!videoUrl.isNullOrEmpty()) {
            val isM3u8 = videoUrl.contains(".m3u8") || videoUrl.contains(".ts")
            
            callback(
                newExtractorLink(
                    source = "91porna",
                    name = "Direct Stream",
                    url = videoUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/" // FIXED: Moved inside the lambda block
                }
            )
            found = true
        }

        // Subtitle Extraction
        doc.select("track[kind=subtitles]").forEach { track ->
            val subUrl = track.attr("src")
            val subLabel = track.attr("label").ifBlank { "Subtitle" }
            if (subUrl.isNotBlank() && subUrl.startsWith("http")) {
                subtitleCallback(SubtitleFile(subLabel, subUrl))
            }
        }

        return found
    }

    private fun fixUrl(href: String): String {
        return if (href.startsWith("http")) href else mainUrl + href
    }
}
