package com.chikianimation

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI

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
        
        // Strict Image Selector (Fixes the banner issue)
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

    private fun fixRelativeUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> {
                runCatching {
                    val uri = URI(baseUrl)
                    "${uri.scheme}://${uri.host}$trimmed"
                }.getOrNull() ?: trimmed
            }
            else -> {
                runCatching {
                    val uri = URI(baseUrl)
                    val path = uri.path.substringBeforeLast("/", "")
                    "${uri.scheme}://${uri.host}$path/${trimmed.removePrefix("./")}"
                }.getOrNull() ?: trimmed
            }
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (Decodes the Base64 dropdown trap)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false
        val embedUrls = mutableSetOf<String>()

        fun addUrl(url: String?) {
            if (url.isNullOrBlank()) return
            var cleanUrl = url.trim()
            if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
            if (cleanUrl.startsWith("http")) embedUrls.add(cleanUrl)
        }

        // 1. Decode every single option from the dropdown mirror list
        document.select("option[value]").forEach { element ->
            val value = element.attr("value")
            if (value.isNotBlank()) {
                try {
                    val decoded = String(Base64.decode(value, Base64.DEFAULT))
                    val src = Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(decoded)?.groupValues?.get(1)
                    if (src != null) addUrl(src) else addUrl(value)
                } catch (e: Exception) {
                    addUrl(value)
                }
            }
        }

        // 2. Grab standard visible iframes
        document.select("iframe").forEach { iframe ->
            addUrl(iframe.attr("src"))
            addUrl(iframe.attr("data-src"))
            addUrl(iframe.attr("data-lazy-src"))
        }

        // Process all collected URLs
        for (url in embedUrls) {
            // A. Dailymotion Handler
            if (url.contains("dailymotion.com") || url.contains("geo.dailymotion")) {
                val vid = Regex("""(?:video/|video=|embed/|/video/)([a-zA-Z0-9_]+)""").find(url)?.groupValues?.get(1)
                if (vid != null) {
                    try {
                        loadExtractor("https://www.dailymotion.com/video/$vid", data, subtitleCallback, callback)
                        found = true
                    } catch (e: Exception) {}
                }
            } 
            // B. Custom Multi-Player Handler (animekhor, rpmstream, organicgoods, p2pstream, etc.)
            else if (url.contains("animekhor") || url.contains("rpmstream") || url.contains("organicgoods") || url.contains("p2pstream") || url.contains("upns")) {
                try {
                    val res = app.get(url, headers = mapOf("Referer" to data))
                    val doc = res.document
                    
                    doc.select("track").forEach { track ->
                        val trackSrc = track.attr("src").ifBlank { track.attr("data-src") }
                        val label = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Subtitle" }
                        val subUrl = fixRelativeUrl(trackSrc, url)
                        if (!subUrl.isNullOrBlank()) {
                            subtitleCallback(SubtitleFile(label, subUrl))
                        }
                    }

                    var customFound = false
                    doc.select("source").forEach { source ->
                        val streamUrl = fixRelativeUrl(source.attr("src"), url)
                        if (!streamUrl.isNullOrBlank() && (streamUrl.contains(".m3u8") || streamUrl.contains(".txt") || source.attr("type").contains("mpegurl", true))) {
                            M3u8Helper.generateM3u8("Multi Player", streamUrl, url).forEach { callback(it) }
                            customFound = true
                        }
                    }

                    if (!customFound) {
                        Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.txt)(?:\?[^\s"'<>]*)?""").findAll(res.text).forEach { match ->
                            M3u8Helper.generateM3u8("Multi Player", match.value, url).forEach { callback(it) }
                            customFound = true
                        }
                    }
                    if (customFound) found = true
                } catch (e: Exception) {}
            }
            // C. Fallback for all other standard extractors (ok.ru, rumble, d.tube, etc.)
            else {
                try {
                    loadExtractor(url, data, subtitleCallback, callback)
                    found = true
                } catch (e: Exception) {}
            }
        }

        return found
    }
}
