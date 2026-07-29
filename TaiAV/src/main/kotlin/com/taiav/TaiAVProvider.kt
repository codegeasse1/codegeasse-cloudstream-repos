package com.taiav

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class TaiAVProvider : MainAPI() {
    override var mainUrl = "https://m.taiav.com"
    override var name = "TaiAV"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Others)

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

    override val mainPage = mainPageOf(
        "$mainUrl/en" to "Popular Videos",
        "$mainUrl/cn" to "Chinese Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data.replace(".html", "/page/$page.html")
        val document = app.get(url).document

        val homeItems = document.select("div.movie-card").mapNotNull { element ->
            element.toSearchResultAsync()
        }
        return newHomePageResponse(request.name, homeItems)
    }

    private suspend fun Element.toSearchResultAsync(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        
        val img = this.selectFirst("img")
        val rawTitle = img?.attr("alt")?.ifBlank { aTag.text() }?.trim() ?: ""
        val title = translateText(rawTitle, true) ?: rawTitle

        val posterUrl = fixUrlNull(img?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val chineseQuery = translateText(query, false) ?: query
        val url = "$mainUrl/index.php/vod/search.html?wd=${URLEncoder.encode(chineseQuery, "UTF-8")}"
        val document = app.get(url).document
        
        return document.select("div.movie-card").mapNotNull { element ->
            element.toSearchResultAsync()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1.uk-h4.uk-text-break")?.text() ?: "Video"
        val title = translateText(rawTitle, true) ?: rawTitle

        val posterUrl = fixUrlNull(document.selectFirst("div.player-poster")?.attr("data-poster"))

        val tagElements = document.select(".tags a")
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
            val jsonRegex = Regex("""player_aaaa\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            val match = jsonRegex.find(pageHtml)
            if (match != null) {
                val jsonString = match.groupValues[1]
                val encryptMatch = Regex(""""encrypt"\s*:\s*(\d+)""").find(jsonString)
                val urlMatch = Regex(""""url"\s*:\s*"([^"]+)"""").find(jsonString)

                if (urlMatch != null) {
                    val encodedUrl = urlMatch.groupValues[1]
                    val encryptType = encryptMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

                    val realUrl = when (encryptType) {
                        1 -> String(Base64.decode(encodedUrl, Base64.DEFAULT), Charsets.UTF_8)
                        2 -> {
                            val base64Decoded = String(Base64.decode(encodedUrl, Base64.DEFAULT), Charsets.UTF_8)
                            URLDecoder.decode(base64Decoded, "UTF-8")
                        }
                        else -> encodedUrl
                    }

                    if (realUrl.contains(".m3u8")) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name Player",
                                url = realUrl,
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

        // Fallback 1: m3u8
        if (!found) {
            val cdnRegex = Regex("""https?:\\?/\\?/[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
            cdnRegex.findAll(pageHtml).forEach { match ->
                val cleanUrl = match.value.replace("\\/", "/").replace("&amp;", "&")
                if (cleanUrl.isNotBlank()) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name Server (m3u8)",
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

        // Fallback 2: .ts to .m3u8 conversion
        if (!found) {
            val tsRegex = Regex("""https?:\\?/\\?/[^\s"'<>]+?\.ts[^\s"'<>]*""")
            tsRegex.findAll(pageHtml).forEach { match ->
                var cleanUrl = match.value.replace("\\/", "/").replace("&amp;", "&")
                if (cleanUrl.isNotBlank()) {
                    val m3u8Url = cleanUrl.replace(Regex("\\.ts.*"), ".m3u8")
                    if (m3u8Url != cleanUrl) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name CDN (m3u8 converted)",
                                url = m3u8Url,
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
        }

        return found
    }
}
