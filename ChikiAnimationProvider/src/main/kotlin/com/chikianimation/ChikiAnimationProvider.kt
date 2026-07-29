package com.chikianimation

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
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
        
        // REVERTED to old simple selector logic
        val rawPoster = document.selectFirst(".limit img, .infox img, img[itemprop=image], .thumb img")?.attr("src")
        val poster = fixUrlNull(rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://"))
        
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
                    this.name = epTitle.ifBlank { "Episode $epNum" }
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
    // LOAD LINKS (Reverted to your working old code)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        val embedDiv = document.selectFirst("#pembed[data-default-embed]")
        val encoded = embedDiv?.attr("data-default-embed")

        if (!encoded.isNullOrBlank()) {
            val decodedHtml = try {
                String(Base64.decode(encoded, Base64.DEFAULT))
            } catch (e: Exception) {
                null
            }
            val iframeSrc = decodedHtml?.let {
                Regex("src=\"([^\"]+)\"").find(it)?.groupValues?.get(1)
            }
            if (!iframeSrc.isNullOrBlank()) {
                loadExtractor(iframeSrc, data, subtitleCallback, callback)
                found = true
            }
        }

        document.select("iframe[src*=dailymotion], iframe.dmp_iframe, div.agn-player-stage iframe")
            .forEach { iframe ->
                val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }

        return found
    }
}
