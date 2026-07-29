package com.chikianimation

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class ChikiAnimationProvider : MainAPI() {
    override var mainUrl = "https://chikianimation.online"
    override var name = "ChikiAnimation"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/anime/?status=&type=&order=update" to "Latest Release",
        "$mainUrl/anime/?status=&type=&order=popular" to "Popular",
        "$mainUrl/anime/?status=completed&type=&order=update" to "Completed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data.replace("?", "page/$page/?")
        val document = app.get(url).document

        val home = document.select("article.bs > div.bsx").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("a") ?: return null

        val rawHref = fixUrlNull(linkEl.attr("href")) ?: return null
        // Strip "-episode-X" or "-ep-X" suffixes from the URL to get the main series page
        val href = rawHref.replace(Regex("-(episode|ep)-\\d+-[a-zA-Z0-9-]+/?$"), "")
            .let { if (it.contains("/anime/")) it else "$mainUrl/anime/${it.substringAfterLast("/")}" }

        val title = linkEl.attr("title").ifBlank {
            this.selectFirst("div.tt")?.text()
        }?.trim() ?: return null

        val rawPoster = this.selectFirst("img")?.attr("data-lazy-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("data-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("src")

        // Strip Jetpack CDN proxy and resize query params
        val posterUrl = fixUrlNull(rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.bs > div.bsx").mapNotNull { it.toSearchResult() }
    }

    // ---------------------------------------------------------------
    // LOAD (anime detail page + episode list)
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()?.replace(Regex("(?i)(episode|ep)\\s*\\d+.*"), "") ?: ""
        
        // TIGHTER SELECTORS: Force the scraper to look strictly inside the content/article wrapper
        val posterElement = document.selectFirst(".bigcontent .thumb img, .bixbox .thumb img, article .thumb img, .infox .imgbox img, .ts-post-image")
        
        val rawPoster = posterElement?.attr("data-lazy-src")?.ifBlank { null }
            ?: posterElement?.attr("data-src")?.ifBlank { null }
            ?: posterElement?.attr("src")
        
        var poster = fixUrlNull(rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://"))

        // FALLBACK: If the main selector fails, try og:image, but explicitly reject default site banners and logos
        if (poster.isNullOrBlank()) {
            val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
            if (ogImage != null && !ogImage.contains("logo", true) && !ogImage.contains("banner", true)) {
                poster = fixUrlNull(ogImage)
            }
        }
        
        val synopsis = document.selectFirst(".entry-content, .synp .entry-content, #synopsis, .desc")?.text()
        val genres = document.select("a[href*=/genres/], .genxed a").map { it.text() }

        fun parseEpisodeGrid(doc: org.jsoup.nodes.Document, currentUrl: String): List<Episode> {
            val elements = doc.select("div.eplister ul li, div.episodelist ul li, ul.episodelist li, div.ep_list ul li, .bixbox.bxcl ul li")
            return elements.mapNotNull { li ->
                val epLink = li.selectFirst("a")
                val epHref = if (epLink != null && epLink.hasAttr("href")) fixUrlNull(epLink.attr("href")) 
                             else if (li.hasClass("selected") || li.hasAttr("selected") || li.select("div.playinfo").isNotEmpty()) currentUrl 
                             else return@mapNotNull null
                             
                if (epHref == null) return@mapNotNull null
                
                val epTitle = (epLink?.attr("title")?.ifBlank { epLink.text() } ?: li.text()).trim()
                
                // Extract episode number
                val epNumText = li.selectFirst(".epl-num")?.text() ?: epTitle
                val epNum = Regex("(?i)episode\\s*(\\d+)").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(?i)ep\\s*(\\d+)").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("\\d+").find(epNumText)?.value?.toIntOrNull()

                newEpisode(epHref) {
                    this.name = epTitle.ifBlank { "Episode $num" }
                    this.episode = epNum
                }
            }.distinctBy { it.data }.reversed()
        }

        var episodes = parseEpisodeGrid(document, url)
        
        // Fallback: Check for any episode link if the series page hides the list
        if (episodes.isEmpty()) {
            val firstEpLink = document.selectFirst(".epcurfirst a, .epcurlast a, .inepcx a, .bxcl a, a:matchesOwn((?i)watch)")?.attr("href")
            val anyEpLink = document.select("a[href]").firstOrNull { 
                (it.attr("href").contains("-episode-") || it.attr("href").contains("-ep-")) && it.attr("href").contains(mainUrl)
            }?.attr("href")
            
            val fallbackHref = fixUrlNull(firstEpLink ?: anyEpLink)
            
            if (fallbackHref != null) {
                val epDocument = app.get(fallbackHref).document
                episodes = parseEpisodeGrid(epDocument, fallbackHref)
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (Custom Extractor Method included)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        // Custom Inline Dailymotion Extractor
        suspend fun extractDailymotion(url: String): Boolean {
            val dmRegex = Regex("""(?:video/|video=|dai\.ly/|embed/video/)([a-zA-Z0-9]+)""")
            val videoId = dmRegex.find(url)?.groupValues?.get(1) ?: return false
            
            val metadataUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            return try {
                val response = app.get(metadataUrl, headers = mapOf("Referer" to "https://www.dailymotion.com/")).text
                
                // Parse out the master m3u8 playlist URL from metadata json block
                val m3u8Url = Regex("""url"\s*:\s*"([^"]+?\.m3u8[^"]*?)"""")
                    .find(response)?.groupValues?.get(1)
                    ?.replace("\\/", "/") // Clean up JSON escaped slashes

                if (!m3u8Url.isNullOrBlank()) {
                    callback(
                        ExtractorLink(
                            source = "Dailymotion",
                            name = "Dailymotion (Ad-Free)",
                            url = m3u8Url,
                            referer = "https://www.dailymotion.com/",
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        }

        // Helper function to process candidate URLs and filter lazyloaded/corrupt values
        suspend fun invokeExtractor(url: String): Boolean {
            var cleanUrl = url.trim()
            if (cleanUrl.startsWith("//")) {
                cleanUrl = "https:$cleanUrl"
            }

            if (cleanUrl.isBlank() || cleanUrl.startsWith("data:") || cleanUrl.contains("about:blank")) {
                return false
            }

            // Route all Dailymotion-related links through our internal custom extractor
            if (cleanUrl.contains("dailymotion") || cleanUrl.contains("dai.ly")) {
                return extractDailymotion(cleanUrl)
            }

            // Fallback to standard Cloudstream extractor core for alternate servers
            return try {
                loadExtractor(cleanUrl, data, subtitleCallback, callback)
            } catch (e: Exception) {
                false
            }
        }

        // 1. Scrape Base64 Dropdowns and Hidden Embeds
        val embedDiv = document.selectFirst("#pembed[data-default-embed]")
        val encoded = embedDiv?.attr("data-default-embed")

        if (!encoded.isNullOrBlank()) {
            val decodedHtml = try {
                String(Base64.decode(encoded, Base64.DEFAULT))
            } catch (e: Exception) {
                null
            }
            val iframeSrc = decodedHtml?.let {
                Regex("""src=["']([^"']+)["']""").find(it)?.groupValues?.get(1)
            }
            if (!iframeSrc.isNullOrBlank()) {
                if (invokeExtractor(iframeSrc)) found = true
            }
        }

        // 2. Extract iframes directly from the DOM, parsing multi-layer lazyload attributes
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").takeIf { it.isNotBlank() }
                ?: iframe.attr("src").takeIf { it.isNotBlank() }
                ?: iframe.attr("data-lazy-src").takeIf { it.isNotBlank() }

            if (!src.isNullOrBlank()) {
                if (invokeExtractor(src)) found = true
            }
        }

        // 3. Fallback: Parse other interactive server tabs or dropdown options
        document.select("option, li, div, button").forEach { element ->
            val dataAttributes = listOf("data-embed", "data-link", "data-video", "value")
            for (attr in dataAttributes) {
                val value = element.attr(attr).trim()
                if (value.isBlank()) continue

                val decoded = if (!value.startsWith("http") && !value.contains("/") && value.length > 10) {
                    try { String(Base64.decode(value, Base64.DEFAULT)) } catch (e: Exception) { "" }
                } else {
                    value
                }

                if (decoded.contains("http") || decoded.startsWith("//")) {
                    val cleanUrl = if (decoded.contains("iframe")) {
                        Regex("""src=["']([^"']+)["']""").find(decoded)?.groupValues?.get(1) ?: decoded
                    } else {
                        decoded
                    }
                    if (invokeExtractor(cleanUrl)) found = true
                }
            }
        }

        return found
    }
}
