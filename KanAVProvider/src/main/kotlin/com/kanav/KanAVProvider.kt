package com.kanav

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KanAVProvider : MainAPI() {
    override var mainUrl = "https://kanav.ad"
    override var name = "KanAV"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    // Using NSFW or Others type since this is an adult video site
    override val supportedTypes = setOf(TvType.NSFW, TvType.Others)

    // ---------------------------------------------------------------
    // YOUR VERIFIED GOOGLE TRANSLATE HELPER
    // ---------------------------------------------------------------
    private suspend fun translateToEnglish(text: String?): String? {
        if (text.isNullOrBlank()) return text
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&q=$encodedText"
            val response = app.get(url).text

            // Extract all translated sentence segments from the Google Translate JSON array
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
        val title = translateToEnglish(rawTitle) ?: rawTitle

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
        // Standard MacCMS search URL structure
        val url = "$mainUrl/index.php/vod/search.html?wd=${URLEncoder.encode(query, "UTF-8")}"
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
        val title = translateToEnglish(rawTitle) ?: rawTitle

        // Based on screenshot 263, the poster is inside an img with class "countext-img"
        val posterElement = document.selectFirst("img.countext-img, .video-box-ather img")
        val posterUrl = fixUrlNull(posterElement?.attr("data-original")?.ifBlank { posterElement.attr("src") })

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = title // Site rarely has descriptions, title acts as plot
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (Extracts from standard HTML and MacCMS Iframes)
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

        // Regex to catch native .m3u8 files in the source or MacCMS JSON configuration
        val cdnRegex = Regex("""https?:\\?/\\?/[^\s"'<>]+?\.m3u8[^\s"'<>]*""")

        // 1. Search the main page HTML
        cdnRegex.findAll(pageHtml).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/").replace("&amp;", "&")
            if (cleanUrl.isNotBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name Server",
                        url = cleanUrl,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
                found = true
            }
        }

        // 2. Search inside embedded iframes (Based on screenshot 262 showing dplayer-dm.html)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                val iframeUrl = fixUrlNull(src)
                if (iframeUrl != null) {
                    try {
                        val iframeHtml = app.get(iframeUrl, headers = mapOf("Referer" to data)).text
                        cdnRegex.findAll(iframeHtml).forEach { match ->
                            val cleanUrl = match.value.replace("\\/", "/").replace("&amp;", "&")
                            if (cleanUrl.isNotBlank()) {
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name Iframe",
                                        url = cleanUrl,
                                        referer = iframeUrl,
                                        quality = Qualities.Unknown.value,
                                        type = ExtractorLinkType.M3U8
                                    )
                                )
                                found = true
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore dead iframes
                    }
                }
            }
        }

        return found
    }
}
