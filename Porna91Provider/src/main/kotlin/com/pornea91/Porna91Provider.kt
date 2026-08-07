package com.pornea91

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
    override val supportedTypes = setOf(TvType.Movie, TvType.Others)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to ua,
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

    private val posterCacheDir: File? by lazy {
        try {
            CloudStreamApp.context?.let { 
                File(it.cacheDir, "p91_posters").apply { mkdirs() } 
            }
        } catch (e: Exception) {
            null
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

        return newMovieSearchResponse(title, href, TvType.Movie) {
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
            val items = mutableListOf<SearchResponse>()
            for (el in document.select(".video-items .video-item")) {
                val res = el.toSearchResult()
                if (res != null) items.add(res)
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

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = title
            this.tags = tags
        }
    }

    // ------------------------------------------------------------------
    // FIXED: Removed hardcoded domain check to handle CDN rotations.
    // Added magic number checks to skip decryption if it's already an image.
    // Returns file:// URI for better Glide/CloudStream compatibility.
    // ------------------------------------------------------------------
    private suspend fun getDecryptedPoster(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("/") || url.contains("91porna.com")) return fixUrlNull(url)

        try {
            val dir = posterCacheDir ?: return fixUrlNull(url)
            val file = File(dir, "${url.hashCode()}.jpg")
            if (file.exists() && file.length() > 1000) return file.toURI().toString()

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Referer", "$mainUrl/")
            val encBytes = conn.inputStream.use { it.readBytes() }
            if (encBytes.isEmpty()) return fixUrlNull(url)

            // Check if it's already a valid image (JPEG/PNG magic numbers)
            if (encBytes.size > 4 && 
                ((encBytes[0].toInt() and 0xFF) == 0xFF && (encBytes[1].toInt() and 0xFF) == 0xD8) || // JPEG
                ((encBytes[0].toInt() and 0xFF) == 0x89 && encBytes[1].toInt() == 0x50) // PNG
            ) {
                file.writeBytes(encBytes)
                return file.toURI().toString()
            }

            val key = SecretKeySpec("f5d965df75336270".toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)

            val b64Text = String(cipher.doFinal(encBytes), Charsets.UTF_8).trim()
            val imgBytes = Base64.decode(b64Text, Base64.DEFAULT)
            if (imgBytes == null || imgBytes.size < 100) return fixUrlNull(url)

            file.writeBytes(imgBytes)
            return file.toURI().toString()
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
        val html = app.get(data, headers = headers).text
        if (extractFromPage(html, data, callback)) return true

        var vidId = data.substringAfter("video_key=", "").substringBefore("&").substringBefore("\"").substringBefore("'")
        if (vidId.isBlank()) {
            vidId = data.substringAfterLast("/").substringBefore("?").substringBefore(".")
        }
        
        if (vidId.isNotBlank()) {
            try {
                val embedHtml = app.get("$mainUrl/comic/index/embed?id=$vidId", headers = headers).text
                if (extractFromPage(embedHtml, data, callback)) return true
            } catch (_: Exception) { }
        }

        return false
    }

    // ------------------------------------------------------------------
    // FIXED: Unescapes JSON slashes (\/) and hex codes before running Regex.
    // Added fallbacks to probe common REST API endpoints used by the site.
    // ------------------------------------------------------------------
    private fun extractM3u8(text: String): String? {
        val unescaped = text.replace("\\/", "/").replace("\\u002F", "/").replace("\\u0026", "&")
        return Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""").find(unescaped)?.value
    }

    private suspend fun extractFromPage(html: String, dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        // 1. Try the old detail_play endpoint
        val playMatch = Regex("""/index/detail_play\?[^"'\s]+""").find(html)
        if (playMatch != null) {
            var playUrl = playMatch.value.replace("\\/", "/").replace("&amp;", "&")
            playUrl = playUrl.removeSuffix("&t=").removeSuffix("&t")
            playUrl += "&t=" + (System.currentTimeMillis() / 1000 / 2100)

            try {
                val playHtml = app.get(fixUrl(playUrl), headers = headers).text
                val m3u8 = extractM3u8(playHtml)
                if (!m3u8.isNullOrBlank()) {
                    callback(newExtractorLink(name, "$name HLS", m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    })
                    return true
                }
            } catch (_: Exception) { }
        }

        // 2. Try to find API endpoint
        var vidId = dataUrl.substringAfter("video_key=", "").substringBefore("&").substringBefore("\"").substringBefore("'")
        if (vidId.isBlank()) {
            vidId = dataUrl.substringAfterLast("/").substringBefore("?").substringBefore(".")
        }
        
        if (vidId.isNotBlank()) {
            val apiEndpoints = listOf(
                "/api/video/detail?video_key=$vidId",
                "/api/comic/video/detail?video_key=$vidId",
                "/api/video/get_video?video_key=$vidId",
                "/api/video/play?video_key=$vidId",
                "/api/v1/video/detail?video_key=$vidId",
                "/api/video/info?video_key=$vidId",
                "/api/video/detail/$vidId",
                "/api/comic/video/detail/$vidId"
            )
            for (endpoint in apiEndpoints) {
                try {
                    val apiUrl = fixUrl(endpoint)
                    val apiRes = app.get(apiUrl, headers = headers).text
                    val m3u8 = extractM3u8(apiRes)
                    if (!m3u8.isNullOrBlank()) {
                        callback(newExtractorLink(name, "$name API", m3u8, ExtractorLinkType.M3U8) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                        })
                        return true
                    }
                } catch (_: Exception) { }
            }
        }

        // 3. Last resort: plain m3u8 anywhere in the page
        val direct = extractM3u8(html)
        if (!direct.isNullOrBlank()) {
            callback(newExtractorLink(name, "$name Direct", direct, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            })
            return true
        }
        return false
    }
}