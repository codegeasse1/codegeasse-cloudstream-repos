package com.chikianimation

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
        val href = rawHref
            .replace(Regex("-(episode|ep)-\\d+-[a-zA-Z0-9-]+/?$"), "")
            .let {
                if (it.contains("/anime/")) it
                else "$mainUrl/anime/${it.substringAfterLast("/")}"
            }

        val title = linkEl.attr("title").ifBlank {
            this.selectFirst("div.tt")?.text()
        }?.trim() ?: return null

        val rawPoster = this.selectFirst("img")?.attr("data-lazy-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("data-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("src")

        val posterUrl = fixUrlNull(
            rawPoster?.substringBefore("?")
                ?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://")
        )

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
    // LOAD
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("(?i)(episode|ep)\\s*\\d+.*"), "") ?: ""

        val posterElement = document.selectFirst(
            ".bigcontent .thumb img, .bixbox .thumb img, article .thumb img, .infox .imgbox img, .ts-post-image"
        )
        val rawPoster = posterElement?.attr("data-lazy-src")?.ifBlank { null }
            ?: posterElement?.attr("data-src")?.ifBlank { null }
            ?: posterElement?.attr("src")

        var poster = fixUrlNull(
            rawPoster?.substringBefore("?")
                ?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://")
        )

        if (poster.isNullOrBlank()) {
            val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
            if (ogImage != null && !ogImage.contains("logo", true) && !ogImage.contains("banner", true)) {
                poster = fixUrlNull(ogImage)
            }
        }

        val synopsis = document.selectFirst(".entry-content, .synp .entry-content, #synopsis, .desc")?.text()
        val genres = document.select("a[href*=/genres/], .genxed a").map { it.text() }

        fun parseEpisodeGrid(doc: org.jsoup.nodes.Document, currentUrl: String): List<Episode> {
            val elements = doc.select(
                "div.eplister ul li, div.episodelist ul li, ul.episodelist li, div.ep_list ul li, .bixbox.bxcl ul li"
            )
            return elements.mapNotNull { li ->
                val epLink = li.selectFirst("a")
                val epHref = if (epLink != null && epLink.hasAttr("href"))
                    fixUrlNull(epLink.attr("href"))
                else if (li.select("div.playinfo").isNotEmpty())
                    currentUrl
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
            val firstEpLink = document.selectFirst(".epcurfirst a, .epcurlast a, .inepcx a, .bxcl a")
                ?.attr("href")
            val anyEpLink = document.select("a[href]").firstOrNull {
                (it.attr("href").contains("-episode-") || it.attr("href").contains("-ep-")) &&
                        it.attr("href").contains(mainUrl)
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
    // LOAD LINKS - Fixed
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        // ── 1. Collect all embed URLs ──────────────────────────────
        val embedUrls = mutableSetOf<String>()

        // 1a. Base64 encoded embed
        document.selectFirst("#pembed[data-default-embed]")
            ?.attr("data-default-embed")
            ?.takeIf { it.isNotBlank() }
            ?.let { encoded ->
                runCatching {
                    val decodedHtml = String(Base64.decode(encoded, Base64.DEFAULT))
                    Regex("""src=["']([^"']+)["']""").find(decodedHtml)
                        ?.groupValues?.get(1)
                }
                .getOrNull()
                ?.let { embedUrls.add(it) }
            }

        // 1b. All iframes — check every possible src attribute
        document.select("iframe").forEach { iframe ->
            listOf("src", "data-src", "data-lazy-src", "data-original").forEach { attr ->
                val src = iframe.attr(attr)
                if (src.isNotBlank() && !src.startsWith("javascript") && src.startsWith("http")) {
                    embedUrls.add(src)
                }
            }
        }

        // 1c. Server selector buttons / option links
        document.select(
            ".mirrorlist a, .server-item a, [data-src], " +
            ".serverlist a, .mirrorLink a, option[data-link]"
        ).forEach { el ->
            val src = el.attr("data-src").ifBlank { el.attr("data-link") }.ifBlank { el.attr("href") }
            if (src.isNotBlank() && src.startsWith("http")) embedUrls.add(src)
        }

        // ── 2. Handle each URL ────────────────────────────────────
        for (rawUrl in embedUrls) {
            val embedUrl = fixUrlNull(rawUrl) ?: continue

            when {
                // Dailymotion geo embed → convert to standard embed
                embedUrl.contains("geo.dailymotion.com") -> {
                    val videoId = Regex("""video=([a-zA-Z0-9]+)""")
                        .find(embedUrl)?.groupValues?.get(1)
                    if (videoId != null) {
                        val standardUrl = "https://www.dailymotion.com/embed/video/$videoId"
                        loadExtractor(standardUrl, data, subtitleCallback, callback)
                        found = true
                    }
                }

                // Standard dailymotion embed
                embedUrl.contains("dailymotion.com") -> {
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                    found = true
                }

                // Everything else
                else -> {
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        return found
    }
}
