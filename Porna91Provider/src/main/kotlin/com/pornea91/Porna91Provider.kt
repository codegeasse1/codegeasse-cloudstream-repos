package com.pornea91

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Porna91Provider : MainAPI() {
    override var mainUrl = "https://91porna.com"
    override var name = "91Porna"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Others)

    // No translation for this site – titles are mostly Chinese but we keep original
    private suspend fun translateToEnglish(text: String?): String? = text

    // ---------------------------------------------------------------
    // MAIN PAGE – all video sections from homepage + nav dropdowns
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/comic/index/video?category=now_month_hot" to "Hot Ranking",
        "$mainUrl/comic/index/video?category=original" to "Original",
        "$mainUrl/comic/index/video?category=play" to "Currently Playing",
        "$mainUrl/comic/index/video?category=new_update" to "Recent Updates",
        "$mainUrl/comic/index/search?keyword=乱伦" to "Incest",
        "$mainUrl/comic/index/search?keyword=熟女" to "Mature",
        "$mainUrl/comic/index/search?keyword=萝莉" to "Lolita",
        "$mainUrl/comic/index/search?keyword=动漫" to "Anime",
        "$mainUrl/comic/index/search?keyword=黑人" to "Black",
        "$mainUrl/comic/index/search?keyword=巨乳" to "Big Tits",
        "$mainUrl/comic/index/search?keyword=调教" to "BDSM",
        "$mainUrl/comic/index/search?keyword=换妻" to "Swinging",
        "$mainUrl/comic/index/search?keyword=内射" to "Creampie",
        "$mainUrl/comic/index/search?keyword=按摩" to "Massage",
        "$mainUrl/comic/index/search?keyword=吃瓜 黑料 爆料" to "Melon News",
        "$mainUrl/melonshort" to "Short Videos",
        "$mainUrl/comic/index/av" to "Japan AV",
        "$mainUrl/comic/index/search?keyword=h动漫" to "91 Anime",
        "$mainUrl/moviesets" to "Collections",
        "$mainUrl/novels" to "Novels"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val docUrl = if (page == 1) baseUrl else "$baseUrl&page=$page".replace("?page=$page", "?page=$page") // handle both cases
        val document = app.get(docUrl).document

        // Homepage: both video items and the “黑料吃瓜爆料” articles exist.
        // We only pick .video-item for movies. For articles we could create a separate type,
        // but they can’t be played – we skip them to avoid clutter.
        val items = document.select(".video-items .video-item, ul.video-items > li.video-item").mapNotNull { element ->
            element.toSearchResult()
        }
        return newHomePageResponse(request.name, items)
    }

    // ---------------------------------------------------------------
    // ITEM PARSING
    // ---------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href*=/detail?video_key=], a[href*=/avdetail?video_key=]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        val img = this.selectFirst("img")
        val title = img?.attr("alt")?.trim()
            ?: this.selectFirst(".line-clamp-2, .post-item-title")?.text()?.trim()
            ?: return null

        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.ifBlank { img.attr("src") }
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val maxPages = 5
        val results = mutableListOf<SearchResponse>()
        for (page in 1..maxPages) {
            val docUrl = if (page == 1)
                "$mainUrl/comic/index/search?keyword=$encodedQuery"
            else
                "$mainUrl/comic/index/search?keyword=$encodedQuery&page=$page"
            val document = app.get(docUrl).document
            val items = document.select(".video-items .video-item").mapNotNull { it.toSearchResult() }
            if (items.isEmpty()) break
            results.addAll(items)
        }
        return results
    }

    // ---------------------------------------------------------------
    // LOAD (Detail Page)
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: document.selectFirst("h1, h2")?.text()?.trim()
            ?: "Video"

        var posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        if (posterUrl.isNullOrBlank()) {
            posterUrl = document.selectFirst(".poster img, .video-cover img")?.attr("data-src")
                ?: document.selectFirst(".poster img, .video-cover img")?.attr("src")
        }

        val tags = document.select("a[href*=/search?keyword=]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.plot = title
            this.tags = tags
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS – try to find M3U8, then fallback to common player scripts
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val html = app.get(data).text

        // 1. Direct M3U8 links (rare but possible)
        val m3u8Regex = Regex("""https?:\\?/\\?/[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
        m3u8Regex.findAll(html).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/").replace("&amp;", "&")
            if (cleanUrl.isNotBlank()) {
                callback(newExtractorLink(name, "$name M3U8", cleanUrl, ExtractorLinkType.M3U8) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                })
                found = true
            }
        }

        // 2. Check for player_aaaa (common in Chinese AV sites)
        if (!found) {
            val playerJsonRegex = Regex("""player_aaaa\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            val match = playerJsonRegex.find(html)
            if (match != null) {
                try {
                    val jsonStr = match.groupValues[1]
                    val encrypt = Regex(""""encrypt"\s*:\s*(\d+)""").find(jsonStr)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val urlEncoded = Regex(""""url"\s*:\s*"([^"]+)"""").find(jsonStr)?.groupValues?.get(1) ?: ""
                    val realUrl = when (encrypt) {
                        1 -> android.util.Base64.decode(urlEncoded, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
                        2 -> java.net.URLDecoder.decode(android.util.Base64.decode(urlEncoded, android.util.Base64.DEFAULT).toString(Charsets.UTF_8), "UTF-8")
                        else -> urlEncoded
                    }
                    if (realUrl.contains(".m3u8")) {
                        callback(newExtractorLink(name, "$name Player", realUrl, ExtractorLinkType.M3U8) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        })
                        found = true
                    }
                } catch (_: Exception) {}
            }
        }

        // 3. Look for video URL in JavaScript variables (e.g., var video_url = '...')
        if (!found) {
            val jsUrlRegex = Regex("""(?:video_url|src)\s*[:=]\s*['"]([^'"]+)['"]""")
            jsUrlRegex.findAll(html).forEach { match ->
                val url = match.groupValues[1]
                if (url.contains(".m3u8") || url.contains(".mp4")) {
                    callback(newExtractorLink(name, "$name JS", url,
                        if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    })
                    found = true
                }
            }
        }

        return found
    }
}
