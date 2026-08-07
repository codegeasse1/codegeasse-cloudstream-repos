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
        
        // Flattened loop to fix the Kotlin coroutine compilation error
        for (el in elements) {
            val res = el.toSearchResultAsync()
            if (res != null) {
                items.add(res)
            }
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
                            it.attr("data-original").ifBlank { 
                                it.attr("src") 
                            } 
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
    // AES VIDEO PAYLOAD DECRYPTION 
    // ---------------------------------------------------------------
    private fun decryptVideoPayload(encryptedB64: String): String {
        return try {
            val key = SecretKeySpec("f5d965df75336270".toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            String(cipher.doFinal(Base64.decode(encryptedB64, Base64.DEFAULT)), Charsets.UTF_8)
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
        val mappedUrls = mutableSetOf<String>()

        val cdnRegex = Regex("""(https?://[^\s"'\\]+?\.(?:m3u8|mp4)[^\s"'\\]*)""")

        fun extractAndAdd(text: String) {
            val unescaped = text.replace("\\/", "/").replace("\\u002F", "/").replace("\\u0026", "&")
            
            cdnRegex.findAll(unescaped).forEach { match ->
                var cleanUrl = match.groupValues[1].replace("&amp;", "&")
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
                    found = true
                }
            }
        }

        // 1. Scan the main HTML first
        extractAndAdd(html)

        // 2. Scan APIs if it's hidden (with AES cracking built-in)
        if (!found) {
            val vidIdMatch = Regex("""video_key=([^&]+)""").find(data) ?: Regex("""/detail/(\d+)""").find(data)
            val vidId = vidIdMatch?.groupValues?.get(1) ?: data.substringAfterLast("/").substringBefore("?").substringBefore(".")
            
            if (vidId.isNotBlank()) {
                val apiEndpoints = listOf(
                    "/api/comic/video/detail?video_key=$vidId",
                    "/api/video/detail?video_key=$vidId",
                    "/api/video/get_video?video_key=$vidId",
                    "/api/v1/video/detail?video_key=$vidId"
                )
                
                for (api in apiEndpoints) {
                    try {
                        val apiHtml = app.get(fixUrl(api), headers = headers).text
                        extractAndAdd(apiHtml) // Try direct read first
                        
                        // Crack the AES Payload if it's returning the massive Base64 string
                        if (!found && apiHtml.isNotBlank() && !apiHtml.trim().startsWith("{") && !apiHtml.trim().startsWith("<")) {
                            val decrypted = decryptVideoPayload(apiHtml.trim())
                            if (decrypted.isNotBlank()) {
                                extractAndAdd(decrypted)
                            }
                        }
                        
                        if (found) break
                    } catch (e: Exception) {}
                }
            }
        }

        return found
    }
}
