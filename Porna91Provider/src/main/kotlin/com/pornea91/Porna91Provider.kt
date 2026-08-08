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
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "$mainUrl/"
    )

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
    // AES IMAGE DECRYPTION
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

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
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

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
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

    // ---------------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: document.selectFirst("h1, h2, .post-title")?.text()?.trim()
            ?: "Video"

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

        val tags = document.select("a[href*=/search?keyword=]")
            .map { it.text().trim() }.filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = finalPoster
            this.backgroundPosterUrl = finalPoster
            this.plot = title
            this.tags = tags
        }
    }

    // ---------------------------------------------------------------
    // DEAN EDWARDS UNPACKER
    // ---------------------------------------------------------------
    private fun unpack(packedJs: String): String {
        return try {
            // Match: eval(function(p,a,c,k,e,d){...}('CODE',BASE,COUNT,'KEYS'.split('|'),0,{}))
            val match = Regex(
                """eval\s*\(\s*function\s*\(p,a,c,k,e,d\).*?}\s*\(\s*'(.*?)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\(""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            ).find(packedJs) ?: return packedJs

            val p = match.groupValues[1]
            val a = match.groupValues[2].toIntOrNull() ?: 36
            val c = match.groupValues[3].toIntOrNull() ?: 0
            val kRaw = match.groupValues[4]
            unpackTuple(p, a, c, kRaw)
        } catch (e: Exception) {
            packedJs
        }
    }

    private fun unpackTuple(p: String, a: Int, c: Int, kRaw: String): String {
        return try {
            val k = kRaw.split("|")
            fun enc(n: Int): String {
                val prefix = if (n >= a) enc(n / a) else ""
                val m = n % a
                return prefix + if (m > 35) (m + 29).toChar().toString() else m.toString(36)
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
    // AES VIDEO DECRYPTORS
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
    // EXTRACTOR LINK HELPER - FIXED
    // ---------------------------------------------------------------
    private suspend fun addLink(url: String, mappedUrls: MutableSet<String>, callback: (ExtractorLink) -> Unit): Boolean {
        val cleanUrl = url.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")

        if (cleanUrl.isBlank()) return false
        if (!cleanUrl.startsWith("http")) return false
        if (!mappedUrls.add(cleanUrl)) return false

        val isM3u8 = cleanUrl.contains(".m3u8", ignoreCase = true)

        // Use the correct newExtractorLink signature
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = cleanUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.headers = videoHeaders
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    private suspend fun extractLinks(text: String, mappedUrls: MutableSet<String>, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        val normalized = text
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        // Match M3U8 URLs first (highest priority)
        val m3u8Regex = Regex("""https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*""")
        for (match in m3u8Regex.findAll(normalized)) {
            if (addLink(match.value, mappedUrls, callback)) found = true
        }

        // Match MP4 URLs
        val mp4Regex = Regex("""https?://[^\s"'\\<>]+\.mp4[^\s"'\\<>]*""")
        for (match in mp4Regex.findAll(normalized)) {
            if (addLink(match.value, mappedUrls, callback)) found = true
        }

        return found
    }

    // ---------------------------------------------------------------
    // LOAD LINKS - MAIN LOGIC
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mappedUrls = mutableSetOf<String>()
        val apiHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01"
        )

        // Step 1: Fetch the page
        val html = app.get(data, headers = headers).text

        // Step 2: Direct scan for links
        if (extractLinks(html, mappedUrls, callback)) return true

        // Step 3: Find and unpack eval(function(p,a,c,k...)) blocks
        val scriptBlocks = Regex("""eval\s*\(\s*function\s*\(p,a,c,k,e,d\).*?\)\s*\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).findAll(html)

        for (block in scriptBlocks) {
            val unpacked = unpack(block.value)
            if (unpacked == block.value) continue // didn't unpack

            // Scan unpacked script for direct links
            if (extractLinks(unpacked, mappedUrls, callback)) return true

            // Look for detail_play API call in unpacked script
            val detailPlayUrl = Regex("""['"]((?:https?://[^'"]*)?/comic/index/detail_play\?[^'"]+)['"]""")
                .find(unpacked)?.groupValues?.get(1)?.replace("\\/", "/")

            if (detailPlayUrl != null) {
                val fullApiUrl = if (detailPlayUrl.startsWith("http")) detailPlayUrl
                else "$mainUrl$detailPlayUrl"

                // Extract video_key and hex payload from unpacked script
                val videoKey = Regex("""video_key[=:]\s*['"]?(\w+)['"]?""").find(unpacked)?.groupValues?.get(1)
                val hexPayload = Regex("""['"]([0-9a-fA-F]{32,})['"]""").find(unpacked)?.groupValues?.get(1)

                val t = System.currentTimeMillis() / 1000

                val urlsToTry = mutableListOf<String>()

                // Add the exact URL from unpacked script
                urlsToTry.add(fullApiUrl)
                if (!fullApiUrl.contains("&t=")) urlsToTry.add("$fullApiUrl&t=$t")

                // Try with hex payload if found
                if (videoKey != null && hexPayload != null) {
                    urlsToTry.add("$mainUrl/comic/index/detail_play?video_key=$videoKey&u=$hexPayload&t=$t")
                    urlsToTry.add("$mainUrl/comic/index/detail_play?video_key=$videoKey&u=${URLEncoder.encode(hexPayload, "UTF-8")}&t=$t")
                }
                if (videoKey != null) {
                    urlsToTry.add("$mainUrl/comic/index/detail_play?video_key=$videoKey&t=$t")
                }

                for (apiUrl in urlsToTry.distinct()) {
                    try {
                        val resp = app.get(apiUrl, headers = apiHeaders).text
                        // Try direct extraction
                        if (extractLinks(resp, mappedUrls, callback)) return true

                        // Try base64 decryption on any long base64 strings
                        for (b64Match in Regex("""[A-Za-z0-9+/]{40,}={0,2}""").findAll(resp)) {
                            val dec = decryptVideoPayload(b64Match.value)
                            if (dec.isNotBlank() && extractLinks(dec, mappedUrls, callback)) return true
                        }

                        // Try hex decryption
                        for (hexMatch in Regex("""[0-9a-fA-F]{64,}""").findAll(resp)) {
                            val dec = decryptHexVideoUrl(hexMatch.value)
                            if (dec.startsWith("http") && addLink(dec, mappedUrls, callback)) return true
                        }
                    } catch (e: Exception) { /* continue */ }
                }
            }

            // Look for hex encrypted URLs in unpacked script
            for (hexMatch in Regex("""['"]([0-9a-fA-F]{64,})['"]""").findAll(unpacked)) {
                val dec = decryptHexVideoUrl(hexMatch.value)
                if (dec.startsWith("http") && addLink(dec, mappedUrls, callback)) return true
            }
        }

        // Step 4: Extract video_key from page and try API endpoints directly
        val videoKey = Regex("""video_key[='":\s]+(\w+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""[?&]video_key=([^&"'\s]+)""").find(data)?.groupValues?.get(1)
            ?: Regex("""/detail[^/]*/(\d+)""").find(data)?.groupValues?.get(1)
            ?: data.substringAfterLast("/").substringBefore("?").substringBefore(".")

        if (videoKey.isNotBlank()) {
            val t = System.currentTimeMillis() / 1000
            val endpoints = listOf(
                "$mainUrl/comic/index/detail_play?video_key=$videoKey&t=$t",
                "$mainUrl/api/comic/video/detail?video_key=$videoKey",
                "$mainUrl/api/video/detail?video_key=$videoKey",
                "$mainUrl/api/video/get_video?video_key=$videoKey",
                "$mainUrl/api/v1/video/detail?video_key=$videoKey"
            )
            for (endpoint in endpoints) {
                try {
                    val resp = app.get(endpoint, headers = apiHeaders).text
                    if (extractLinks(resp, mappedUrls, callback)) return true
                    // Try decrypting response
                    for (b64Match in Regex("""[A-Za-z0-9+/]{40,}={0,2}""").findAll(resp)) {
                        val dec = decryptVideoPayload(b64Match.value)
                        if (dec.isNotBlank() && extractLinks(dec, mappedUrls, callback)) return true
                    }
                } catch (e: Exception) { /* continue */ }
            }
        }

        // Step 5: Mobile UA fallback
        try {
            val mobileHtml = app.get(
                data, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
                    "Referer" to "$mainUrl/"
                )
            ).text
            if (extractLinks(mobileHtml, mappedUrls, callback)) return true
        } catch (e: Exception) { /* ignore */ }

        return mappedUrls.isNotEmpty()
    }
}
