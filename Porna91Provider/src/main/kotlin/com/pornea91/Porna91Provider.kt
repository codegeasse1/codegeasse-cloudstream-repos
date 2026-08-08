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

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
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

    // ================================================================
    // AES IMAGE DECRYPTION
    // ================================================================
    private suspend fun decryptImageUrl(url: String): String? {
        if (url.isBlank() || url.startsWith("data:")) return url
        return try {
            val response = app.get(url, headers = mapOf("Referer" to "$mainUrl/"))
            val bytes = response.okhttpResponse.body?.bytes() ?: return null

            if (bytes.size > 2 && ((bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8 ||
                        (bytes[0].toInt() and 0xFF) == 0x89 && bytes[1].toInt() == 0x50)) {
                return url
            }

            val key = SecretKeySpec("f5d965df75336270".toByteArray(), "AES")
            val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray())
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val decrypted = cipher.doFinal(bytes)
            val ext = url.substringAfterLast(".", "jpg")
            "data:image/$ext;base64," + Base64.encodeToString(decrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            url
        }
    }

    // ================================================================
    // BASIC FUNCTIONS (MainPage, Search, Load)
    // ================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}&page=$page"
        val document = app.get(url, headers = headers).document
        
        val items = mutableListOf<SearchResponse>()
        val elements = document.select(".video-items .video-item, ul.video-items > li.video-item")
        
        for (el in elements) {
            val res = el.toSearchResultAsync()
            if (res != null) {
                items.add(res)
            }
        }
        
        return newHomePageResponse(request.name, items)
    }

    private suspend fun Element.toSearchResultAsync(): SearchResponse? {
        val link = selectFirst("a[href*=/detail], a[href*=/avdetail]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = selectFirst("img")?.attr("alt")?.trim()
            ?: selectFirst(".title, .line-clamp-2, h3, h4")?.text()?.trim() ?: "Untitled"

        val poster = selectFirst("img")?.attr("data-src")?.ifBlank { null }
            ?: Regex("""loadBannerDirect\s*\(\s*['"]([^'"]+)['"]""").find(outerHtml())?.groupValues?.get(1)

        var finalPosterUrl = poster
        if (poster != null) {
            finalPosterUrl = decryptImageUrl(poster) ?: poster
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = finalPosterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResponse>()
        for (p in 1..2) {
            val url = if (p == 1) "$mainUrl/comic/index/search?keyword=$encoded"
            else "$mainUrl/comic/index/search?keyword=$encoded&page=$p"
            val doc = app.get(url, headers = headers).document
            
            val items = mutableListOf<SearchResponse>()
            val elements = doc.select(".video-items .video-item")
            for (el in elements) {
                val res = el.toSearchResultAsync()
                if (res != null) {
                    items.add(res)
                }
            }
            
            if (items.isEmpty()) break
            results.addAll(items)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: doc.selectFirst("h1, h2")?.text()?.trim() ?: "Video"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: Regex("""loadBannerDirect\s*\(\s*['"]([^'"]+)['"]""").find(doc.outerHtml())?.groupValues?.get(1)

        var finalPosterUrl = poster
        if (poster != null) {
            finalPosterUrl = decryptImageUrl(poster) ?: poster
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = finalPosterUrl
            this.plot = title
        }
    }

    // ================================================================
    // UNPACKER (improved for your site)
    // ================================================================
    private fun unpackAll(html: String): List<String> {
        val list = mutableListOf<String>()
        val regex = Regex("""eval\(function\(p,a,c,k,e,d\).*?\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
        for (match in regex.findAll(html)) {
            val p = match.groupValues[1]
            val a = match.groupValues[2].toIntOrNull() ?: 36
            val c = match.groupValues[3].toIntOrNull() ?: 0
            val k = match.groupValues[4].split("|")
            list.add(unpackTuple(p, a, c, k))
        }
        return list
    }

    private fun unpackTuple(p: String, a: Int, c: Int, k: List<String>): String {
        val dict = mutableMapOf<String, String>()
        for (i in 0 until c) {
            k.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                dict[i.toString(36)] = it
            }
        }
        return Regex("""\b\w+\b""").replace(p) { dict[it.value] ?: it.value }
    }

    // ================================================================
    // AES DECRYPTORS
    // ================================================================
    private fun decryptBase64Aes(b64: String): String = try {
        val clean = b64.trim()
        val key = SecretKeySpec("f5d965df75336270".toByteArray(Charsets.UTF_8), "AES")
        val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        String(cipher.doFinal(Base64.decode(clean, Base64.DEFAULT)), Charsets.UTF_8)
    } catch (e: Exception) { "" }

    private fun decryptHexAes(hex: String): String = try {
        val bytes = ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        val key = SecretKeySpec("f5d965df75336270".toByteArray(Charsets.UTF_8), "AES")
        val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        String(cipher.doFinal(bytes), Charsets.UTF_8).trim()
    } catch (e: Exception) { "" }

    // ================================================================
    // LINK HELPERS
    // ================================================================
    private suspend fun addLink(
        rawUrl: String,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var url = rawUrl.trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .trimEnd(')', ']', ',', ';', '"', '\'')

        if (url.isBlank() || !url.startsWith("http") || !seen.add(url)) return false

        val isM3u8 = url.contains(".m3u8", ignoreCase = true)
        if (!isM3u8 && !url.contains(".mp4", ignoreCase = true)) return false

        callback(
            newExtractorLink(
                source = name,
                name = if (isM3u8) "$name (M3U8)" else name,
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

    private suspend fun extractLinks(
        text: String,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalized = text.replace("\\/", "/").replace("\\u002F", "/").replace("&amp;", "&")
        var found = false

        val urlRegex = Regex("""https?://[^\s"'\\<>]+?\.(?:m3u8|mp4)[^\s"'\\<>]*""", RegexOption.IGNORE_CASE)
        for (match in urlRegex.findAll(normalized)) {
            if (addLink(match.value, seen, callback)) found = true
        }
        return found
    }

    // ================================================================
    // MAIN LINK LOADER
    // ================================================================
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

        // Scan everything
        extractLinks(html, seen, callback)

        for (unpacked in unpackAll(html)) {
            extractLinks(unpacked, seen, callback)

            // Try AES decryption on packed content
            for (b64 in Regex("""[A-Za-z0-9+/=]{60,}""").findAll(unpacked)) {
                val dec = decryptBase64Aes(b64.value)
                if (dec.isNotBlank()) extractLinks(dec, seen, callback)
            }
            for (hex in Regex("""[0-9a-fA-F]{64,}""").findAll(unpacked)) {
                val dec = decryptHexAes(hex.value)
                if (dec.startsWith("http")) addLink(dec, seen, callback)
            }

            // Look for detail_play API call in unpacked script
            val detailPlayUrl = Regex("""['"]((?:https?://[^'"]*)?/comic/index/detail_play\?[^'"]+)['"]""")
                .find(unpacked)?.groupValues?.get(1)?.replace("\\/", "/")

            if (detailPlayUrl != null) {
                val fullApiUrl = if (detailPlayUrl.startsWith("http")) detailPlayUrl
                else "$mainUrl$detailPlayUrl"

                val videoKey = Regex("""video_key[=:]\s*['"]?(\w+)['"]?""").find(unpacked)?.groupValues?.get(1)
                val hexPayload = Regex("""['"]([0-9a-fA-F]{32,})['"]""").find(unpacked)?.groupValues?.get(1)

                val t = System.currentTimeMillis() / 1000
                val urlsToTry = mutableListOf<String>()

                urlsToTry.add(fullApiUrl)
                if (!fullApiUrl.contains("&t=")) urlsToTry.add("$fullApiUrl&t=$t")

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
                        if (extractLinks(resp, seen, callback)) return true

                        for (b64Match in Regex("""[A-Za-z0-9+/]{40,}={0,2}""").findAll(resp)) {
                            val dec = decryptVideoPayload(b64Match.value)
                            if (dec.isNotBlank() && extractLinks(dec, seen, callback)) return true
                        }

                        for (hexMatch in Regex("""[0-9a-fA-F]{64,}""").findAll(resp)) {
                            val dec = decryptHexVideoUrl(hexMatch.value)
                            if (dec.startsWith("http") && addLink(dec, seen, callback)) return true
                        }
                    } catch (e: Exception) { /* continue */ }
                }
            }
        }

        // Brute force detail_play endpoint
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
                tryFetch(endpoint, apiHeaders, seen, callback)
            }
        }

        // Final fallback
        try {
            val mobileHtml = app.get(
                data, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
                    "Referer" to "$mainUrl/"
                )
            ).text
            extractLinks(mobileHtml, seen, callback)
        } catch (e: Exception) { /* ignore */ }

        return seen.isNotEmpty()
    }

    private suspend fun tryFetch(
        url: String, 
        hdrs: Map<String, String>, 
        seen: MutableSet<String>, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val resp = app.get(url, headers = hdrs).text
            extractLinks(resp, seen, callback)
            for (m in Regex("""[A-Za-z0-9+/]{40,}={0,2}""").findAll(resp)) {
                val dec = decryptVideoPayload(m.value)
                if (dec.isNotBlank()) extractLinks(dec, seen, callback)
            }
            for (m in Regex("""[0-9a-fA-F]{64,}""").findAll(resp)) {
                val dec = decryptHexAes(m.value)
                if (dec.startsWith("http")) addLink(dec, seen, callback)
            }
            for (script in unpackAll(resp)) extractLinks(script, seen, callback)
        } catch (_: Exception) {}
    }
}
