package com.chigua

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

class C51CGProvider : MainAPI() {
    override var mainUrl = "https://51cg1.com"
    override var name = "51CG"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Others)

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
    // MAIN PAGE – All content categories + Home
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/category/wpcz/" to "Today's Melon",
        "$mainUrl/category/xsxy/" to "Campus Student",
        "$mainUrl/category/whhl/" to "Internet Celebrity",
        "$mainUrl/category/rdsj/" to "Hot Melon",
        "$mainUrl/category/mrdg/" to "Melon List",
        "$mainUrl/category/bkdg/" to "Must Watch",
        "$mainUrl/category/cbdj/" to "AI Adult Drama",
        "$mainUrl/category/ysyl/" to "Watching Fun",
        "$mainUrl/category/mrds/" to "Daily Contest",
        "$mainUrl/category/lldd/" to "Ethics Morality",
        "$mainUrl/category/gcjq/" to "Chinese Drama",
        "$mainUrl/category/thjx/" to "Selected Visits",
        "$mainUrl/category/whhj/" to "Web Yellow Collection",
        "$mainUrl/category/snsn/" to "Slutty Men Women",
        "$mainUrl/category/whmx/" to "Celebrity Scandal",
        "$mainUrl/category/hwcg/" to "Overseas Melon",
        "$mainUrl/category/rrcg/" to "Everyone Melon",
        "$mainUrl/category/ldcg/" to "Cadre Leader",
        "$mainUrl/category/jpll/" to "Sweet Girl",
        "$mainUrl/category/qubk/" to "Melon Watching",
        "$mainUrl/category/dcbq/" to "Flirting",
        "$mainUrl/category/zzs/" to "51 Knowledge",
        "$mainUrl/category/cgxw/" to "Melon News",
        "$mainUrl/category/yczq/" to "Original Blogger",
        "$mainUrl/category/51djc/" to "51 Theater",
        "$mainUrl/category/sjb/" to "World Cup",
        "$mainUrl/category/51hd/" to "Past Activities"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Hikari 51CG pagination scheme: Home → /page/N/, categories → /category/<slug>/N/
        val url = when {
            page <= 1 -> request.data
            request.data == "$mainUrl/" -> "$mainUrl/page/$page/"
            else -> request.data.trimEnd('/') + "/$page/"
        }
        val document = app.get(url).document

        // Skip ad-articles (class "ad-item")
        val items = document.select("article:not(.ad-item):has(.post-card) a").mapNotNull { element ->
            element.toSearchResult()
        }

        // Unlimited pagination: keep loading pages until the site returns no more cards.
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ---------------------------------------------------------------
    // ITEM PARSING (works for all pages)
    // ---------------------------------------------------------------
    private suspend fun Element.toSearchResult(): SearchResponse? {
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
    // SEARCH (path-based pagination: /search/<term>/ / /page/N/)
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
                element.toSearchResult()
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
    // LOAD (Detail Page) – RANKING LIST ONLY FOR “Melon List” CATEGORY
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val pageHtml = document.outerHtml()

        // Detect if the page belongs to the "Melon List" category (吃瓜榜单, slug mrdg)
        val isMelonList = document.select(".nav-breadcrumb-wrap a[href*=\"/category/mrdg/\"]").isNotEmpty()

        if (isMelonList) {
            val rawTitle = document.selectFirst("h1.post-title")?.text()?.trim()
                ?: document.selectFirst("title")?.text()?.substringBefore("-")?.trim()
                ?: "Ranking List"
            val title = translateToEnglish(rawTitle) ?: "Ranking List"

            val poster = document.selectFirst(".post-content img[data-xkrkllgl]")?.attr("data-xkrkllgl")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")

            val rankingLinks = document.select(".post-content a.btn.btn-primary[href*=/archives/]")
            val episodes = mutableListOf<Episode>()
            for ((index, a) in rankingLinks.withIndex()) {
                val link = fixUrlNull(a.attr("href")) ?: continue
                val rawEpTitle = a.parent()?.previousElementSibling()?.text()?.trim()
                    ?: a.text().trim()
                val epTitle = translateToEnglish(rawEpTitle) ?: rawEpTitle
                episodes.add(
                    newEpisode(link) {
                        this.name = epTitle
                        this.episode = index + 1
                    }
                )
            }

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = "Top ${episodes.size} entries"
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }

        // ---------------------------------------------------------------
        // NORMAL SINGLE-VIDEO PAGE (all other categories)
        // ---------------------------------------------------------------
        val rawTitle = document.selectFirst("h1, .post-title, title")?.text()?.substringBefore("-")?.trim() ?: "Video"
        val title = translateToEnglish(rawTitle) ?: "Video"

        var poster = document.selectFirst(".post-content img, article p img")?.let {
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

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = synopsis
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (generic M3U8 scraper)
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
                        source = "51CG Server",
                        name = "51CG Server",
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
