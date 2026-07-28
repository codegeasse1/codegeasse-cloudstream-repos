package com.anime4i

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Anime4iProvider : MainAPI() {
    override var mainUrl = "https://anime4i.com"
    override var name = "Anime4i"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var version = 2

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/anime/?status=&type=&order=update" to "Latest Update",
        "$mainUrl/anime/?status=&type=&order=popular" to "Popular",
        "$mainUrl/anime/?status=completed&type=&order=update" to "Completed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data.replace("?", "page/$page/?")
        val document = app.get(url).document

        // Confirmed markup: <article class="bs"><div class="bsx"><a href="..."> ... </a></div></article>
        val home = document.select("article.bs > div.bsx").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("a") ?: return null
        // Home/latest cards link straight to an EPISODE page, e.g.
        //   /martial-master-episode-678-english-subtitles
        // Strip the "-episode-N-english-subtitles" suffix to recover the series page.
        val rawHref = fixUrlNull(linkEl.attr("href")) ?: return null
        val href = rawHref.replace(Regex("-episode-\\d+-english-subtitles/?$"), "")
            .let { if (it.contains("/anime/")) it else "$mainUrl/anime/${it.substringAfterLast("/")}" }

        val title = linkEl.attr("title").ifBlank {
            this.selectFirst("div.tt")?.text()
        }?.trim() ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src")?.substringBefore("?"))

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
        val poster = fixUrlNull(document.selectFirst(".limit img, .infox img, img[itemprop=image]")?.attr("src")?.substringBefore("?"))
        val synopsis = document.selectFirst(".entry-content, .synp .entry-content, #synopsis")?.text()
        val genres = document.select("a[href*=/genres/]").map { it.text() }

        fun parseEpisodeGrid(doc: org.jsoup.nodes.Document) =
            doc.select("div.episode-numbers a[data-num]").mapNotNull { epLink ->
                val epHref = fixUrlNull(epLink.attr("href")) ?: return@mapNotNull null
                val epNum = epLink.attr("data-num").toIntOrNull()
                val epTitle = epLink.attr("title").ifBlank { "Episode $epNum" }
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

        val embedDiv = document.selectFirst("#pembed[data-default-embed]")
        val encoded = embedDiv?.attr("data-default-embed")

        var found = false

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
