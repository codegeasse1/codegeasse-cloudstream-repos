package com.pornea91

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
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
        "Referer" to "$mainUrl/"
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
    // IMAGE DECRYPTION
    // ----------------------------------------------------------------
    private suspend fun decryptImageUrl(url: String): String? {
        if (url.isBlank() || url.startsWith("data:")) return url
        return try {
            val response = app.get(url, headers = mapOf("Referer" to "$mainUrl/"))
            val cipherBytes = response.okhttpResponse.body?.bytes() ?: return null
            if ((cipherBytes.size > 2 && (cipherBytes[0].toInt() and 0xFF) == 0xFF && (cipherBytes[1].toInt() and 0xFF) == 0xD8) ||
                (cipherBytes.size > 2 && (cipherBytes[0].toInt() and 0xFF) == 0x89 && cipherBytes[1].toInt() == 0x50)
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
        val items = document.select(".video-items .video-item, ul.video-items > li.video-item")
            .mapNotNull { it.toSearchResultAsync() }
        return newHomePageResponse(request.name, items)
    }

    private suspend fun Element.toSearchResultAsync(): SearchResponse? {
        val link = this.selectFirst("a[href*=/detail], a[href*=/avdetail], a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val imgEl = this.selectFirst("img")
        val title = imgEl?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".title, .line-clamp-2, h3, h4")?.text()?.trim()
            ?: this.text().substringBefore(" • ").trim()
        val scriptImgMatch = Regex("""loadBannerDirect\s*\(\s*['"]([^'"]+)['"]""").find(this.outerHtml())?.groupValues?.get(1)
        val fallbackImg = imgEl?.let {
            it.attr("z-image-loader-url").ifBlank {
                it.attr("x-image-loader-url").ifBlank {
                    it.attr("data-xkrkllgl").ifBlank {
                        it.attr("data-src").ifBlank { it.attr("data-original").ifBlank { it.attr("src") } }
                    }
                }
            }
        }
        val posterUrl = (scriptImgMatch ?: fallbackImg)?.let { decryptImageUrl(it) ?: it }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ----------------------------------------------------------------
    // SEARCH
    // ----------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResponse>()
        for (page in 1..2) {
            val docUrl = if (page == 1) "$mainUrl/comic/index/search?keyword=$encodedQuery"
            else "$mainUrl/comic/index/search?keyword=$encodedQuery&page=$page"
            val items = app.get(docUrl, headers = headers).document
                .select(".video-items .video-item")
                .mapNotNull { it.toSearchResultAsync() }
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
        val contentImg = document.selectFirst(".post-content img, article p img, .poster img, .video-cover img")
        var poster = contentImg?.let {
            it.attr("z-image-loader-url").ifBlank {
                it.attr("x-image-loader-url").ifBlank {
                    it.attr("data-xkrkllgl").ifBlank { it.attr("data-src").ifBlank { it.attr("src") } }
                }
            }
        }
        if (poster.isNullOrBlank() || poster.startsWith("data:"))
            poster = Regex("""loadBannerDirect\s*\(\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
        if (poster.isNullOrBlank() || poster.startsWith("data:"))
            poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val finalPoster = poster?.let { decryptImageUrl(it) ?: it }
        val tags = document.select("a[href*=/search?keyword=]").map { it.text().trim() }.filter { it.isNotBlank() }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = finalPoster
            this.plot = title
            this.tags = tags
        }
    }

    // ----------------------------------------------------------------
    // UNPACKER
    // ----------------------------------------------------------------
    private fun unpackAll(html: String): List<String> {
        val results = mutableListOf<String>()
        val re = Regex("""\}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'\.split""", RegexOption.DOT_MATCHES_ALL)
        for (m in re.findAll(html)) {
            val p = m.groupValues[1].replace("\\'", "'")
            val a = m.groupValues[2].toIntOrNull() ?: 36
            val c = m.groupValues[3].toIntOrNull() ?: 0
            val k = m.groupValues[4].replace("\\'", "'").split("|")
            fun enc(n: Int): String {
                val prefix = if (n >= a) enc(n / a) else ""
                val mod = n % a
                return prefix + if (mod > 35) (mod + 29).toChar().toString() else mod.toString(36)
            }
            val dict = mutableMapOf<String, String>()
            for (i in 0 until c) k.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { dict[enc(i)] = it }
            results.add(Regex("""\b\w+\b""").replace(p) { dict[it.value] ?: it.value })
        }
        return results
    }

    // ----------------------------------------------------------------
    // AES DECRYPTORS
    // ----------------------------------------------------------------
    private fun decryptAes(data: ByteArray): String {
        return try {
            val key = SecretKeySpec("f5d965df75336270".toByteArray(), "AES")
            val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray())
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            String(cipher.doFinal(data), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun decryptBase64(b64: String): String {
        return try {
            decryptAes(Base64.decode(b64.trim(), Base64.DEFAULT))
        } catch (_: Exception) {
            ""
        }
    }

    private fun decryptHex(hex: String): String {
        return try {
            val clean = hex.trim()
            if (clean.length % 2 != 0) return ""
            val bytes = ByteArray(clean.length / 2) { i ->
                ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
            }
            decryptAes(bytes)
        } catch (_: Exception) {
            ""
        }
    }

    // ----------------------------------------------------------------
    // LINK EXTRACTION - FIXED: Use direct ExtractorLink constructor
    // ----------------------------------------------------------------
    private fun addLink(rawUrl: String, seen: MutableSet<String>, callback: (ExtractorLink) -> Unit): Boolean {
        val url = rawUrl.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .trimEnd(')', ']', ',', ';', '"', '\'', '\\')

        if (url.isBlank() || !url.startsWith("http") || !seen.add(url)) return false

        val isM3u8 = url.contains(".m3u8", ignoreCase = true)
        val isMp4 = url.contains(".mp4", ignoreCase = true)
        if (!isM3u8 && !isMp4) return false

        // CORRECT CloudStream 3 ExtractorLink constructor
        callback(
            ExtractorLink(
                source = name,
                name = name,
                url = url,
                referer = "$mainUrl/",
                quality = Qualities.Unknown.value,
                isM3u8 = isM3u8,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl
                )
            )
        )
        return true
    }

    private fun extractLinks(text: String, seen: MutableSet<String>, callback: (ExtractorLink) -> Unit): Boolean {
        val normalized = text.replace("\\/", "/").replace("\\u002F", "/").replace("&amp;", "&")
        var found = false
        for (m in Regex("""https?://[^\s"'<>\u0000-\u001F\\]+""").findAll(normalized)) {
            val url = m.value.trimEnd(')', ']', ',', ';', '"', '\'', '\\')
            if ((url.contains(".m3u8", ignoreCase = true) || url.contains(".mp4", ignoreCase = true)) &&
                addLink(url, seen, callback)
            ) found = true
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
            "X-Requested-With" to "XMLHttpRequest"
        )

        val html = app.get(data, headers = headers).text
        extractLinks(html, seen, callback)

        for (unpacked in unpackAll(html)) {
            extractLinks(unpacked, seen, callback)

            // hex-encrypted URLs
            for (hm in Regex("""['"]([0-9a-fA-F]{64,})['"]""").findAll(unpacked)) {
                val dec = decryptHex(hm.groupValues[1])
                if (dec.startsWith("http")) addLink(dec, seen, callback)
            }

            // detail_play API
            for (am in Regex("""['"]?((?:https?://[^'"]*)?/comic/index/detail_play\?[^'"&\s\\]+)['"]?""").findAll(unpacked)) {
                val path = am.groupValues[1].replace("\\/", "/")
                val fullUrl = if (path.startsWith("http")) path else "$mainUrl$path"
                val videoKey = Regex("""video_key=([^&\s'"]+)""").find(fullUrl)?.groupValues?.get(1)
                    ?: Regex("""video_key[=:]\s*['"]?(\w+)""").find(unpacked)?.groupValues?.get(1)
                val hexPayload = Regex("""[uU]=([0-9a-fA-F]{32,})""").find(fullUrl)?.groupValues?.get(1)
                    ?: Regex("""['"]([0-9a-fA-F]{64,})['"]""").find(unpacked)?.groupValues?.get(1)

                val t = System.currentTimeMillis() / 1000
                val urls = mutableSetOf(fullUrl, "$fullUrl&t=$t")
                if (videoKey != null && hexPayload != null) {
                    urls += "$mainUrl/comic/index/detail_play?video_key=$videoKey&u=$hexPayload&t=$t"
                }
                if (videoKey != null) {
                    urls += "$mainUrl/comic/index/detail_play?video_key=$videoKey&t=$t"
                }
                for (u in urls) tryFetch(u, apiHeaders, seen, callback)
            }
        }

        val videoKey = Regex("""video_key[='":\s]+(\w+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""[?&]video_key=([^&"'\s]+)""").find(data)?.groupValues?.get(1)
            ?: Regex("""/detail[^/]*/(\d+)""").find(data)?.groupValues?.get(1)

        if (!videoKey.isNullOrBlank()) {
            val t = System.currentTimeMillis() / 1000
            listOf(
                "$mainUrl/comic/index/detail_play?video_key=$videoKey&t=$t",
                "$mainUrl/api/comic/video/detail?video_key=$videoKey",
                "$mainUrl/api/video/detail?video_key=$videoKey"
            ).forEach { tryFetch(it, apiHeaders, seen, callback) }
        }

        return seen.isNotEmpty()
    }

    private suspend fun tryFetch(url: String, hdrs: Map<String, String>, seen: MutableSet<String>, callback: (ExtractorLink) -> Unit) {
        try {
            val resp = app.get(url, headers = hdrs).text
            extractLinks(resp, seen, callback)
            for (m in Regex("""[A-Za-z0-9+/]{40,}={0,2}""").findAll(resp)) {
                val dec = decryptBase64(m.value)
                if (dec.isNotBlank()) extractLinks(dec, seen, callback)
            }
            for (m in Regex("""[0-9a-fA-F]{64,}""").findAll(resp)) {
                val dec = decryptHex(m.value)
                if (dec.startsWith("http")) addLink(dec, seen, callback)
            }
            for (script in unpackAll(resp)) extractLinks(script, seen, callback)
        } catch (_: Exception) {}
    }
}
