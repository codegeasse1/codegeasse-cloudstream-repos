package com.pornea91

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
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

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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

    // ----------------------------------------------------------------
    // AES IMAGE DECRYPTION
    // ----------------------------------------------------------------
    private suspend fun decryptImageUrl(url: String): String? {
        if (url.isBlank() || url.startsWith("data:")) return url
        return try {
            val response = app.get(url, headers = mapOf("Referer" to "$mainUrl/"))
            val cipherBytes = response.okhttpResponse.body?.bytes() ?: return null
            if ((cipherBytes.size > 2 &&
                        (cipherBytes[0].toInt() and 0xFF) == 0xFF &&
                        (cipherBytes[1].toInt() and 0xFF) == 0xD8) ||
                (cipherBytes.size > 2 &&
                        (cipherBytes[0].toInt() and 0xFF) == 0x89 &&
                        cipherBytes[1].toInt() == 0x50)
            ) return url
            val key = SecretKeySpec("f5d965df75336270".toByteArray(), "AES")
            val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray())
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val dec = cipher.doFinal(cipherBytes)
            val ext = url.substringAfterLast(".", "jpeg").substringBefore("?")
            "data:image/$ext;base64," + Base64.encodeToString(dec, Base64.NO_WRAP)
        } catch (e: Exception) {
            url
        }
    }

    // ----------------------------------------------------------------
    // MAIN PAGE
    // ----------------------------------------------------------------
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
        val scriptImgMatch =
            Regex("""loadBannerDirect\s*\(\s*['"]([^'"]+)['"]""").find(cardHtml)?.groupValues?.get(1)
        val fallbackImg = imgEl?.let {
            it.attr("z-image-loader-url").ifBlank {
                it.attr("x-image-loader-url").ifBlank {
                    it.attr("data-xkrkllgl").ifBlank {
                        it.attr("data-src")
                            .ifBlank { it.attr("data-original").ifBlank { it.attr("src") } }
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

    // ----------------------------------------------------------------
    // SEARCH
    // ----------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResponse>()
        for (page in 1..2) {
            val docUrl =
                if (page == 1) "$mainUrl/comic/index/search?keyword=$encodedQuery"
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

    // ----------------------------------------------------------------
    // LOAD
    // ----------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: document.selectFirst("h1, h2, .post-title")?.text()?.trim()
            ?: "Video"
        val pageHtml = document.outerHtml()
        val contentImg =
            document.selectFirst(".post-content img, article p img, .poster img, .video-cover img")
        var poster = contentImg?.let {
            it.attr("z-image-loader-url").ifBlank {
                it.attr("x-image-loader-url").ifBlank {
                    it.attr("data-xkrkllgl").ifBlank {
                        it.attr("data-src").ifBlank { it.attr("src") }
                    }
                }
            }
        }
        if (poster.isNullOrBlank() || poster.startsWith("data:"))
            poster =
                Regex("""loadBannerDirect\s*\(\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
        if (poster.isNullOrBlank() || poster.startsWith("data:"))
            poster = document.selectFirst("meta[property=og:image]")?.attr("content")
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

    // ----------------------------------------------------------------
    // DEAN EDWARDS UNPACKER
    // Handles single-quoted packed strings including escaped quotes \'
    // ----------------------------------------------------------------
    private fun unpack(html: String): List<String> {
        val results = mutableListOf<String>()
        // Grab every eval(function(p,a,c,k,e,d) block
        // We collect the raw match then extract p/a/c/k manually
        val outerRe = Regex(
            """eval\s*\(\s*function\s*\(p,a,c,k,e,d\)""",
            RegexOption.IGNORE_CASE
        )
        for (outerMatch in outerRe.findAll(html)) {
            val start = outerMatch.range.first
            // Walk forward to find the argument tuple ('...',a,c,'...'.split('|')...)
            val slice = html.substring(start)
            // Extract p (the encoded string) — it follows the last '{' .. '}(' in the packer
            val tupleMatch = Regex(
                """\}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'\.split""",
                RegexOption.DOT_MATCHES_ALL
            ).find(slice) ?: continue

            val p = tupleMatch.groupValues[1].replace("\\'", "'")
            val a = tupleMatch.groupValues[2].toIntOrNull() ?: 36
            val c = tupleMatch.groupValues[3].toIntOrNull() ?: 0
            val kRaw = tupleMatch.groupValues[4].replace("\\'", "'")
            results.add(unpackTuple(p, a, c, kRaw))
        }
        return results
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
            Regex("""\b\w+\b""").replace(p) { mr -> dict[mr.value] ?: mr.value }
        } catch (e: Exception) {
            p
        }
    }

    // ----------------------------------------------------------------
    // AES VIDEO DECRYPTORS
    // ----------------------------------------------------------------
    private fun decryptBase64Aes(b64: String): String {
        return try {
            val clean = b64.trim().replace("\n", "").replace("\r", "")
            val key = SecretKeySpec("f5d965df75336270".toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            String(cipher.doFinal(Base64.decode(clean, Base64.DEFAULT)), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun decryptHexAes(hex: String): String {
        return try {
            val clean = hex.trim()
            if (clean.length % 2 != 0) return ""
            val data = ByteArray(clean.length / 2) { i ->
                ((Character.digit(clean[i * 2], 16) shl 4) +
                        Character.digit(clean[i * 2 + 1], 16)).toByte()
            }
            val key = SecretKeySpec("f5d965df75336270".toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            String(cipher.doFinal(data), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
    }

    // ----------------------------------------------------------------
    // LINK HELPERS  (both suspend so they can call newExtractorLink)
    // ----------------------------------------------------------------
    private suspend fun addLink(
        rawUrl: String,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = rawUrl.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
        if (url.isBlank() || !url.startsWith("http")) return false
        if (!seen.add(url)) return false          // already added
        val isM3u8 = url.contains(".m3u8", ignoreCase = true)
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = url,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.headers = videoHeaders
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    /**
     * Scans [text] for M3U8 / MP4 URLs and fires [callback] for every new one.
     * Returns true if at least one link was added.
     * Does NOT return early so ALL links in the text are collected.
     */
    private suspend fun extractLinks(
        text: String,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalized = text
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        var found = false
        val urlRe = Regex("""https?://[^\s"'\\<>\u0000-\u001F]+""")
        for (m in urlRe.findAll(normalized)) {
            val u = m.value.trimEnd(')', ']', ',', ';', '"', '\'', '\\')
            if (u.contains(".m3u8", ignoreCase = true) || u.contains(".mp4", ignoreCase = true)) {
                if (addLink(u, seen, callback)) found = true
            }
        }
        return found
    }

    // ----------------------------------------------------------------
    // LOAD LINKS
    // ----------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val seen = mutableSetOf<String>()
        val apiHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01"
        )

        // ── Step 1: fetch the detail page ──────────────────────────
        val html = app.get(data, headers = headers).text

        // ── Step 2: plain scan of raw HTML ─────────────────────────
        extractLinks(html, seen, callback)

        // ── Step 3: unpack all eval(function(p,a,c,k)) blocks ──────
        val unpackedScripts = unpack(html)
        for (unpacked in unpackedScripts) {
            // 3a. direct links in unpacked script
            extractLinks(unpacked, seen, callback)

            // 3b. hex-encrypted URL inside unpacked script
            for (hm in Regex("""['"]([0-9a-fA-F]{64,})['"]""").findAll(unpacked)) {
                val dec = decryptHexAes(hm.groupValues[1])
                if (dec.startsWith("http")) {
                    extractLinks(dec, seen, callback)
                    addLink(dec, seen, callback)
                }
            }

            // 3c. call detail_play API discovered inside the unpacked script
            val apiPathRe =
                Regex("""['"]?((?:https?://[^'"]*)?/comic/index/detail_play\?[^'"&\s]+)['"]?""")
            for (am in apiPathRe.findAll(unpacked)) {
                val rawPath = am.groupValues[1].replace("\\/", "/")
                val fullUrl =
                    if (rawPath.startsWith("http")) rawPath else "$mainUrl$rawPath"

                val videoKey =
                    Regex("""video_key=([^&\s'"]+)""").find(fullUrl)?.groupValues?.get(1)
                        ?: Regex("""video_key[=:]\s*['"]?(\w+)""").find(unpacked)?.groupValues?.get(1)
                val hexPayload =
                    Regex("""[uU]=([0-9a-fA-F]{32,})""").find(fullUrl)?.groupValues?.get(1)
                        ?: Regex("""['"]([0-9a-fA-F]{64,})['"]""").find(unpacked)?.groupValues?.get(1)

                val t = System.currentTimeMillis() / 1000
                val candidates = linkedSetOf<String>()
                candidates += fullUrl
                candidates += "$fullUrl&t=$t"
                if (videoKey != null && hexPayload != null) {
                    candidates += "$mainUrl/comic/index/detail_play?video_key=$videoKey&u=$hexPayload&t=$t"
                    candidates += "$mainUrl/comic/index/detail_play?video_key=$videoKey&u=${
                        URLEncoder.encode(hexPayload, "UTF-8")
                    }&t=$t"
                }
                if (videoKey != null)
                    candidates += "$mainUrl/comic/index/detail_play?video_key=$videoKey&t=$t"

                for (apiUrl in candidates) {
                    tryFetchAndExtract(apiUrl, apiHeaders, seen, callback)
                }
            }
        }

        // ── Step 4: extract video_key and brute-force known endpoints
        val videoKey =
            Regex("""video_key[='":\s]+(\w+)""").find(html)?.groupValues?.get(1)
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
            for (ep in endpoints) tryFetchAndExtract(ep, apiHeaders, seen, callback)
        }

        // ── Step 5: mobile UA fallback ──────────────────────────────
        try {
            val mHtml = app.get(
                data, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
                    "Referer" to "$mainUrl/"
                )
            ).text
            extractLinks(mHtml, seen, callback)
            for (script in unpack(mHtml)) extractLinks(script, seen, callback)
        } catch (_: Exception) {
        }

        return seen.isNotEmpty()
    }

    // ----------------------------------------------------------------
    // Helper: GET url, scan response for media links + try AES decryption
    // ----------------------------------------------------------------
    private suspend fun tryFetchAndExtract(
        url: String,
        hdrs: Map<String, String>,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val resp = app.get(url, headers = hdrs).text
            extractLinks(resp, seen, callback)

            // base64-AES
            for (m in Regex("""[A-Za-z0-9+/]{40,}={0,2}""").findAll(resp)) {
                val dec = decryptBase64Aes(m.value)
                if (dec.isNotBlank()) extractLinks(dec, seen, callback)
            }

            // hex-AES
            for (m in Regex("""[0-9a-fA-F]{64,}""").findAll(resp)) {
                val dec = decryptHexAes(m.value)
                if (dec.startsWith("http")) {
                    extractLinks(dec, seen, callback)
                    addLink(dec, seen, callback)
                }
            }

            // recurse into any packed scripts in the response
            for (script in unpack(resp)) extractLinks(script, seen, callback)
        } catch (_: Exception) {
        }
    }
}
