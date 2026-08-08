package com.mrds

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
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
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.Others)

    // ---------------------------------------------------------------
    // REMOTE TRANSLATION TOGGLE
    // ---------------------------------------------------------------
    private var cachedTranslateFlag: Boolean? = null

    private suspend fun isTranslationEnabled(): Boolean {
        cachedTranslateFlag?.let { return it }
        val flag = try {
            val json = app.get(
                "https://gist.githubusercontent.com/codegeasse1/02333c773cbd933b02e1779e6a1222fe/raw/config.json"
            ).text
            Regex(""""translate"\s*:\s*(true|false)""").find(json)
                ?.groupValues?.get(1)?.toBoolean() ?: true
        } catch (e: Exception) {
            true
        }
        cachedTranslateFlag = flag
        return flag
    }

    // ---------------------------------------------------------------
    // GOOGLE TRANSLATE HELPER
    // ---------------------------------------------------------------
    private suspend fun translateToEnglish(text: String?): String? {
        if (!isTranslationEnabled()) return text
        if (text.isNullOrBlank()) return text
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&q=$encodedText"
            val response = app.get(url).text

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
        "$mainUrl/category/mrds/" to "Daily Contest",
        "$mainUrl/category/ztds/" to "Theme Contest",
        "$mainUrl/category/rstt/" to "Hot Search",
        "$mainUrl/category/xazd/" to "Campus Students",
        "$mainUrl/category/blyp/" to "Must Watch",
        "$mainUrl/category/fctg/" to "Leaked Secrets",
        "$mainUrl/category/mhds/" to "Internet Celebrity",
        "$mainUrl/category/lqdp/" to "Bizarre",
        "$mainUrl/category/jdsj/" to "AV Movies",
        "$mainUrl/category/mxwh/" to "Celebrity Contest",
        "$mainUrl/category/smdh/" to "Anime & Manga",
        "$mainUrl/category/dypd/" to "Film & Anime",
        "$mainUrl/category/mtds/" to "Cosplay",
        "$mainUrl/category/ysds/" to "ASMR",
        "$mainUrl/category/czds/" to "Edging Challenge",
        "$mainUrl/category/hjds/" to "PMV Mix",
        "$mainUrl/category/tgds/" to "Original Submissions",
        "$mainUrl/category/omjp/" to "Western Premium",
        "$mainUrl/category/qwcs/" to "All Network",
        "$mainUrl/category/aijc/" to "AI Theater",
        "$mainUrl/category/sjbq/" to "World Cup Zone"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document

        val homeItems = document.select("article:not(.ad-item):has(.post-card) a").mapNotNull { element ->
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
        val title = translateToEnglish(rawTitle) ?: rawTitle
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
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val maxPages = 8

        suspend fun fetchPage(page: Int): List<SearchResponse> {
            val url = if (page == 1)
                "$mainUrl/search/$encodedQuery/"
            else
                "$mainUrl/search/$encodedQuery/$page/"
            val document = app.get(url).document
            return document.select("article:not(.ad-item):has(.post-card) a").mapNotNull { element ->
                element.toSearchResultAsync()
            }
        }

        val results = mutableListOf<SearchResponse>()
        for (page in 1..maxPages) {
            val pageResults = fetchPage(page)
            if (pageResults.isEmpty()) break
            results.addAll(pageResults)
        }
        return results
    }

    // ---------------------------------------------------------------
    // LOAD (Detail Page) - Multi-Part Archive Button Support
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1, .post-title, title")?.text()?.substringBefore("-")?.trim() ?: "Video"
        val title = translateToEnglish(rawTitle) ?: "Video"
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
        val synopsis = translateToEnglish(rawSynopsis)

        // Check for archive / multi-part buttons pointing to sub-pages
        val archiveLinks = document.select(".post-content a.btn.btn-primary[href*=/archives/], .post-content a[href*=/archives/]")
        if (archiveLinks.isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            
            // Include the main page as Part 1 if it has a video, or list the archive links cleanly
            episodes.add(
                newEpisode(url) {
                    this.name = "Part 1"
                    this.episode = 1
                }
            )

            archiveLinks.forEachIndexed { index, a ->
                val link = fixUrlNull(a.attr("href")) ?: return@forEachIndexed
                if (link != url) {
                    episodes.add(
                        newEpisode(link) {
                            this.name = "Part ${index + 2}"
                            this.episode = index + 2
                        }
                    )
                }
            }

            if (episodes.size > 1) {
                return newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.plot = synopsis ?: "Collection of ${episodes.size} videos"
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = synopsis
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (100% Original Single-Video Logic Preserved)
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
