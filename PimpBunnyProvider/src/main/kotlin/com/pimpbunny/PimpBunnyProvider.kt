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

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        initSession()
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

        suspend fun processM3u8(url: String, referer: String) {
            try {
                val playlist = app.get(url, headers = mapOf("Referer" to referer)).text
                if (playlist.contains("#EXT-X-STREAM-INF")) {
                    // Master playlist – extract each variant
                    val lines = playlist.lines()
                    var i = 0
                    while (i < lines.size) {
                        val line = lines[i]
                        if (line.startsWith("#EXT-X-STREAM-INF")) {
                            // parse resolution: e.g., "RESOLUTION=1280x720"
                            val resolution = Regex("RESOLUTION=(\\d+x\\d+)").find(line)?.groupValues?.get(1)
                            val quality = when {
                                resolution != null -> {
                                    val height = resolution.substringAfter("x").toIntOrNull() ?: 0
                                    when {
                                        height >= 2160 -> Qualities.P2160.value
                                        height >= 1440 -> Qualities.P1440.value
                                        height >= 1080 -> Qualities.P1080.value
                                        height >= 720  -> Qualities.P720.value
                                        height >= 480  -> Qualities.P480.value
                                        height >= 360  -> Qualities.P360.value
                                        height >= 240  -> Qualities.P240.value
                                        else -> Qualities.P144.value
                                    }
                                }
                                else -> Qualities.Unknown.value
                            }
                            // next line should be the URI
                            if (i + 1 < lines.size) {
                                val uri = lines[i + 1].trim()
                                if (uri.isNotEmpty() && !uri.startsWith("#")) {
                                    val streamUrl = if (uri.startsWith("http")) uri
                                    else {
                                        // resolve relative URL
                                        val base = url.substringBeforeLast("/") + "/"
                                        base + uri
                                    }
                                    val label = if (resolution != null) "$name - $resolution" else "$name - Variant"
                                    callback(newExtractorLink(name, label, streamUrl, ExtractorLinkType.M3U8) {
                                        this.referer = referer
                                        this.quality = quality
                                    })
                                    found = true
                                }
                            }
                        }
                        i++
                    }
                } else {
                    // Simple m3u8 (no variants)
                    callback(newExtractorLink(name, "$name Stream", url, ExtractorLinkType.M3U8) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                    })
                    found = true
                }
            } catch (_: Exception) {}
        }

        // 1. Direct M3U8 links
        Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""").findAll(mainHtml).forEach { match ->
            val url = match.value.replace("&amp;", "&")
            processM3u8(url, data)
        }

        // 2. Direct MP4 links (only add if m3u8 not found? We'll add them anyway but they might not work)
        if (!found) {
            Regex("""https?://[^\s"'<>]+?\.mp4[^\s"'<>]*""").findAll(mainHtml).forEach { match ->
                val url = match.value.replace("&amp;", "&")
                callback(newExtractorLink(name, "$name MP4", url, ExtractorLinkType.VIDEO) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                })
                found = true
            }
        }

        // 3. Player JSON
        val playerNames = listOf("player_aaaa", "player_data", "player_info", "player", "videoConfig",
            "config", "playInfo", "playerConfig", "videoInfo")
        for (pName in playerNames) {
            val match = Regex("""$pName\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(mainHtml)
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
                    if (realUrl.contains(".m3u8")) {
                        processM3u8(realUrl, data)
                    } else if (realUrl.contains(".mp4")) {
                        callback(newExtractorLink(name, "$name MP4", realUrl, ExtractorLinkType.VIDEO) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        })
                        found = true
                    }
                } catch (_: Exception) {}
            }
        }

        // 4. Iframes
        if (!found) {
            val document = app.get(data, headers = headers).document
            val iframes = document.select("iframe")
            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.startsWith("http")) {
                    try {
                        val iframeHtml = app.get(src, headers = headers + ("Referer" to data)).text
                        // Re-run extraction on iframe content
                        loadLinks(iframeHtml, isCasting, subtitleCallback, callback) // recursive
                    } catch (_: Exception) {}
                }
            }
        }

        // 5. API fallback
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
                        if (streamUrl.contains(".m3u8")) {
                            processM3u8(streamUrl, data)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return found
    }
}
