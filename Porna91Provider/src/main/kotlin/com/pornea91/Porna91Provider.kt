package com.pornea91

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    private var sessionInitialized = false
    private suspend fun initSession() {
        if (!sessionInitialized) {
            app.get(mainUrl, headers = headers).text
            sessionInitialized = true
        }
    }

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
        initSession()
        val baseUrl = request.data
        val docUrl = if (page == 1) baseUrl else "$baseUrl&page=$page"
        val document = app.get(docUrl, headers = headers).document
        val items = document.select(".video-items .video-item, ul.video-items > li.video-item")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href*=/detail], a[href*=/avdetail], a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        
        val img = this.selectFirst("img")
        val bgImg = this.attr("style")?.let { Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1) }
        
        val title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".title, .line-clamp-2, .post-item-title, h3, h4")?.text()?.trim()
            ?: return null
            
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.ifBlank { 
                img.attr("data-original")?.ifBlank { 
                    img.attr("src") 
                } 
            } ?: bgImg
        )
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        initSession()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val maxPages = 5
        val results = mutableListOf<SearchResponse>()
        for (page in 1..maxPages) {
            val docUrl = if (page == 1) "$mainUrl/comic/index/search?keyword=$encodedQuery"
                         else "$mainUrl/comic/index/search?keyword=$encodedQuery&page=$page"
            val document = app.get(docUrl, headers = headers).document
            val items = document.select(".video-items .video-item")
                .mapNotNull { it.toSearchResult() }
            if (items.isEmpty()) break
            results.addAll(items)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        initSession()
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: document.selectFirst("h1, h2")?.text()?.trim() ?: "Video"
            
        var posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        if (posterUrl.isNullOrBlank()) {
            posterUrl = document.selectFirst(".poster img, .video-cover img, video, .video-item img")?.attr("data-src")
                ?: document.selectFirst(".poster img, .video-cover img, video, .video-item img")?.attr("data-original")
                ?: document.selectFirst(".poster img, .video-cover img, video, .video-item img")?.attr("src")
        }
        
        val tags = document.select("a[href*=/search?keyword=]").map { it.text().trim() }.filter { it.isNotBlank() }
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = title
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        initSession()
        var found = false
        val mainHtml = app.get(data, headers = headers).text

        suspend fun searchForStream(html: String, referer: String) {
            // 1. Direct regex for m3u8/mp4
            val directRegex = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:[^\s"'<>\\]*)?""")
            for (match in directRegex.findAll(html)) {
                val url = match.value.replace("&amp;", "&").replace("\\/", "/")
                if (url.contains("poster") || url.contains("thumb") || url.contains("preview") || url.contains("cover")) continue
                val isM3u8 = url.contains(".m3u8")
                callback(newExtractorLink(name, "$name Direct", url, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                })
                found = true
            }
            if (found) return

            // 2. Player JS objects
            val playerNames = listOf("player_aaaa", "player_data", "player_info", "player", "videoConfig",
                "config", "playInfo", "playerConfig", "videoInfo", "dplayer", "artplayer", "MacPlayer")
            for (pName in playerNames) {
                val match = Regex("""$pName\s*=\s*(\{.*?\})\s*;?""", RegexOption.DOT_MATCHES_ALL).find(html)
                    ?: Regex("""var\s+$pName\s*=\s*(\{.*?\})\s*;?""", RegexOption.DOT_MATCHES_ALL).find(html)
                if (match != null) {
                    try {
                        val json = match.groupValues[1]
                        val encrypt = Regex(""""encrypt"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val urlEncoded = Regex(""""(?:url|url_next|link|play_url)"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""
                        
                        if (urlEncoded.isNotBlank()) {
                            val realUrl = when (encrypt) {
                                1 -> String(Base64.decode(urlEncoded, Base64.DEFAULT), Charsets.UTF_8)
                                2 -> URLDecoder.decode(String(Base64.decode(urlEncoded, Base64.DEFAULT), Charsets.UTF_8), "UTF-8")
                                else -> URLDecoder.decode(urlEncoded, "UTF-8")
                            }.replace("\\/", "/")
                            
                            if (realUrl.contains(".m3u8") || realUrl.contains(".mp4")) {
                                callback(newExtractorLink(name, "$name Player", realUrl,
                                    if (realUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = referer
                                    this.quality = Qualities.Unknown.value
                                })
                                found = true
                                return
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            
            // 3. <video> or <source> tags
            val doc = Jsoup.parse(html)
            val videoTag = doc.selectFirst("video source, video[src], source[src]")
            if (videoTag != null) {
                val src = videoTag.attr("src").ifBlank { videoTag.attr("data-src") }.replace("\\/", "/")
                if (src.isNotBlank() && (src.contains(".m3u8") || src.contains(".mp4"))) {
                    val fullUrl = if (src.startsWith("http")) src else fixUrl(src)
                    callback(newExtractorLink(name, "$name VideoTag", fullUrl,
                        if (fullUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                    })
                    found = true
                }
            }
        }

        searchForStream(mainHtml, data)

        if (!found) {
            val document = app.get(data, headers = headers).document
            val iframes = document.select("iframe")
            for (iframe in iframes) {
                var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.startsWith("//")) src = "https:$src"
                if (src.isNotBlank() && src.startsWith("http")) {
                    try {
                        val iframeHtml = app.get(src, headers = headers + ("Referer" to data)).text
                        searchForStream(iframeHtml, src)
                        if (found) break
                    } catch (_: Exception) {}
                }
            }
        }

        if (!found && data.contains("video_key=")) {
            val videoKey = data.substringAfter("video_key=").substringBefore("&")
            val apiUrls = listOf(
                "$mainUrl/api/play?video_key=$videoKey",
                "$mainUrl/comic/play?video_key=$videoKey",
                "$mainUrl/api/video?key=$videoKey",
                "$mainUrl/api/getVideo?key=$videoKey",
                "$mainUrl/index/play?video_key=$videoKey"
            )
            for (api in apiUrls) {
                try {
                    val json = app.get(api, headers = headers).text
                    val urlMatch = Regex(""""(?:url|play_url|video_url|src)"\s*:\s*"([^"]+)"""").find(json)
                    if (urlMatch != null) {
                        val streamUrl = urlMatch.groupValues[1].replace("\\/", "/")
                        if (streamUrl.contains(".m3u8") || streamUrl.contains(".mp4")) {
                            callback(newExtractorLink(name, "$name API", streamUrl, 
                                if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            })
                            found = true
                            break
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return found
    }
}