package com.pornea91

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class Porna91Provider : MainAPI() {
    override var mainUrl = "https://91porna.com"
    override var name = "91Porna"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Others)

    // Standard browser headers – used for every request
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    // ---------------------------------------------------------------
    // MAIN PAGE – all homepage video sections
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
        val docUrl = if (page == 1) baseUrl else "$baseUrl&page=$page"
        val document = app.get(docUrl, headers = headers).document
        val items = document.select(".video-items .video-item, ul.video-items > li.video-item")
            .mapNotNull { element -> element.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ---------------------------------------------------------------
    // ITEM PARSING – fetch image manually to bypass CDN blocking
    // ---------------------------------------------------------------
    private suspend fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href*=/detail?video_key=], a[href*=/avdetail?video_key=]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        val img = this.selectFirst("img")
        val title = img?.attr("alt")?.trim()
            ?: this.selectFirst(".line-clamp-2, .post-item-title")?.text()?.trim()
            ?: return null

        // Fetch the image and convert to base64 data URI
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.ifBlank { img.attr("src") }
        )
        val dataUri = posterUrl?.let { fetchImageAsDataUri(it) } ?: posterUrl

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = dataUri
        }
    }

    // ---------------------------------------------------------------
    // SEARCH – same image fetching
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val maxPages = 5
        val results = mutableListOf<SearchResponse>()
        for (page in 1..maxPages) {
            val docUrl = if (page == 1) "$mainUrl/comic/index/search?keyword=$encodedQuery"
                         else "$mainUrl/comic/index/search?keyword=$encodedQuery&page=$page"
            val document = app.get(docUrl, headers = headers).document
            val items = document.select(".video-items .video-item")
                .mapNotNull { element -> element.toSearchResult() }
            if (items.isEmpty()) break
            results.addAll(items)
        }
        return results
    }

    // ---------------------------------------------------------------
    // LOAD (Detail Page)
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: document.selectFirst("h1, h2")?.text()?.trim() ?: "Video"

        var posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        if (posterUrl.isNullOrBlank()) {
            posterUrl = document.selectFirst(".poster img, .video-cover img")?.attr("data-src")
                ?: document.selectFirst(".poster img, .video-cover img")?.attr("src")
        }
        val dataUri = posterUrl?.let { fetchImageAsDataUri(it) } ?: posterUrl

        val tags = document.select("a[href*=/search?keyword=]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = dataUri
            this.plot = title
            this.tags = tags
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS – extract M3U8 with browser headers
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val html = app.get(data, headers = headers).text

        // 1. Direct M3U8 anywhere in the page
        val m3u8Regex = Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
        m3u8Regex.findAll(html).forEach { match ->
            val url = match.value.replace("&amp;", "&")
            callback(newExtractorLink(name, "$name M3U8", url, ExtractorLinkType.M3U8) {
                this.referer = data
                this.quality = Qualities.Unknown.value
            })
            found = true
        }

        // 2. player_aaaa JSON (common fallback)
        if (!found) {
            val playerNames = listOf("player_aaaa", "player_data", "player_info", "player", "videoConfig")
            for (pName in playerNames) {
                val match = Regex("""$pName\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html)
                if (match != null) {
                    try {
                        val json = match.groupValues[1]
                        val encrypt = Regex(""""encrypt"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val urlEncoded = Regex(""""url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""
                        val realUrl = when (encrypt) {
                            1 -> String(Base64.decode(urlEncoded, Base64.DEFAULT), Charsets.UTF_8)
                            2 -> URLDecoder.decode(String(Base64.decode(urlEncoded, Base64.DEFAULT), Charsets.UTF_8), "UTF-8")
                            else -> urlEncoded
                        }
                        if (realUrl.contains(".m3u8") || realUrl.contains(".mp4")) {
                            callback(newExtractorLink(name, "Player", realUrl,
                                if (realUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            })
                            found = true
                            break
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 3. Fallback API – request stream via video_key
        if (!found && data.contains("video_key=")) {
            val videoKey = data.substringAfter("video_key=").substringBefore("&")
            try {
                val apiUrl = "$mainUrl/api/play?video_key=$videoKey"
                val json = app.get(apiUrl, headers = headers).text
                val urlMatch = Regex(""""url"\s*:\s*"([^"]+)"""").find(json)
                if (urlMatch != null) {
                    val streamUrl = urlMatch.groupValues[1]
                    if (streamUrl.contains(".m3u8")) {
                        callback(newExtractorLink(name, "API", streamUrl, ExtractorLinkType.M3U8) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        })
                        found = true
                    }
                }
            } catch (_: Exception) {}
        }

        return found
    }

    // ---------------------------------------------------------------
    // Helper: fetch image and return base64 data URI
    // ---------------------------------------------------------------
    private suspend fun fetchImageAsDataUri(imageUrl: String): String {
        return try {
            val response = app.get(imageUrl, headers = headers)
            val bytes = response.okhttpResponse.body?.bytes() ?: return imageUrl
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val ext = imageUrl.substringAfterLast(".", "jpeg").substringBefore("?")
            "data:image/$ext;base64,$base64"
        } catch (e: Exception) {
            imageUrl  // fallback to original URL if fetch fails
        }
    }
}
