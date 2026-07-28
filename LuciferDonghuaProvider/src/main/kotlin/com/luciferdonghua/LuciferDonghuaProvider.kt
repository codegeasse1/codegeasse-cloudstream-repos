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
        // Strip the "-episode-X-lucifer-donghua" suffix to recover the main series page
        val href = rawHref.replace(Regex("-episode-\\d+-[a-zA-Z0-9-]+/?$"), "")
            .let { if (it.contains("/anime/")) it else "$mainUrl/anime/${it.substringAfterLast("/")}" }

        val title = linkEl.attr("title").ifBlank {
            this.selectFirst("div.tt")?.text()
        }?.trim() ?: return null

        val rawPoster = this.selectFirst("img")?.attr("data-lazy-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("data-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("src")

        // Strip Jetpack CDN proxy (i0.wp.com / i3.wp.com) and resize query params
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

        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        val rawPoster = document.selectFirst(".limit img, .infox img, img[itemprop=image]")?.attr("src")
        val poster = fixUrlNull(rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://"))
        val synopsis = document.selectFirst(".entry-content, .synp .entry-content, #synopsis")?.text()
        val genres = document.select("a[href*=/genres/]").map { it.text() }

        fun parseEpisodeGrid(doc: org.jsoup.nodes.Document) =
            doc.select("div.episodelist ul li, div#singleepisode div.episodelist ul li").mapNotNull { li ->
                val epLink = li.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrlNull(epLink.attr("href")) ?: return@mapNotNull null
                val epTitle = epLink.attr("title").ifBlank { epLink.text() }.trim()
                val epNum = Regex("(?i)episode\\s+(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name = epTitle
                    this.episode = epNum
                }
            }.reversed()

        var episodes = parseEpisodeGrid(document)
        if (episodes.isEmpty()) {
            val watchNowHref = fixUrlNull(
                document.selectFirst("a:matchesOwn((?i)watch now)")?.attr("href")
            )
            if (watchNowHref != null) {
                val epDocument = app.get(watchNowHref).document
                episodes = parseEpisodeGrid(epDocument)
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
    // LOAD LINKS (video extraction)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        // 1. Check for Base64 encoded player embed
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

        // 2. Direct DOM scan for Dailymotion and player iframes
        document.select("iframe[src], meta[itemprop=contentUrl]").forEach { element ->
            val src = fixUrlNull(element.attr("src").ifBlank { element.attr("content") }) ?: return@forEach
            if (src.contains("dailymotion") || src.contains("player") || src.contains("embed")) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        return found
    }
}
