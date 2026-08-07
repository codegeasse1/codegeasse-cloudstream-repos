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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val docUrl = if (page == 1) request.data else "${request.data}&page=$page"
        val document = app.get(docUrl, headers = headers).document
        
        val items = document.select(".video-items .video-item, ul.video-items > li.video-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href*=/detail], a[href*=/avdetail], a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        val img = this.selectFirst("img")
        val title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".title, .line-clamp-2, .post-item-title, h3, h4")?.text()?.trim()
            ?: return null

        val rawPoster = img?.attr("data-src")?.ifBlank { img.attr("data-original") }?.ifBlank { img.attr("src") }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(rawPoster)
            this.posterHeaders = headers 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResponse>()
        
        for (page in 1..2) {
            val docUrl = if (page == 1) "$mainUrl/comic/index/search?keyword=$encodedQuery"
            else "$mainUrl/comic/index/search?keyword=$encodedQuery&page=$page"
            
            val document = app.get(docUrl, headers = headers).document
            val items = document.select(".video-items .video-item").mapNotNull { it.toSearchResult() }
            if (items.isEmpty()) break
            results.addAll(items)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        
        val title = document.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: document.selectFirst("h1, h2")?.text()?.trim() ?: "Video"

        var rawPoster = document.selectFirst("meta[property=og:image]")?.attr("content")
        if (rawPoster.isNullOrBlank()) {
            val img = document.selectFirst(".poster img, .video-cover img, .video-item img")
            rawPoster = img?.attr("data-src")?.ifBlank { img.attr("src") }
        }

        val tags = document.select("a[href*=/search?keyword=]").map { it.text().trim() }.filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(rawPoster)
            this.backgroundPosterUrl = fixUrlNull(rawPoster)
            this.posterHeaders = headers
            this.plot = title
            this.tags = tags
        }
    }

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val html = app.get(data, headers = headers).text
        val mappedUrls = mutableSetOf<String>()

        suspend fun addStream(streamUrl: String) {
            val cleanUrl = streamUrl.replace("\\/", "/").trim()
            if (!cleanUrl.startsWith("http")) return
            if (!cleanUrl.contains(".m3u8") && !cleanUrl.contains(".mp4")) return
            if (!mappedUrls.add(cleanUrl)) return

            val isM3u8 = cleanUrl.contains(".m3u8")
            callback.invoke(
                newExtractorLink(name, name + if (isM3u8) " HLS" else " MP4", cleanUrl, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        // Added the suspend keyword here to fix the compiler error
        suspend fun extractAndAddM3u8(text: String) {
            val unescaped = text.replace("\\/", "/").replace("\\u002F", "/").replace("\\u0026", "&")
            Regex("""(https?://[^\s"'\\]+\.(?:m3u8|mp4)[^\s"'\\]*)""").findAll(unescaped).forEach {
                addStream(it.groupValues[1])
            }
        }

        // 1. Scan Main HTML for exposed links
        extractAndAddM3u8(html)

        // 2. Scan APIs (with integrated AES Decryption)
        val vidIdMatch = Regex("""video_key=([^&]+)""").find(data) ?: Regex("""/detail/(\d+)""").find(data)
        val vidId = vidIdMatch?.groupValues?.get(1) ?: data.substringAfterLast("/").substringBefore("?").substringBefore(".")
        
        if (vidId.isNotBlank()) {
            val apiEndpoints = listOf(
                "/api/video/detail?video_key=$vidId",
                "/api/comic/video/detail?video_key=$vidId",
                "/api/video/get_video?video_key=$vidId",
                "/api/v1/video/detail?video_key=$vidId"
            )
            
            for (api in apiEndpoints) {
                try {
                    val apiRes = app.get(fixUrl(api), headers = headers).text
                    extractAndAddM3u8(apiRes)

                    if (!apiRes.trim().startsWith("{") && apiRes.length > 50 && apiRes.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
                        val decryptedHtml = decryptVideoPayload(apiRes.trim())
                        extractAndAddM3u8(decryptedHtml)
                    }

                    if (found) break
                } catch (e: Exception) {}
            }
        }

        // 3. Scan Embedded Iframes
        val document = Jsoup.parse(html)
        for (iframe in document.select("iframe")) {
            val src = fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") })
            if (src != null && src.startsWith("http")) {
                try {
                    val iframeHtml = app.get(src, headers = headers).text
                    extractAndAddM3u8(iframeHtml)
                } catch (e: Exception) {}
            }
        }

        return found
    }
}
