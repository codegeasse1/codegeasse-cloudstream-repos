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
    // SEARCH (automatically translates English → Chinese)
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        // Convert English query to Chinese so that the site returns correct results
        val chineseQuery = translateText(query, false) ?: query
        val url = "$mainUrl/index.php/vod/search.html?wd=${URLEncoder.encode(chineseQuery, "UTF-8")}"
        val document = app.get(url).document

        return document.select(".video-item").mapNotNull { element ->
            element.toSearchResultAsync()
        }
    }

    // ---------------------------------------------------------------
    // LOAD (Detail Page) – now shows English tags that are clickable
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("title")?.text()?.substringBefore("-")?.trim() ?: "Video"
        val title = translateText(rawTitle, true) ?: rawTitle

        val posterElement = document.selectFirst("img.countext-img, .video-box-ather img")
        val posterUrl = fixUrlNull(posterElement?.attr("data-original")?.ifBlank { posterElement.attr("src") })

        // Collect all clickable tag links (categories + tags + actors)
        val tagElements = document.select(
            ".video-countext-categories a, .video-countext-tags a"
        ).filter {
            it.attr("href") != "#" // exclude dummy date link
        }

        // Translate the combined Chinese tags to English for display
        val rawTags = tagElements.map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val tags = if (rawTags.isNotEmpty()) {
            // Join, translate as one batch, then split back (avoids repeated API calls)
            val joined = rawTags.joinToString(" ~ ")
            val translated = translateText(joined, true) ?: joined
            translated.split(" ~ ").map { it.trim() }
        } else {
            emptyList()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = title
            this.tags = tags
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (MacCMS player_aaaa Decoder – FIXED)
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
            // Extract player_aaaa JSON object
            val jsonRegex = Regex("""player_aaaa\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            val match = jsonRegex.find(pageHtml)
            if (match != null) {
                val jsonString = match.groupValues[1]
                // Get encrypt type and encoded URL
                val encryptMatch = Regex(""""encrypt"\s*:\s*(\d+)""").find(jsonString)
                val urlMatch = Regex(""""url"\s*:\s*"([^"]+)"""").find(jsonString)

                if (urlMatch != null) {
                    val encodedUrl = urlMatch.groupValues[1]
                    val encryptType = encryptMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

                    val realUrl = when (encryptType) {
                        1 -> {
                            // encrypt=1: only Base64 decode
                            String(Base64.decode(encodedUrl, Base64.DEFAULT), Charsets.UTF_8)
                        }
                        2 -> {
                            // encrypt=2: Base64 decode → then URL decode
                            val base64Decoded = String(Base64.decode(encodedUrl, Base64.DEFAULT), Charsets.UTF_8)
                            URLDecoder.decode(base64Decoded, "UTF-8")
                        }
                        else -> encodedUrl // fallback
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

        // Fallback: search for direct .m3u8 links anywhere in the HTML
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
