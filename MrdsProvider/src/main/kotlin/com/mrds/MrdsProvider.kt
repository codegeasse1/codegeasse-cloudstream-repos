package com.mrds

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MrdsProvider : MainAPI() {
    override var mainUrl = "https://mrds.com"
    override var name = "MRDS"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Others)

    // ---------------------------------------------------------------
    // BULLETPROOF GOOGLE TRANSLATE HELPER
    // ---------------------------------------------------------------
    private suspend fun translateText(text: String?, targetLang: String): String? {
        if (text.isNullOrBlank()) return text
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encodedText"
            
            // Explicitly set a User-Agent so Google Translate doesn't block the request
            val response = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")).text
            
            // Clean Regex to reliably grab the first translated string
            val match = Regex("""\[\[\["([^"]+)"""").find(response)
            val translated = match?.groupValues?.get(1)
                ?.replace("\\n", "")
                ?.replace("\\\"", "\"")
                ?.trim()
            
            if (!translated.isNullOrBlank()) translated else text
        } catch (e: Exception) {
            text // Fallback to original text if offline or blocked
        }
    }

    // ---------------------------------------------------------------
    // NATIVE AES IMAGE DECRYPTION
    // ---------------------------------------------------------------
    private suspend fun decryptImageUrl(url: String): String? {
        return try {
            val response = app.get(url, headers = mapOf("Referer" to "$mainUrl/"))
            val cipherBytes = response.okhttpResponse.body?.bytes() ?: return null

            val key = SecretKeySpec("f5d965df75336270".toByteArray(), "AES")
            val iv = IvParameterSpec("97b60394abc2fbe1".toByteArray())

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val decryptedBytes = cipher.doFinal(cipherBytes)

            val ext = url.substringAfterLast(".", "jpeg").substringBefore("?")
            "data:image/$ext;base64," + Base64.encodeToString(decryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/category/trending/" to "Trending"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document

        val homeItems = document.select("article:has(.post-card) a").mapNotNull { element ->
            element.toSearchResultAsync()
        }
        
        return newHomePageResponse(request.name, homeItems)
    }

    // ---------------------------------------------------------------
    // SEARCH & HOMEPAGE ITEM PARSING
    // ---------------------------------------------------------------
    private suspend fun Element.toSearchResultAsync(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val rawTitle = this.selectFirst(".post-card-title")?.text()?.trim()
            ?: this.text().substringBefore(" • ").trim()
            
        // Auto-translate titles to English
        val title = translateText(rawTitle, "en") ?: rawTitle
        val cardHtml = this.outerHtml()

        val scriptImgMatch = Regex("""loadBannerDirect\s*\(\s*['"]([^'"]+)['"]""").find(cardHtml)?.groupValues?.get(1)
        val fallbackImg = this.selectFirst("img")?.let { 
            it.attr("z-image-loader-url").ifBlank { 
                it.attr("x-image-loader-url").ifBlank { 
                    it.attr("data-xkrkllgl").ifBlank { it.attr("src") } 
                } 
            } 
        }
        val rawPosterUrl = scriptImgMatch ?: fallbackImg

        var finalPosterUrl = rawPosterUrl
        if (rawPosterUrl != null && rawPosterUrl.contains("pic.xustgq.cn")) {
            finalPosterUrl = decryptImageUrl(rawPosterUrl) ?: rawPosterUrl
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = finalPosterUrl
        }
    }

    // ---------------------------------------------------------------
    // SEARCH (Auto Translation + Dual Query)
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // 1. Translate the English query to Chinese (zh-CN)
        val chineseQuery = translateText(query, "zh-CN") ?: query

        // 2. Search using the Chinese translated term (URL encoded safely)
        val encodedChinese = URLEncoder.encode(chineseQuery, "UTF-8")
        val chineseDoc = app.get("$mainUrl/?s=$encodedChinese").document
        
        val chineseResults = chineseDoc.select("article:has(.post-card) a").mapNotNull { element ->
            element.toSearchResultAsync()
        }
        results.addAll(chineseResults)

        // 3. Dual-Query: If the translation is different, search the exact English text too
        if (chineseQuery != query) {
            val encodedOrig = URLEncoder.encode(query, "UTF-8")
            val origDoc = app.get("$mainUrl/?s=$encodedOrig").document
            
            val origResults = origDoc.select("article:has(.post-card) a").mapNotNull { element ->
                element.toSearchResultAsync()
            }
            results.addAll(origResults)
        }

        // Return unique results (combines Chinese search hits and English search hits)
        return results.distinctBy { it.url }
    }

    // ---------------------------------------------------------------
    // LOAD (Detail Page)
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1, .post-title, title")?.text()?.substringBefore("-")?.trim() ?: "Video"
        val title = translateText(rawTitle, "en") ?: "Video"
        val pageHtml = document.outerHtml()

        val contentImg = document.selectFirst(".post-content img, article p img")
        var poster = contentImg?.let { 
            it.attr("z-image-loader-url").ifBlank { 
                it.attr("x-image-loader-url").ifBlank { 
                    it.attr("data-xkrkllgl").ifBlank { it.attr("src") } 
                } 
            } 
        }
        
        if (poster.isNullOrBlank() || poster.startsWith("data:")) {
            poster = Regex("""loadBannerDirect\s*\(\s*['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
        }

        if (poster.isNullOrBlank() || poster.startsWith("data:")) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        }

        if (poster != null && poster.contains("pic.xustgq.cn")) {
            poster = decryptImageUrl(poster) ?: poster
        }

        val rawSynopsis = document.selectFirst(".post-content p, article p")?.text()
        val synopsis = translateText(rawSynopsis, "en")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = synopsis
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
        val html = app.get(data).text

        val cdnRegex = Regex("""https?:\\?/\\?/[^\s"'<>]+?\.m3u8[^\s"'<>]*""")

        cdnRegex.findAll(html).forEach { match ->
            var cleanUrl = match.value.replace("\\/", "/")
            cleanUrl = cleanUrl.replace("&amp;", "&")

            if (cleanUrl.isNotBlank()) {
                callback(
                    newExtractorLink(
                        source = "MRDS Server",
                        name = "MRDS Server",
                        url = cleanUrl,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        return found
    }
}
