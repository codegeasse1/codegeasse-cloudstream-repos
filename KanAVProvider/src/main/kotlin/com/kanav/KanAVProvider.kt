package com.kanav

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class KanAVProvider : MainAPI() {
    override var mainUrl = "https://kanav.ad"
    override var name = "KanAV"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Others)

    // ---------------------------------------------------------------
    // GOOGLE TRANSLATE HELPER
    // ---------------------------------------------------------------
    private suspend fun translateText(text: String?, toEnglish: Boolean = true): String? {
        if (text.isNullOrBlank()) return text
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val targetLang = if (toEnglish) "en" else "zh-CN"
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encodedText"
            
            val response = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0")).text

            val matches = Regex("""\["([^"\\]*(?:\\.[^"\\]*)*)","[^"]*"""").findAll(response)
            val translated = matches.map { 
                it.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n") 
            }.joinToString("")

            if (translated.isNotBlank()) translated else text
        } catch (e: Exception) {
            text
        }
    }

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/index.php/vod/type/id/1.html" to "Recent Updates"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data.replace(".html", "/page/$page.html")
        val document = app.get(url).document

        val homeItems = document.select(".video-item").mapNotNull { element ->
            element.toSearchResultAsync()
        }
        
        return newHomePageResponse(request.name, homeItems)
    }

    // ---------------------------------------------------------------
    // ITEM PARSING
    // ---------------------------------------------------------------
    private suspend fun Element.toSearchResultAsync(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        
        val img = this.selectFirst("img")
        val rawTitle = img?.attr("alt")?.ifBlank { this.selectFirst(".entry-title")?.text() }?.trim() ?: ""
        val title = translateText(rawTitle, true) ?: rawTitle

        val rawPoster = img?.attr("data-original")?.ifBlank { img.attr("src") }
        val posterUrl = fixUrlNull(rawPoster)

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val chineseQuery = translateText(query, false) ?: query
        val url = "$mainUrl/index.php/vod/search.html?wd=${URLEncoder.encode(chineseQuery, "UTF-8")}"
        val document = app.get(url).document
        
        return document.select(".video-item").mapNotNull { element ->
            element.toSearchResultAsync()
        }
    }

    // ---------------------------------------------------------------
    // LOAD (Detail Page)
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("title")?.text()?.substringBefore("-")?.trim() ?: "Video"
        val title = translateText(rawTitle, true) ?: rawTitle

        val posterElement = document.selectFirst("img.countext-img, .video-box-ather img")
        val posterUrl = fixUrlNull(posterElement?.attr("data-original")?.ifBlank { posterElement.attr("src") })

        val tagElements = document.select(".video-countext-categories a, .video-countext-tags a").filter { 
            it.attr("href") != "#" 
        }
        val rawTags = tagElements.map { it.text().trim() }.filter { it.isNotBlank() }
        
        val tagsList = if (rawTags.isNotEmpty()) {
            val combinedTags = rawTags.joinToString(" ~ ")
            val translatedCombinedTags = translateText(combinedTags, true) ?: combinedTags
            translatedCombinedTags.split(" ~ ").map { it.trim() }
        } else {
            emptyList()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = title
            this.tags = tagsList
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (MacCMS player_aaaa JSON Decoder)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val document = app.get(data).document
        val pageHtml = document.html()

        try {
            // 1. Extract the hidden player_aaaa JSON configuration object from the page script
            val playerJsonRegex = Regex("""var\s+player_aaaa\s*=\s*(\\{.*?\\}|\\\{.*?\\\})""", RegexOption.DOT_MATCHES_ALL)
            val match = playerJsonRegex.find(pageHtml) ?: Regex("""player_aaaa\s*=\s*(\{.*?\});""").find(pageHtml)
            
            if (match != null) {
                val jsonString = match.groupValues[1]
                // Grab the "url" property inside the JSON
                val urlMatch = Regex(""""url"\s*:\s*"([^"]+)"""").find(jsonString)
                if (urlMatch != null) {
                    val encodedUrl = urlMatch.groupValues[1]
                    // MacCMS urls are typically URL-encoded twice or Base64 encoded
                    val decodedUrl = URLDecoder.decode(URLDecoder.decode(encodedUrl, "UTF-8"), "UTF-8")
                    
                    if (decodedUrl.contains(".m3u8")) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name Player",
                                url = decodedUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fallback Regex for direct .m3u8 strings found anywhere in the HTML
        if (!found) {
            val cdnRegex = Regex("""https?:\\?/\\?/[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
            cdnRegex.findAll(pageHtml).forEach { match ->
                val cleanUrl = match.value.replace("\\/", "/").replace("&amp;", "&")
                if (cleanUrl.isNotBlank()) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name Server",
                            url = cleanUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                }
            }
        }

        return found
    }
}
