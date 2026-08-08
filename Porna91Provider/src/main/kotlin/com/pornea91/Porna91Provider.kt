package com.pornea91

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
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
        "Accept" to "*/*",
        "Referer" to "$mainUrl/"
    )

    // Headers attached to every video link so ExoPlayer can fetch the AES key
    private val videoHeaders = mapOf(
        "User-Agent" to ua,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/comic/index/video?category=now_month_hot" to "Hot Ranking",
        "$mainUrl/comic/index/video?category=original" to "Original",
        "$mainUrl/comic/index/video?category=play" to "Currently Playing",
        "$mainUrl/comic/index/video?category=new_update" to "Recent Updates",
        "$mainUrl/melonshort" to "Short Videos",
        "$mainUrl/comic/index/av" to "Japan AV"
    )

    // ---------------------------------------------------------------
    // NATIVE AES IMAGE DECRYPTION
    // ---------------------------------------------------------------
    private suspend fun decryptImageUrl(url: String): String? {
        if (url.isBlank() || url.startsWith("data:")) return url
        return try {
            val response = app.get(url, headers = mapOf("Referer" to "$mainUrl/"))
            val cipherBytes = response.okhttpResponse.body?.bytes() ?: return null

            if ((cipherBytes.size > 2 && (cipherBytes[0].toInt() and 0xFF) == 0xFF && (cipherBytes[1].toInt() and 0xFF) == 0xD8) ||
                (cipherBytes.size > 2 && (cipherBytes[0].toInt() and 0xFF) == 0x89 && cipherBytes[1].toInt() == 0x50)
            ) {
                return url
            }

            val key = SecretKeySpec("f5d965df75336270".toByteArray(), "AES")
            val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray())
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val decryptedBytes = cipher.doFinal(cipherBytes)

            val ext = url.substringAfterLast(".", "jpeg").substringBefore("?")
            "data:image/$ext;base64," + Base64.encodeToString(decryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            url
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val docUrl = if (page == 1) request.data else "${request.data}&page=$page"
        val document = app.get(docUrl, headers = headers).document

        val items = mutableListOf<SearchResponse>()
        for (el in document.select(".video-items .video-item, ul.video-items > li.video-item")) {
            el.toSearchResultAsync()?.let { items.add(it) }
        }
        return newHomePageResponse(request.name, items)
    }

    private suspend fun Element.toSearchResultAsync(): SearchResponse? {
        val link = this.selectFirst("a[href*=/detail], a[href*=/avdetail], a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        val imgEl = this.selectFirst("img")
        val title = imgEl?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".title, .line-clamp-2, .post-item-title, h3, h4")?.text()?.trim()
            ?: this.text().substringBefore(" • ").trim()

        val cardHtml = this.outerHtml()
        val scriptImgMatch = Regex("""loadBannerDirect\s*\(\s*['"]([^'"]+)['"]""").find(cardHtml)?.groupValues?.get(1)
        val fallbackImg = imgEl?.let {
            it.attr("z-image-loader-url").ifBlank {
                it.attr("x-image-loader-url").ifBlank {
                    it.attr("data-xkrkllgl").ifBlank {
                        it.attr("data-src").ifBlank { it.attr("data-original").ifBlank { it.attr("src") } }
                    }
                }
            }
        }

        val rawPosterUrl = scriptImgMatch ?: fallbackImg
        val finalPosterUrl = rawPosterUrl?.let { decryptImageUrl(it) ?: it }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = finalPosterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResponse>()

        for (page in 1..2) {
            val docUrl = if (page == 1) "$mainUrl/comic/index/search?keyword=$encodedQuery"
            else "$mainUrl/comic/index/search?keyword=$encodedQuery&page=$page"

            val document = app.get(docUrl, headers = headers).document
            val items = mutableListOf<SearchResponse>()
            for (el in document.select(".video-items .video-item")) {
                el.toSearchResultAsync()?.let { items.add(it) }
            }
            if (items.isEmpty()) break
            results.addAll(items)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: document.selectFirst("h1, h2, .post-title")?.text()?.trim() ?: "Video"

        val pageHtml = document.outerHtml()

        val contentImg = document.selectFirst(".post-content img, article p img, .poster img, .video-cover img")
        var poster = contentImg?.let {
            it.attr("z-image-loader-url").ifBlank {
                it.attr("x-image-loader-url").ifBlank {
                    it.attr("data-xkrkllgl").ifBlank {
                        it.attr("data-src").ifBlank { it.attr("src") }
                    }
                }
            }
        }

        if (poster.isNullOrBlank() || poster.startsWith("data:")) {
            poster = Regex("""loadBannerDirect\s*\(\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
        }
        if (poster.isNullOrBlank() || poster.startsWith("data:")) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        }

        val finalPoster = poster?.let { decryptImageUrl(it) ?: it }

        val tags = document.select("a[href*=/search?keyword=]").map { it.text().trim() }.filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = finalPoster
            this.backgroundPosterUrl = finalPoster
            this.plot = title
            this.tags = tags
        }
    }

    // ---------------------------------------------------------------
    // DEAN EDWARDS UNPACKER  (fixed: unpack the argument tuple directly)
    // ---------------------------------------------------------------
    private fun unpackTuple(p: String, a: Int, c: Int, kRaw: String): String {
        return try {
            val k = kRaw.split("|")
            fun enc(cc: Int): String {
                val first = if (cc >= a) enc(cc / a) else ""
                val m = cc % a
                return first + (if (m > 35) (m + 29).toChar().toString() else m.toString(36))
            }
            val dict = mutableMapOf<String, String>()
            for (i in 0 until c) {
                val word = k.getOrNull(i)
                if (!word.isNullOrBlank()) dict[enc(i)] = word
            }
            Regex("""\b\w+\b""").replace(p) { m -> dict[m.value] ?: m.value }
        } catch (e: Exception) {
            p
        }
    }

    // ---------------------------------------------------------------
    // AES VIDEO PAYLOAD DECRYPTORS
    // ---------------------------------------------------------------
    private fun decryptVideoPayload(encryptedB64: String): String {
        return try {
            val clean = encryptedB64.trim().replace("\n", "").replace("\r", "")
            val key = SecretKeySpec("f5d965df75336270".toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            String(cipher.doFinal(Base64.decode(clean, Base64.DEFAULT)), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun decryptHexVideoUrl(hexStr: String): String {
        return try {
            val clean = hexStr.trim()
            if (clean.length % 2 != 0) return ""
            val data = ByteArray(clean.length / 2) { i ->
                ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
            }
            val key = SecretKeySpec("f5d965df75336270".toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            String(cipher.doFinal(data), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            ""
        }
    }

    // ---------------------------------------------------------------
    // LINK HELPERS
    // ---------------------------------------------------------------
    private fun extractAndAddLinks(text: String, mappedUrls: MutableSet<String>, callback: (ExtractorLink) -> Unit): Boolean {
        var localFound = false
        val cdnRegex = Regex("""(https?://[^\s"'\\]+?\.(?:m3u8|mp4)[^\s"'\\]*)""")
        val unescaped = text.replace("\\/", "/").replace("\\u002F", "/").replace("\\u0026", "&")

        for (match in cdnRegex.findAll(unescaped)) {
            var cleanUrl = match.groupValues[1].replace("&amp;", "&")

            if (cleanUrl.contains("url=http")) {
                cleanUrl = cleanUrl.substringAfter("url=").substringBefore("&")
                try { cleanUrl = java.net.URLDecoder.decode(cleanUrl, "UTF-8") } catch (e: Exception) {}
            }

            if (cleanUrl.isNotBlank() && mappedUrls.add(cleanUrl)) {
                val isM3u8 = cleanUrl.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Server",
                        url = cleanUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = videoHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
                localFound = true
            }
        }
        return localFound
    }

    private fun tryDecryptAndExtract(text: String, mappedUrls: MutableSet<String>, callback: (ExtractorLink) -> Unit): Boolean {
        if (extractAndAddLinks(text, mappedUrls, callback)) return true

        for (match in Regex("""[A-Za-z0-9+/=]{40,}""").findAll(text)) {
            val decrypted = decryptVideoPayload(match.value.trim())
            if (decrypted.isNotBlank() && extractAndAddLinks(decrypted, mappedUrls, callback)) return true
        }
        return false
    }

    // ---------------------------------------------------------------
    // LOAD LINKS
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val html = app.get(data, headers = headers).text
        val mappedUrls = mutableSetOf<String>()
        val apiHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest"
        )
        val t = System.currentTimeMillis() / 1000 / 2100

        // 1. Plain scan of the page
        if (tryDecryptAndExtract(html, mappedUrls, callback)) return true

        // 2. FIXED unpacker: grab the packed argument tuple straight from HTML
        val tupleRegex = Regex("""\('([^']*)',\s*(\d+),\s*(\d+),\s*'([^']+)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
        for (tm in tupleRegex.findAll(html)) {
            val unpacked = unpackTuple(tm.groupValues[1], tm.groupValues[2].toIntOrNull() ?: 10, tm.groupValues[3].toIntOrNull() ?: 10, tm.groupValues[4])

            // a) hex 'u' payload decrypted locally
            val hexPayload = Regex("""encodeURIComponent\(\s*['"]([0-9a-fA-F]{16,})['"]\s*\)""").find(unpacked)?.groupValues?.get(1)
                ?: Regex("""['"]([0-9a-fA-F]{64,})['"]""").find(unpacked)?.groupValues?.get(1)
            if (hexPayload != null) {
                val dec = decryptHexVideoUrl(hexPayload)
                if (dec.startsWith("http") && extractAndAddLinks(dec, mappedUrls, callback)) return true
            }

            // b) call detail_play with every sensible combination
            val baseMatch = Regex("""["'](/comic/index/detail_play\?[^"']+)["']""").find(unpacked)?.groupValues?.get(1)?.replace("\\/", "/")
            val candidates = mutableListOf<String>()
            if (baseMatch != null) {
                val base = mainUrl + baseMatch
                if (baseMatch.contains("u=") && hexPayload != null) {
                    candidates.add(base + URLEncoder.encode(hexPayload, "UTF-8") + "&t=$t")
                }
                if (!baseMatch.contains("&t=")) candidates.add("$base&t=$t")
                candidates.add(base)
            }
            val vidKey = Regex("""video_key=(\w+)""").find(html)?.groupValues?.get(1)
                ?: Regex("""video_key=(\w+)""").find(data)?.groupValues?.get(1)
            if (vidKey != null) {
                if (hexPayload != null) candidates.add("$mainUrl/comic/index/detail_play?video_key=$vidKey&u=$hexPayload&t=$t")
                candidates.add("$mainUrl/comic/index/detail_play?video_key=$vidKey&t=$t")
            }
            for (c in candidates.distinct()) {
                try {
                    val res = app.get(c, headers = apiHeaders).text
                    if (tryDecryptAndExtract(res, mappedUrls, callback)) return true
                } catch (e: Exception) {}
            }

            // c) maybe the unpacked script already contains the cdn url
            if (extractAndAddLinks(unpacked, mappedUrls, callback)) return true
        }

        // 3. Mobile UA fallback
        try {
            val mobileUa = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
            val mobileHtml = app.get(data, headers = mapOf("User-Agent" to mobileUa, "Referer" to "$mainUrl/")).text
            if (tryDecryptAndExtract(mobileHtml, mappedUrls, callback)) return true
        } catch (e: Exception) {}

        // 4. Fallback API endpoints
        val vidId = (Regex("""data-video=['"](\d+)['"]""").find(html)
            ?: Regex("""video_key=([^&"']+)""").find(data)
            ?: Regex("""/detail/(\d+)""").find(data))?.groupValues?.get(1)
            ?: data.substringAfterLast("/").substringBefore("?").substringBefore(".")

        if (vidId.isNotBlank()) {
            for (api in listOf(
                "/api/comic/video/detail?video_key=$vidId",
                "/api/video/detail?video_key=$vidId",
                "/api/video/get_video?video_key=$vidId",
                "/api/v1/video/detail?video_key=$vidId",
                "/comic/index/detail_play?video_key=$vidId"
            )) {
                try {
                    val apiRes = app.get(fixUrl(api), headers = apiHeaders).text
                    if (tryDecryptAndExtract(apiRes, mappedUrls, callback)) { found = true; break }
                } catch (e: Exception) {}
            }
        }

        return found
    }
}