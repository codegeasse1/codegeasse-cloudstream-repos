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
            
            // Explicitly set User-Agent so Google doesn't block the request
            val response = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")).text
            
            var translated = ""
            // Safely extract all translated segments from the JSON array
            val regex = Regex("""\["([^"\\]*(?:\\.[^"\\]*)*)",""")
            val matches = regex.findAll(response.substringBefore("]],null,"))
            
            matches.forEach { match ->
                translated += match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\r", "")
            }
            
            if (translated.isNotBlank() && translated != "null") translated else text
        } catch (e: Exception) {
            text // Fallback to original text if offline or blocked
        }
    }

    // BATCH TRANSLATION: Translates a list of results in 1 single fast API call to prevent CloudStream timeouts
    private suspend fun translateList(items: List<SearchResponse>): List<SearchResponse> {
        if (items.isEmpty()) return items
        
        val combinedTitles = items.joinToString(" || ") { it.name }
        val translatedCombined = translateText(combinedTitles, "en") ?: combinedTitles
        val translatedTitles = translatedCombined.split(Regex("""\s*\|\|\s*"""))
        
        return items.mapIndexed { index, res ->
            val newTitle = translatedTitles.getOrNull(index)?.trim() ?: res.name
            newMovieSearchResponse(newTitle, res.url, TvType.Movie) {
                this.posterUrl = res.posterUrl
            }
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
            element.toSearchResult()
        }
        
        // Translate the homepage feed into English instantly
        return newHomePageResponse(request.name, translateList(homeItems))
    }

    // ---------------------------------------------------------------
    // ITEM PARSING
    // ---------------------------------------------------------------
    private suspend fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst(".post-card-title")?.text()?.trim() ?: this.text().substringBefore(" • ").trim()
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
    // SEARCH (Dual Query + Auto English/Chinese Translation)
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // 1. Translate the English query (e.g., "mengyao") to Chinese (e.g., "梦瑶")
        val chineseQuery = translateText(query, "zh-CN") ?: query

        // 2. Set up Dual-Search URLs
        val urlsToSearch = mutableSetOf<String>()
        urlsToSearch.add("$mainUrl/?s=${URLEncoder.encode(chineseQuery, "UTF-8")}")
        
        // If translation is different, search the exact English text too just in case
        if (chineseQuery != query) {
            urlsToSearch.add("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}")
        }

        // 3. Fetch search results
        urlsToSearch.forEach { url ->
            val doc = app.get(url).document
            doc.select("article:has(.post-card) a").forEach { element ->
                element.toSearchResult()?.let { results.add(it) }
            }
        }

        // Remove duplicates and apply Batch Translation so the UI stays English
        val uniqueResults = results.distinctBy { it.url }
        return translateList(uniqueResults)
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
