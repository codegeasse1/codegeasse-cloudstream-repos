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
        //   /martial-master-episode-678-english-subtitles
        // Strip the "-episode-N-english-subtitles" suffix to recover the series page.
        val rawHref = fixUrlNull(linkEl.attr("href")) ?: return null
        val href = rawHref.replace(Regex("-episode-\\d+-english-subtitles/?$"), "")
            .let { if (it.contains("/anime/")) it else "$mainUrl/anime/${it.substringAfterLast("/")}" }

        val title = linkEl.attr("title").ifBlank {
            this.selectFirst("div.tt")?.text()
        }?.trim() ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

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
        val poster = fixUrlNull(document.selectFirst(".limit img, .infox img, img[itemprop=image]")?.attr("src"))
        val synopsis = document.selectFirst(".entry-content, .synp .entry-content, #synopsis")?.text()
        val genres = document.select("a[href*=/genres/]").map { it.text() }

        // Confirmed markup: <div class="episode-numbers"><a href="..." data-num="678"
        // title="Martial Master Episode 678 English Subtitles" ...>678</a> ...</div>
        // This was confirmed on an episode page. It's usually a shared theme component that
        // also renders on the /anime/ page, so try there first; if it's missing (some themes
        // only render it on episode pages), follow the "Watch Now" link once to fetch it.
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

        // Confirmed: the player container ships a base64-encoded <iframe> in
        // data-default-embed, decodable without running any JS.
        //   <div id="pembed" ... data-default-embed="PGlmcmU...">
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

        // Fallback: if the iframe is only present after the "Play video" click,
        // look for a live iframe already in the DOM as well.
        document.select("iframe[src*=dailymotion], iframe.dmp_iframe, div.agn-player-stage iframe")
            .forEach { iframe ->
                val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }

        return found
    }
}



yaml



name: Build CloudStream Plugins

on:
  push:
    branches: [main, master]
  workflow_dispatch:

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: '8.4'

      - name: Build plugins
        run: gradle make makePluginsJson

      - name: Collect build output
        run: |
          mkdir -p release
          find . -name "*.cs3" -not -path "./release/*" -exec cp {} release/ \;
          cp build/plugins.json release/plugins.json
          echo '{"name": "Custom Repo", "description": "My CloudStream extensions", "manifestVersion": 1, "pluginLists": ["https://raw.githubusercontent.com/${{ github.repository }}/builds/plugins.json"]}' > release/repo.json

      - name: Push to builds branch
        run: |
          cd release
          git init
          git checkout -b builds
          git add .
          git -c user.name='github-actions' -c user.email='github-actions@github.com' commit -m "Build ${{ github.sha }}"
          git remote add origin "https://x-access-token:${{ secrets.GITHUB_TOKEN }}@github.com/${{ github.repository }}"
          git push origin builds --force




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

        suspend fun processUrl(rawUrl: String) {
            val url = fixUrlNull(rawUrl) ?: return
            var finalUrl = url
            
            // Instantly extract ID from geo.dailymotion links
            if (url.contains("geo.dailymotion.com")) {
                val vid = Regex("video=([a-zA-Z0-9]+)").find(url)?.groupValues?.get(1)
                if (vid != null) finalUrl = "https://www.dailymotion.com/video/$vid"
            } 
            else if (url.contains("dailymotion.com/crawler/video/")) {
                val vid = url.substringAfterLast("/")
                if (vid.isNotBlank()) finalUrl = "https://www.dailymotion.com/video/$vid"
            }

            if (finalUrl.contains("dailymotion.com/video/")) {
                loadExtractor(finalUrl, data, subtitleCallback, callback)
                found = true
            } else {
                // If it's a normal iframe (like standard mp4 or another host), extract it normally
                loadExtractor(finalUrl, data, subtitleCallback, callback)
                found = true
            }
        }

        // 1. Scrape SEO Meta Tags
        document.select("meta[itemprop=embedUrl], meta[itemprop=contentUrl]").forEach { element ->
            processUrl(element.attr("content"))
        }

        // 2. Scrape Base64 Dropdowns and Hidden Embeds
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

        // 3. Scrape visible iframes
        document.select("iframe[src]").forEach { iframe ->
            processUrl(iframe.attr("src"))
        }

        return found
    }
}
