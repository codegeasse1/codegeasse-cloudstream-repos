package com.pornea91

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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

            if (cipherBytes.size > 4 && 
                ((cipherBytes[0].toInt() and 0xFF) == 0xFF && (cipherBytes[1].toInt() and 0xFF) == 0xD8) || 
                ((cipherBytes[0].toInt() and 0xFF) == 0x89 && cipherBytes[1].toInt() == 0x50)
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
        val elements = document.select(".video-items .video-item, ul.video-items > li.video-item")
        
        for (el in elements) {
            val res = el.toSearchResultAsync()
            if (res != null) items.add(res)
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
                        it.attr("data-src").ifBlank { 
                            it.attr("data-original").ifBlank { it.attr("src") } 
                        } 
                    }
                }
            }
        }
        
        val rawPosterUrl = scriptImgMatch ?: fallbackImg
        var finalPosterUrl = rawPosterUrl
        
        if (rawPosterUrl != null) {
            finalPosterUrl = decryptImageUrl(rawPosterUrl) ?: rawPosterUrl
        }

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
            val elements = document.select(".video-items .video-item")
            
            val items = mutableListOf<SearchResponse>()
            for (el in elements) {
                val res = el.toSearchResultAsync()
                if (res != null) items.add(res)
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

        var finalPoster = poster
        if (poster != null) {
            finalPoster = decryptImageUrl(poster) ?: poster
        }
        
        val tags = document.select("a[href*=/search?keyword=]").map { it.text().trim() }.filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = finalPoster
            this.backgroundPosterUrl = finalPoster
            this.plot = title
            this.tags = tags
        }
    }

    // ---------------------------------------------------------------
    // JS UNPACKER 
    // ---------------------------------------------------------------
    private fun getUnpacked(packed: String): String {
        return try {
            val pRegex = Regex("""(?s)\('(.*?)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""").find(packed) ?: return packed
            val p = pRegex.groupValues[1]
            val a = pRegex.groupValues[2].toIntOrNull() ?: 10
            val c = pRegex.groupValues[3].toIntOrNull() ?: 10
            val k = pRegex.groupValues[4].split("|")

            fun e(c: Int): String {
                val first = if (c < a) "" else e(c / a)
                val m = c % a
                val second = if (m > 35) (m + 29).toChar().toString() else m.toString(36)
                return first + second
            }

            val dict = mutableMapOf<String, String>()
            for (i in 0 until c) {
                val word = k.getOrNull(i)
                if (!word.isNullOrBlank()) {
                    dict[e(i)] = word
                }
            }

            Regex("""\b\w+\b""").replace(p) { match ->
                dict[match.value] ?: match.value
            }
        } catch (e: Exception) {
            packed
        }
    }

    // ---------------------------------------------------------------
    // AES VIDEO PAYLOAD DECRYPTION 
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

    // ---------------------------------------------------------------
    // SAFE CLASS-LEVEL EXTRACTORS (Bypasses Coroutine Compiler Bugs)
    // ---------------------------------------------------------------
    private fun extractAndAddLinks(text: String, mappedUrls: MutableSet<String>, callback: (ExtractorLink) -> Unit): Boolean {
        var localFound = false
        val cdnRegex = Regex("""(https?://[^\s"'\\]+?\.(?:m3u8|mp4)[^\s"'\\]*)""")
        val unescaped = text.replace("\\/", "/").replace("\\u002F", "/").replace("\\u0026", "&")
        
        for (match in cdnRegex.findAll(unescaped)) {
            var cleanUrl = match.groupValues[1].replace("&amp;", "&")
            
            if (cleanUrl.contains("url=http")) {
                cleanUrl = cleanUrl.substringAfter("url=").substringBefore("&")
                try { cleanUrl = java.net.URLDecoder.decode(cleanUrl, "UTF-8") } catch(e: Exception){}
            }

            if (cleanUrl.isNotBlank() && mappedUrls.add(cleanUrl)) {
                val isM3u8 = cleanUrl.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Server",
                        url = cleanUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                localFound = true
            }
        }
        return localFound
    }

    private fun tryDecryptAndExtract(text: String, mappedUrls: MutableSet<String>, callback: (ExtractorLink) -> Unit): Boolean {
        var localFound = extractAndAddLinks(text, mappedUrls, callback)
        if (localFound) return true

        val b64Regex = Regex("""[A-Za-z0-9+/=]{40,}""")
        for (match in b64Regex.findAll(text)) {
            val candidate = match.value.trim()
            val decrypted = decryptVideoPayload(candidate)
            if (decrypted.isNotBlank()) {
                if (extractAndAddLinks(decrypted, mappedUrls, callback)) {
                    localFound = true
                    break
                }
            }
        }
        return localFound
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

        // 1. Scan the main HTML first
        found = tryDecryptAndExtract(html, mappedUrls, callback)
        if (found) return true

        // 2. Unpack Javascript Eval Obfuscation
        val evalRegex = Regex("""(?s)eval\s*\(\s*function\s*\(p,a,c,k,e,d\).*?\.split\('\|'\)\)\)""")
        for (match in evalRegex.findAll(html)) {
            val unpackedScript = getUnpacked(match.value)
            
            val scriptBaseMatch = Regex("""src=\\?['"]([^"'\\]+u=)""").find(unpackedScript)
            val uValueMatch = Regex("""encodeURIComponent\(\s*['"]([^'"]+)['"]\s*\)""").find(unpackedScript)

            if (scriptBaseMatch != null && uValueMatch != null) {
                val t = System.currentTimeMillis() / 1000 / 2100
                val targetUrl = mainUrl + scriptBaseMatch.groupValues[1].replace("\\/", "/") + URLEncoder.encode(uValueMatch.groupValues[1], "UTF-8") + "&t=$t"
                
                try {
                    val scriptRes = app.get(targetUrl, headers = headers).text
                    found = tryDecryptAndExtract(scriptRes, mappedUrls, callback)
                    if (found) return true
                } catch (e: Exception) {}
            }

            val iframeMatch = Regex("""<iframe[^>]+src=\\?['"]([^"'\\]+)""").find(unpackedScript)
            if (iframeMatch != null) {
                try {
                    val targetUrl = fixUrl(iframeMatch.groupValues[1].replace("\\/", "/"))
                    val iframeRes = app.get(targetUrl, headers = headers).text
                    found = tryDecryptAndExtract(iframeRes, mappedUrls, callback)
                    if (found) return true
                } catch (e: Exception) {}
            }
        }

        // 3. Fetch and decrypt external .txt payload files
        val txtRegex = Regex("""(https?://[^\s"'<>]+\.txt[^\s"'<>]*)""")
        for (match in txtRegex.findAll(html)) {
            try {
                val txtUrl = match.groupValues[1].replace("\\/", "/")
                val txtContent = app.get(txtUrl, headers = headers).text
                
                val decrypted = decryptVideoPayload(txtContent)
                if (decrypted.isNotBlank()) {
                    found = extractAndAddLinks(decrypted, mappedUrls, callback)
                } else {
                    found = extractAndAddLinks(txtContent, mappedUrls, callback)
                }
                if (found) return true
            } catch (e: Exception) {}
        }

        // 4. API Endpoints Brute-Force Fallback
        val vidIdMatch = Regex("""video_key=([^&"']+)""").find(data) ?: Regex("""/detail/(\d+)""").find(data)
        val vidId = vidIdMatch?.groupValues?.get(1) ?: data.substringAfterLast("/").substringBefore("?").substringBefore(".")
        
        if (vidId.isNotBlank()) {
            val apiEndpoints = listOf(
                "/api/comic/video/detail?video_key=$vidId",
                "/api/video/detail?video_key=$vidId",
                "/api/video/get_video?video_key=$vidId",
                "/api/v1/video/detail?video_key=$vidId",
                "/comic/index/detail_play?video_key=$vidId"
            )
            
            for (api in apiEndpoints) {
                try {
                    val apiRes = app.get(fixUrl(api), headers = headers).text
                    found = tryDecryptAndExtract(apiRes, mappedUrls, callback)
                    if (found) return true
                } catch (e: Exception) {}
            }
        }

        return found
    }
}
