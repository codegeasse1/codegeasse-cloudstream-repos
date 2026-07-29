package com.chikianimation

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
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
        val href = rawHref.replace(Regex("-(episode|ep)-\\d+-[a-zA-Z0-9-]+/?$"), "")
            .let { if (it.contains("/anime/")) it else "$mainUrl/anime/${it.substringAfterLast("/")}" }

        val title = linkEl.attr("title").ifBlank { this.selectFirst("div.tt")?.text() }?.trim() ?: return null

        val rawPoster = this.selectFirst("img")?.attr("data-lazy-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("data-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("src")

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
        
        // Strict Poster Extract (Avoids site banners)
        val posterElement = document.selectFirst(".bigcontent .thumb img, .bixbox .thumb img, article .thumb img, .infox .imgbox img, .ts-post-image")
        val rawPoster = posterElement?.attr("data-lazy-src")?.ifBlank { null } ?: posterElement?.attr("data-src")?.ifBlank { null } ?: posterElement?.attr("src")
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
            return doc.select("div.eplister ul li, div.episodelist ul li, ul.episodelist li, div.ep_list ul li, .bixbox.bxcl ul li").mapNotNull { li ->
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
    // LOAD LINKS (The Ultimate Fix for Base64 Dropdowns & Custom Players)
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
            var url = fixUrlNull(rawUrl) ?: return
            if (url.startsWith("//")) url = "https:$url"

            // 1. Dailymotion Bypasser
            if (url.contains("dailymotion.com") || url.contains("geo.dailymotion")) {
                val vid = Regex("""(?:video/|video=|embed/|/video/)([a-zA-Z0-9_]+)""").find(url)?.groupValues?.get(1)
                if (vid != null) {
                    loadExtractor("https://www.dailymotion.com/video/$vid", data, subtitleCallback, callback)
                    found = true
                }
                return
            }

            // 2. Custom Player (rpmstream, organicgoods, bysekoze, etc)
            if (url.contains("rpmstream") || url.contains("organicgoods") || url.contains("chiki.") || url.contains("bysekoze")) {
                try {
                    // MUST use `data` (episode URL) as Referer or the stream will 403 block us
                    val res = app.get(url, headers = mapOf("Referer" to data))
                    val doc = res.document
                    val html = res.text

                    doc.select("track").forEach { track ->
                        val trackSrc = track.attr("src").ifBlank { track.attr("data-src") }
                        val label = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Subtitle" }
                        val subUrl = fixUrlNull(trackSrc) ?: return@forEach
                        subtitleCallback(SubtitleFile(label, subUrl))
                    }

                    var sourceFound = false
                    doc.select("source").forEach { source ->
                        val streamUrl = fixUrlNull(source.attr("src")) ?: return@forEach
                        if (streamUrl.contains(".m3u8") || streamUrl.contains(".txt") || source.attr("type").contains("mpegurl", true)) {
                            M3u8Helper.generateM3u8("Multi Player", streamUrl, url).forEach { callback(it) }
                            sourceFound = true
                        }
                    }

                    if (!sourceFound) {
                        Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.txt)(?:\?[^\s"'<>]*)?""").findAll(html).forEach { match ->
                            M3u8Helper.generateM3u8("Multi Player", match.value, url).forEach { callback(it) }
                            sourceFound = true
                        }
                    }
                    if (sourceFound) found = true
                } catch (e: Exception) {
                    Log.e("ChikiAnimation", "Custom Player Error: ${e.message}")
                }
                return
            }

            // 3. Fallback to normal CloudStream extractors (ok.ru, rumble, etc.)
            if (url.startsWith("http")) {
                loadExtractor(url, data, subtitleCallback, callback)
                found = true
            }
        }

        // A. Scrape Base64 dropdown options (From the HTML source you shared)
        document.select("select.mirror option[value]").forEach {
            val value = it.attr("value")
            if (value.isNotBlank() && value.length > 20 && !value.contains(" ")) {
                try {
                    val decoded = String(Base64.decode(value, Base64.DEFAULT))
                    val src = Regex("""src=["']([^"']+)["']""").find(decoded)?.groupValues?.get(1)
                    if (src != null) processUrl(src)
                } catch (e: Exception) {}
            }
        }

        // B. Scrape standard data-embeds
        document.select("[data-default-embed], [data-embed]").forEach {
            val value = it.attr("data-default-embed").ifBlank { it.attr("data-embed") }
            if (value.isNotBlank()) {
                try {
                    val decoded = String(Base64.decode(value, Base64.DEFAULT))
                    val src = Regex("""src=["']([^"']+)["']""").find(decoded)?.groupValues?.get(1)
                    if (src != null) processUrl(src)
                } catch (e: Exception) {}
            }
        }

        // C. Scrape visible iframes
        document.select("iframe").forEach {
            val src = it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("data-lazy-src") }
            if (src.isNotBlank()) processUrl(src)
        }

        // D. Fetch AJAX servers (Just in case the server list is hidden behind a click)
        document.select("[data-post][data-nume]").forEach { el ->
            val postId = el.attr("data-post")
            val nume = el.attr("data-nume")
            val type = el.attr("data-type")

            if (postId.isNotBlank() && nume.isNotBlank()) {
                try {
                    val ajaxRes = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data),
                        data = mapOf("action" to "player_ajax", "post" to postId, "nume" to nume, "type" to type)
                    ).text
                    val src = Regex("""src=\\?["']([^"'\\]+)\\?["']""").find(ajaxRes)?.groupValues?.get(1)
                        ?: Regex("""(?:url|embed_url)["']?\s*:\s*["']([^"']+)["']""").find(ajaxRes)?.groupValues?.get(1)
                    if (src != null) processUrl(src)
                } catch (e: Exception) {}
            }
        }

        return found
    }
}
