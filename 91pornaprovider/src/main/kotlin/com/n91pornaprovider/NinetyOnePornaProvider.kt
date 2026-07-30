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
        *homeSections.map { it.first }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionUrl = homeSections.find { it.first == request.data }?.second ?: return HomePageResponse(emptyList())
        val url = mainUrl + sectionUrl
        val response = app.get(url)
        val doc = Jsoup.parse(response.text)
        val items = mutableListOf<SearchResponse>()

        val videoItems = doc.select("ul.video-items > li")
        for (el in videoItems) {
            val aTag = el.selectFirst("a[href]") ?: continue
            val href = aTag.attr("href")
            val fullHref = fixUrl(href)
            val poster = aTag.selectFirst("img")?.attr("data-src") ?: aTag.selectFirst("img")?.attr("src") ?: ""
            val title = aTag.selectFirst("div.line-clamp-2")?.text() ?: aTag.selectFirst("div")?.text() ?: ""
            
            items.add(
                newMovieSearchResponse(title, fullHref, TvType.NSFW) {
                    this.posterUrl = poster
                }
            )
        }

        val articleItems = doc.select("ul > li > a > article.post-item")
        for (el in articleItems) {
            val aTag = el.parent() as? Element ?: continue
            val href = aTag.attr("href")
            val fullHref = fixUrl(href)
            val poster = el.selectFirst("div.post-item-poster")?.attr("data-src") ?: ""
            val title = el.selectFirst("h2.post-item-title")?.text() ?: ""
            
            items.add(
                newMovieSearchResponse(title, fullHref, TvType.NSFW) {
                    this.posterUrl = poster
                }
            )
        }

        return HomePageResponse(listOf(HomePageList(request.data, items)), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/comic/index/search?keyword=$encodedQuery"
        val response = app.get(url)
        val doc = Jsoup.parse(response.text)
        val results = mutableListOf<SearchResponse>()

        val videoItems = doc.select("ul.video-items > li")
        for (el in videoItems) {
            val aTag = el.selectFirst("a[href]") ?: continue
            val href = aTag.attr("href")
            val fullHref = fixUrl(href)
            val poster = aTag.selectFirst("img")?.attr("data-src") ?: aTag.selectFirst("img")?.attr("src") ?: ""
            val title = aTag.selectFirst("div.line-clamp-2")?.text() ?: aTag.selectFirst("div")?.text() ?: ""

            results.add(
                newMovieSearchResponse(title, fullHref, TvType.NSFW) {
                    this.posterUrl = poster
                }
            )
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val doc = Jsoup.parse(response.text)

        val title = doc.selectFirst("title")?.text() ?: doc.selectFirst("h2")?.text() ?: "No Title"
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("img.poster")?.attr("src")
            ?: doc.selectFirst("img")?.attr("data-src")
            ?: doc.selectFirst("img")?.attr("src") ?: ""

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
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
        val videoUrl = extractVideoUrl(doc)

        if (!videoUrl.isNullOrEmpty()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }
        return false
    }

    private fun fixUrl(href: String): String {
        return if (href.startsWith("http")) href else mainUrl + href
    }

    private fun extractVideoUrl(doc: Document): String? {
        val html = doc.html()
        val regexPatterns = listOf(
            Regex("""file\s*:\s*["'](https?://[^"']+)"""),
            Regex("""source\s+src\s*=\s*["'](https?://[^"']+)"""),
            Regex("""player\.source\(\s*["'](https?://[^"']+)"""),
            Regex("""(?:video|source)["']\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)["'])""")
        )
        for (pattern in regexPatterns) {
            val match = pattern.find(html)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        val scripts = doc.select("script")
        for (script in scripts) {
            val content = script.html()
            if (content.contains("file:") || content.contains("url:")) {
                val jsonMatch = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4))["']""").find(content)
                if (jsonMatch != null) return jsonMatch.groupValues[1]
            }
        }
        return null
    }
}
