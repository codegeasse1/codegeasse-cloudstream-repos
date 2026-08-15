package com.luciferdonghua

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class LuciferDonghuaProvider : MainAPI() {
    override var mainUrl = "https://luciferdonghua.in"
    override var name = "LuciferDonghua"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

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
        val href = rawHref.replace(Regex("-episode-\\d+-[a-zA-Z0-9-]+/?$"), "")
            .let { if (it.contains("/anime/")) it else "$mainUrl/anime/${it.substringAfterLast("/")}" }

        val title = linkEl.attr("title").ifBlank {
            this.selectFirst("div.tt")?.text()
        }?.trim() ?: return null

        val rawPoster = this.selectFirst("img")?.attr("data-lazy-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("data-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("src")

        val posterUrl = fixUrlNull(rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.bs > div.bsx").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()?.replace(Regex("(?i)episode\\s*\\d+.*"), "") ?: ""
        
        // Strict selectors to ensure it grabs the anime poster instead of the site banner
        val posterElement = document.selectFirst(".bigcontent .thumb img, .bixbox .thumb img, article .thumb img, .infox .imgbox img, .ts-post-image")
        val rawPoster = posterElement?.attr("data-lazy-src")?.ifBlank { null }
            ?: posterElement?.attr("data-src")?.ifBlank { null }
            ?: posterElement?.attr("src")
        
        var poster = fixUrlNull(rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://"))

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
        if (episodes.isEmpty()) {
            val firstEpLink = document.selectFirst(".epcurfirst a, .epcurlast a, .inepcx a, .bxcl a, a:matchesOwn((?i)watch)")?.attr("href")
            val anyEpLink = document.select("a[href]").firstOrNull { 
                it.attr("href").contains("-episode-") && it.attr("href").contains(mainUrl)
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        suspend fun processUrl(rawUrl: String) {
            val url = fixUrlNull(rawUrl) ?: return
            var finalUrl = url
            
            if (url.contains("geo.dailymotion.com")) {
                val vid = Regex("video=([a-zA-Z0-9]+)").find(url)?.groupValues?.get(1)
                if (vid != null) finalUrl = "https://www.dailymotion.com/video/$vid"
            } else if (url.contains("dailymotion.com/crawler/video/")) {
                val vid = url.substringAfterLast("/")
                if (vid.isNotBlank()) finalUrl = "https://www.dailymotion.com/video/$vid"
            }

            if (finalUrl.contains("dailymotion.com/video/")) {
                loadExtractor(finalUrl, data, subtitleCallback, callback)
                found = true
            } else {
                loadExtractor(finalUrl, data, subtitleCallback, callback)
                found = true
            }
        }

        document.select("meta[itemprop=embedUrl], meta[itemprop=contentUrl]").forEach { element ->
            processUrl(element.attr("content"))
        }

        document.select("select option[value], [data-default-embed], [data-embed]").forEach { element ->
            val value = element.attr("value").ifBlank { element.attr("data-default-embed") }.ifBlank { element.attr("data-embed") }
            if (value.isNotBlank()) {
                try {
                    val decoded = String(Base64.decode(value, Base64.DEFAULT))
                    val src = Regex("src=[\"']([^\"']+)[\"']").find(decoded)?.groupValues?.get(1)
                    if (src != null) processUrl(src)
                } catch (e: Exception) {}
            }
        }

        document.select("iframe[src]").forEach { iframe ->
            processUrl(iframe.attr("src"))
        }

        return found
    }
}
