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
    // DEAN EDWARDS UNPACKER
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

    private fun decryptAesB64(encryptedB64: String): String {
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

    private fun decryptAesHex(hexStr: String): String {
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
        val document = Jsoup.parse(html)
        val mappedUrls = mutableSetOf<String>()
        val t = System.currentTimeMillis() / 1000 / 2100

        val apiHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest"
        )

        suspend fun emit(url: String, tag: String): Boolean {
            val u = url.replace("\\/", "/").replace("&amp;", "&").trim()
            if (u.isBlank() || !mappedUrls.add(u)) return false
            val isM3u8 = u.contains(".m3u8") || u.contains("m3u8?")
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name $tag",
                    url = u,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = videoHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        suspend fun scanText(text: String): Boolean {
            var ok = false
            val unescaped = text.replace("\\/", "/").replace("\\u002F", "/").replace("\\u0026", "&")
            for (m in Regex("""https?://[^\s"'\\]+?\.(?:m3u8|mp4)[^\s"'\\]*""").findAll(unescaped)) {
                var u = m.groupValues[0]
                if (u.contains("url=http")) {
                    u = u.substringAfter("url=").substringBefore("&")
                    try { u = java.net.URLDecoder.decode(u, "UTF-8") } catch (e: Exception) {}
                }
                if (emit(u, "CDN")) ok = true
            }
            if (!ok) {
                for (m in Regex("""[A-Za-z0-9+/=]{64,}""").findAll(text)) {
                    val dec = decryptAesB64(m.value)
                    if (dec.isNotBlank()) {
                        for (m2 in Regex("""https?://[^\s"'\\]+?\.(?:m3u8|mp4)[^\s"'\\]*""").findAll(dec.replace("\\/", "/"))) {
                            if (emit(m2.value, "Dec")) ok = true
                        }
                    }
                    if (ok) break
                }
            }
            return ok
        }

        suspend fun emitIfMedia(res: com.lagradost.nicehttp.NiceResponse): Boolean {
            val finalUrl = res.okhttpResponse.request.url.toString()
            return if (finalUrl.contains(".m3u8") || finalUrl.contains(".mp4")) emit(finalUrl, "Direct") else false
        }

        val scriptBodies = mutableListOf(html)
        for (el in document.select("script[src]")) {
            val src = fixUrlNull(el.attr("src")) ?: continue
            if (!src.startsWith("http")) continue
            try { scriptBodies.add(app.get(src, headers = apiHeaders).text) } catch (e: Exception) {}
        }
        for (el in document.select("iframe[src]")) {
            val src = fixUrlNull(el.attr("src")) ?: continue
            try { scriptBodies.add(app.get(src, headers = apiHeaders).text) } catch (e: Exception) {}
        }

        val tupleRegex = Regex("""\('((?:[^'\\]|\\.)*)',\s*(\d+),\s*(\d+),\s*'((?:[^'\\]|\\.)*)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
        val detailPlayRegex = Regex("""["']?(/comic/index/detail_play\?[^"'\s]+)["']?""")
        val hexRegex = Regex("""encodeURIComponent\(\s*['"]([0-9a-fA-F]{16,})['"]\s*\)""")
        val hexLooseRegex = Regex("""['"]([0-9a-fA-F]{64,})['"]""")

        val vidKey = Regex("""video_key=([A-Za-z0-9]+)""").find(data)?.groupValues?.get(1)
            ?: Regex("""video_key=([A-Za-z0-9]+)""").find(html)?.groupValues?.get(1)

        val detailPlayCandidates = linkedSetOf<String>()

        for (body in scriptBodies) {
            if (scanText(body)) return true

            for (tm in tupleRegex.findAll(body)) {
                val kParam = tm.groupValues[4].replace("\\'", "'").replace("\\\\", "\\")
                val unpacked = unpackTuple(tm.groupValues[1], tm.groupValues[2].toIntOrNull() ?: 62, tm.groupValues[3].toIntOrNull() ?: 0, kParam)
                
                if (scanText(unpacked)) return true

                val hex = hexRegex.find(unpacked)?.groupValues?.get(1) ?: hexLooseRegex.find(unpacked)?.groupValues?.get(1)
                if (hex != null) {
                    val dec = decryptAesHex(hex)
                    if (dec.startsWith("http") && emit(dec, "AES")) return true
                    if (scanText(dec)) return true
                }

                for (dp in detailPlayRegex.findAll(unpacked)) {
                    val u = dp.groupValues[1].replace("\\/", "/")
                    detailPlayCandidates.add(u)
                    detailPlayCandidates.add(u.replace(Regex("t=\\d+"), "t=$t"))
                    if (hex != null) {
                        detailPlayCandidates.add(u.replace(Regex("u=[^&]+"), "u=" + URLEncoder.encode(hex, "UTF-8")))
                    }
                }
            }

            for (dp in detailPlayRegex.findAll(body)) {
                val u = dp.groupValues[1].replace("\\/", "/")
                detailPlayCandidates.add(u)
                detailPlayCandidates.add(u.replace(Regex("t=\\d+"), "t=$t"))
            }
        }

        if (vidKey != null) {
            detailPlayCandidates.add("/comic/index/detail_play?video_key=$vidKey&t=$t")
            detailPlayCandidates.add("/comic/index/detail_play?video_key=$vidKey")
        }

        for (cand in detailPlayCandidates) {
            val full = if (cand.startsWith("http")) cand else mainUrl + cand
            try {
                val res = app.get(full, headers = apiHeaders)
                if (emitIfMedia(res)) return true
                if (scanText(res.text)) return true
            } catch (e: Exception) {}
        }

        try {
            val mobileUa = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
            val mobileHtml = app.get(data, headers = mapOf("User-Agent" to mobileUa, "Referer" to "$mainUrl/")).text
            if (scanText(mobileHtml)) return true
        } catch (e: Exception) {}

        return found
    }
}
