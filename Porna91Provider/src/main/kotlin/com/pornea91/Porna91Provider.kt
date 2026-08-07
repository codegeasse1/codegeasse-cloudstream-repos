package com.pornea91

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Porna91Provider : MainAPI() {
    override var mainUrl = "https://91porna.com"
    override var name = "91Porna"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "$mainUrl/"
    )

    private var sessionInitialized = false
    private suspend fun initSession() {
        if (!sessionInitialized) {
            try { app.get(mainUrl, headers = headers).text } catch (e: Exception) {}
            sessionInitialized = true
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/comic/index/video?category=now_month_hot" to "Hot Ranking",
        "$mainUrl/comic/index/video?category=original" to "Original",
        "$mainUrl/comic/index/video?category=play" to "Currently Playing",
        "$mainUrl/comic/index/video?category=new_update" to "Recent Updates",
        "$mainUrl/melonshort" to "Short Videos",
        "$mainUrl/comic/index/av" to "Japan AV",
        "$mainUrl/moviesets" to "Collections",
        "$mainUrl/novels" to "Novels"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        initSession()
        val baseUrl = request.data
        val docUrl = if (page == 1) baseUrl else "$baseUrl&page=$page"
        val document = app.get(docUrl, headers = headers).document
        val items = mutableListOf<SearchResponse>()
        
        for (el in document.select(".video-items .video-item, ul.video-items > li.video-item")) {
            val res = el.toSearchResult()
            if (res != null) items.add(res)
        }
        return newHomePageResponse(request.name, items)
    }

    private suspend fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href*=/detail], a[href*=/avdetail], a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        val img = this.selectFirst("img")
        val title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".title, .line-clamp-2, .post-item-title, h3, h4")?.text()?.trim()
            ?: return null

        val rawPoster = img?.attr("data-src")?.ifBlank { img.attr("data-original") }?.ifBlank { img.attr("src") }
        val poster = getDecryptedPoster(rawPoster)

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        initSession()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResponse>()
        
        for (page in 1..3) {
            val docUrl = if (page == 1) "$mainUrl/comic/index/search?keyword=$encodedQuery"
            else "$mainUrl/comic/index/search?keyword=$encodedQuery&page=$page"
            val document = app.get(docUrl, headers = headers).document
            
            val items = document.select(".video-items .video-item").mapNotNull {
                it.toSearchResult()
            }
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

        var rawPoster = document.selectFirst("meta[property=og:image]")?.attr("content")
        if (rawPoster.isNullOrBlank()) {
            val img = document.selectFirst(".poster img, .video-cover img, .video-item img")
            rawPoster = img?.attr("data-src")?.ifBlank { img.attr("src") }
        }
        val poster = getDecryptedPoster(rawPoster)

        val tags = document.select("a[href*=/search?keyword=]").map { it.text().trim() }.filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = title
            this.tags = tags
        }
    }

    // Returns a Data URI instead of a file:// URI to completely bypass Android 10+ Scoped Storage restrictions
    private suspend fun getDecryptedPoster(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("data:")) return url // Already a data URI

        try {
            val response = app.get(url, headers = headers)
            val encBytes = response.body.bytes()
            if (encBytes.isEmpty()) return fixUrlNull(url)

            // Magic number check: If it is an unencrypted JPEG or PNG, return the raw URL
            if (encBytes.size > 4 && 
                (((encBytes[0].toInt() and 0xFF) == 0xFF && (encBytes[1].toInt() and 0xFF) == 0xD8) || 
                ((encBytes[0].toInt() and 0xFF) == 0x89 && encBytes[1].toInt() == 0x50))
            ) {
                return fixUrlNull(url)
            }

            // Execute the AES Decryption
            val key = SecretKeySpec("f5d965df75336270".toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)

            val b64Text = String(cipher.doFinal(encBytes), Charsets.UTF_8).trim()
            
            // Reconstruct the Base64 image directly into a Data URI for CloudStream's image loader
            return "data:image/jpeg;base64,$b64Text"
            
        } catch (e: Exception) {
            return fixUrlNull(url)
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
        val html = app.get(data, headers = headers).text

        fun extractM3u8(text: String): String? {
            val unescaped = text.replace("\\/", "/").replace("\\u002F", "/").replace("\\u0026", "&")
            // This Regex successfully captures the full URL including the ?auth_key query parameters
            return Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""").find(unescaped)?.groupValues?.get(1)
        }

        // 1. Probe the legacy detail_play endpoint
        val playMatch = Regex("""/index/detail_play\?[^"'\s]+""").find(html)
        if (playMatch != null) {
            var playUrl = playMatch.value.replace("\\/", "/").replace("&amp;", "&")
            playUrl = playUrl.substringBefore("&t=").substringBefore("&t")
            playUrl += "&t=" + (System.currentTimeMillis() / 1000 / 2100)

            try {
                val playHtml = app.get(fixUrl(playUrl), headers = headers).text
                val m3u8 = extractM3u8(playHtml)
                if (!m3u8.isNullOrBlank()) {
                    callback(newExtractorLink(name, "$name HLS", m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    })
                    found = true
                }
            } catch (_: Exception) { }
        }

        // 2. Iterate through modern REST API endpoints
        val vidIdMatch = Regex("""video_key=([^&]+)""").find(data) ?: Regex("""/detail/(\d+)""").find(data)
        val vidId = vidIdMatch?.groupValues?.get(1) ?: data.substringAfterLast("/").substringBefore("?").substringBefore(".")
        
        if (vidId.isNotBlank()) {
            val apiEndpoints = listOf(
                "/api/video/detail?video_key=$vidId",
                "/api/comic/video/detail?video_key=$vidId",
                "/api/video/get_video?video_key=$vidId",
                "/api/video/play?video_key=$vidId",
                "/api/v1/video/detail?video_key=$vidId",
                "/api/video/info?video_key=$vidId"
            )
            for (endpoint in apiEndpoints) {
                try {
                    val apiRes = app.get(fixUrl(endpoint), headers = headers).text
                    val m3u8 = extractM3u8(apiRes)
                    if (!m3u8.isNullOrBlank()) {
                        callback(newExtractorLink(name, "$name API", m3u8, ExtractorLinkType.M3U8) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                        })
                        found = true
                        break // Stop iterating once a valid stream is captured
                    }
                } catch (_: Exception) { }
            }
            
            // Check embed fallback
            if (!found) {
                try {
                    val embedHtml = app.get("$mainUrl/comic/index/embed?id=$vidId", headers = headers).text
                    val m3u8 = extractM3u8(embedHtml)
                    if (!m3u8.isNullOrBlank()) {
                        callback(newExtractorLink(name, "$name Embed", m3u8, ExtractorLinkType.M3U8) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                        })
                        found = true
                    }
                } catch (_: Exception) { }
            }
        }

        // 3. Fallback: Search the raw HTML directly
        if (!found) {
            val direct = extractM3u8(html)
            if (!direct.isNullOrBlank()) {
                callback(newExtractorLink(name, "$name Direct", direct, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                })
                found = true
            }
        }

        return found
    }
}
