package com.pimpbunny

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class PimpBunnyProvider : MainAPI() {
    override var mainUrl = "https://pimpbunny.com"
    override var name = "PimpBunny"
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

    // ----- Added actual category links here so they appear as lists in CloudStream -----
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/categories/4k/" to "4K",
        "$mainUrl/categories/amateur/" to "Amateur",
        "$mainUrl/categories/anal/" to "Anal",
        "$mainUrl/categories/asian/" to "Asian",
        "$mainUrl/categories/bbw/" to "BBW",
        "$mainUrl/categories/big-ass/" to "Big Ass",
        "$mainUrl/categories/big-tits/" to "Big Tits",
        "$mainUrl/categories/blonde/" to "Blonde",
        "$mainUrl/categories/blowjob/" to "Blowjob",
        "$mainUrl/categories/brunette/" to "Brunette",
        "$mainUrl/categories/creampie/" to "Creampie",
        "$mainUrl/categories/cumshot/" to "Cumshot",
        "$mainUrl/categories/ebony/" to "Ebony",
        "$mainUrl/categories/hardcore/" to "Hardcore",
        "$mainUrl/categories/latina/" to "Latina",
        "$mainUrl/categories/lesbian/" to "Lesbian",
        "$mainUrl/categories/milf/" to "MILF",
        "$mainUrl/categories/pov/" to "POV",
        "$mainUrl/categories/teen/" to "Teen",
        "$mainUrl/categories/threesome/" to "Threesome"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        initSession()

        // ----- Home / category video listing -----
        val docUrl = if (page == 1) request.data else "${request.data}${page}/"
        val document = app.get(docUrl, headers = headers).document
        val items = document.select("div.b6m-video").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href*=/videos/]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val img = this.selectFirst("img")
        val title = img?.attr("alt")?.trim()
            ?: this.selectFirst(".ui-card-title, .text-truncate")?.text()?.trim()
            ?: return null
        val posterUrl = fixUrlNull(
            img?.attr("data-original")?.ifBlank { img.attr("data-src")?.ifBlank { img.attr("src") } }
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
            val docUrl = if (page == 1) "$mainUrl/search/$encodedQuery/"
                         else "$mainUrl/search/$encodedQuery/$page/"
            val document = app.get(docUrl, headers = headers).document
            val items = document.select("div.b6m-video").mapNotNull { it.toSearchResult() }
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
            posterUrl = document.selectFirst(".poster img, .video-cover img")?.attr("data-original")
                ?: document.selectFirst(".poster img, .video-cover img")?.attr("data-src")
                ?: document.selectFirst(".poster img, .video-cover img")?.attr("src")
        }
        val tags = document.select("a[href*=/categories/], a[href*=/models/], a[href*=/onlyfans-creators/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
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
        val mappedUrls = mutableSetOf<String>()

        suspend fun searchForStream(html: String, referer: String) {
            // Direct M3U8
            for (match in Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""").findAll(html)) {
                val url = match.value.replace("&amp;", "&")
                if (mappedUrls.add(url)) {
                    callback(newExtractorLink(name, "$name M3U8", url, ExtractorLinkType.M3U8) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                    })
                    found = true
                }
            }

            // Direct MP4
            for (match in Regex("""https?://[^\s"'<>]+?\.mp4[^\s"'<>]*""").findAll(html)) {
                val url = match.value.replace("&amp;", "&")
                if (mappedUrls.add(url)) {
                    val qualityStr = extractQualityFromUrl(url)
                    val qualityVal = getQualityFromString(qualityStr)
                    val sourceName = if (qualityStr != "Unknown") "$name ${qualityStr}p" else "$name MP4"

                    callback(newExtractorLink(name, sourceName, url, ExtractorLinkType.VIDEO) {
                        this.referer = referer
                        this.quality = qualityVal
                    })
                    found = true
                }
            }

            // Player JSON objects (common in KVS)
            val playerNames = listOf(
                "player_aaaa", "player_data", "player_info", "player", "videoConfig",
                "config", "playInfo", "playerConfig", "videoInfo"
            )
            for (pName in playerNames) {
                val match = Regex("""$pName\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html)
                if (match != null) {
                    try {
                        val json = match.groupValues[1]
                        val encrypt = Regex("\"encrypt\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val urlEncoded = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
                        
                        val realUrl = when (encrypt) {
                            1 -> String(Base64.decode(urlEncoded, Base64.DEFAULT), Charsets.UTF_8)
                            2 -> URLDecoder.decode(String(Base64.decode(urlEncoded, Base64.DEFAULT), Charsets.UTF_8), "UTF-8")
                            else -> urlEncoded
                        }
                        
                        if ((realUrl.contains(".m3u8") || realUrl.contains(".mp4")) && mappedUrls.add(realUrl)) {
                            val isM3u8 = realUrl.contains(".m3u8")
                            val qualityStr = extractQualityFromUrl(realUrl)
                            val qualityVal = getQualityFromString(qualityStr)
                            val sourceName = if (qualityStr != "Unknown") "Player ${qualityStr}p" else if (isM3u8) "Player M3U8" else "Player MP4"

                            callback(newExtractorLink(
                                name, 
                                sourceName, 
                                realUrl,
                                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = referer
                                this.quality = qualityVal
                            })
                            found = true
                            break
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 1. Search main page
        searchForStream(mainHtml, data)

        // 2. Iframes
        if (!found) {
            val document = app.get(data, headers = headers).document
            val iframes = document.select("iframe")
            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.startsWith("http")) {
                    try {
                        val iframeHtml = app.get(src, headers = headers + ("Referer" to data)).text
                        searchForStream(iframeHtml, src)
                        if (found) break
                    } catch (_: Exception) {}
                }
            }
        }

        // 3. API fallback (if a video_key parameter exists)
        if (!found && data.contains("video_key=")) {
            val videoKey = data.substringAfter("video_key=").substringBefore("&")
            val apiUrls = listOf(
                "$mainUrl/api/play?video_key=$videoKey",
                "$mainUrl/comic/play?video_key=$videoKey",
                "$mainUrl/api/video?key=$videoKey",
                "$mainUrl/api/getVideo?key=$videoKey"
            )
            for (api in apiUrls) {
                try {
                    val json = app.get(api, headers = headers).text
                    val urlMatch = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(json)
                    if (urlMatch != null) {
                        val streamUrl = urlMatch.groupValues[1]
                        if (streamUrl.contains(".m3u8") && mappedUrls.add(streamUrl)) {
                            callback(newExtractorLink(name, "API", streamUrl, ExtractorLinkType.M3U8) {
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

    // ---------- Quality Parsing Helpers ----------
    private fun extractQualityFromUrl(url: String): String {
        val qualityMatch = Regex("""(?i)(?:_|-|/)(\d{3,4})p?(?:\.mp4|/)""").find(url) 
            ?: Regex("""(?i)(\d{3,4})p""").find(url)
        return qualityMatch?.groupValues?.get(1) ?: "Unknown"
    }

    private fun getQualityFromString(qualityString: String): Int {
        return when {
            qualityString.contains("2160") -> Qualities.P2160.value
            qualityString.contains("1080") -> Qualities.P1080.value
            qualityString.contains("720") -> Qualities.P720.value
            qualityString.contains("480") -> Qualities.P480.value
            qualityString.contains("360") -> Qualities.P360.value
            qualityString.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }
}
