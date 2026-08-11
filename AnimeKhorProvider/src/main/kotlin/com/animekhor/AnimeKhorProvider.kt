package com.animekhor

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class AnimeKhorProvider : MainAPI() {
    override var mainUrl = "https://animekhor.org"
    override var name = "AnimeKhor"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

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
        val rawHref = fixUrlNull(linkEl.attr("href"))?.trimEnd('/') ?: return null
        
        val href = rawHref.replace(Regex("-(episode|ep)-\\d+.*$"), "")
            .let { if (it.contains("/anime/")) it else "$mainUrl/anime/${it.substringAfterLast("/")}/" }

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
    // LOAD LINKS (Fully Tuned for All 10 Servers)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false
        val embedLinks = mutableListOf<Pair<String, String>>()

        val siteHeaders = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to USER_AGENT
        )

        fun addEmbed(url: String?, name: String) {
            if (url.isNullOrBlank()) return
            var cleanUrl = url.trim().replace("\\/", "/")
            if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
            if (cleanUrl.startsWith("http")) {
                if (embedLinks.none { it.first == cleanUrl }) {
                    embedLinks.add(Pair(cleanUrl, name))
                }
            }
        }

        // 1. Decode all dropdown mirrors from select.mirror
        document.select("select.mirror option[value]").forEach { element ->
            val value = element.attr("value").trim()
            val label = element.text().trim().ifBlank { "Server" }

            if (value.isNotBlank()) {
                if (value.startsWith("http") || value.startsWith("//")) {
                    addEmbed(value, label)
                } else {
                    try {
                        val decoded = String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
                        val src = Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(decoded)?.groupValues?.get(1)
                        if (!src.isNullOrBlank()) {
                            addEmbed(src, label)
                        } else {
                            val directUrl = Regex("""https?://[^\s"'<>]+""").find(decoded)?.value
                            addEmbed(directUrl ?: value, label)
                        }
                    } catch (e: Exception) {
                        addEmbed(value, label)
                    }
                }
            }
        }

        // 2. Extract visible iframes and player divs
        document.select(".player-embed iframe, #embed_holder iframe, #pembed iframe, iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
            addEmbed(src, "Default Player")
        }

        // 3. Process all unique embed sources
        for ((embedUrl, serverLabel) in embedLinks) {
            try {
                // A. Dailymotion
                if (embedUrl.contains("dailymotion.com") || embedUrl.contains("geo.dailymotion")) {
                    val vid = Regex("""(?:video/|video=|embed/|/video/)([a-zA-Z0-9_]+)""").find(embedUrl)?.groupValues?.get(1)
                    if (vid != null) {
                        if (loadExtractor("https://www.dailymotion.com/video/$vid", data, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                    continue
                }

                // B. Native Cloudstream Extractors
                // FIXED: Collect the links into a temp list first to safely run newExtractorLink outside the callback
                val tempLinks = mutableListOf<ExtractorLink>()
                if (loadExtractor(embedUrl, data, subtitleCallback) { link ->
                    tempLinks.add(link)
                }) {
                    found = true
                    for (link in tempLinks) {
                        callback(
                            newExtractorLink(
                                source = link.source.ifBlank { name },
                                name = if (serverLabel.isNotBlank()) "$serverLabel (${link.name})" else link.name,
                                url = link.url,
                                type = link.type
                            ) {
                                this.quality = link.quality
                                this.headers = siteHeaders
                                this.extractorData = link.extractorData
                                this.referer = link.referer.ifBlank { data }
                            }
                        )
                    }
                    continue
                }

                // C. AnimeKhor Multi-Player / Custom Proxy Servers
                val res = app.get(embedUrl, headers = mapOf("Referer" to data, "User-Agent" to USER_AGENT))
                val pageText = res.text
                val pageDoc = res.document

                // Extract Subtitles
                pageDoc.select("track").forEach { track ->
                    val trackSrc = track.attr("src").ifBlank { track.attr("data-src") }
                    val label = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Subtitle" }
                    val subUrl = fixRelativeUrl(trackSrc, embedUrl)
                    if (!subUrl.isNullOrBlank()) {
                        subtitleCallback(newSubtitleFile(label, subUrl))
                    }
                }

                // Extract Video Streams
                var customFound = false
                pageDoc.select("source").forEach { source ->
                    val streamUrl = fixRelativeUrl(source.attr("src"), embedUrl)
                    if (!streamUrl.isNullOrBlank()) {
                        val isM3u8 = streamUrl.contains(".m3u8", true) || streamUrl.contains(".txt", true) || source.attr("type").contains("mpegurl", true)
                        if (isM3u8) {
                            M3u8Helper.generateM3u8(name, streamUrl, embedUrl, headers = siteHeaders).forEach { link ->
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = "$serverLabel (HLS)",
                                        url = link.url,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = link.quality
                                        this.headers = siteHeaders
                                        this.referer = embedUrl
                                    }
                                )
                                customFound = true
                            }
                        } else {
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "$serverLabel (MP4)",
                                    url = streamUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.headers = siteHeaders
                                    this.referer = embedUrl
                                }
                            )
                            customFound = true
                        }
                    }
                }

                // Regex Fallback
                if (!customFound) {
                    val streamMatches = Regex("""https?://[^\s"'<>]+?(?:\.m3u8|\.mp4|\.txt|playlist\.m3u8|master\.m3u8)(?:\?[^\s"'<>]*)?""")
                        .findAll(pageText).map { it.value.replace("\\/", "/") }.distinct().toList()

                    for (streamUrl in streamMatches) {
                        val isM3u8 = streamUrl.contains(".m3u8", true) || streamUrl.contains(".txt", true)
                        if (isM3u8) {
                            M3u8Helper.generateM3u8(name, streamUrl, embedUrl, headers = siteHeaders).forEach { link ->
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = "$serverLabel (HLS)",
                                        url = link.url,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = link.quality
                                        this.headers = siteHeaders
                                        this.referer = embedUrl
                                    }
                                )
                                customFound = true
                            }
                        } else {
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "$serverLabel (Direct)",
                                    url = streamUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.headers = siteHeaders
                                    this.referer = embedUrl
                                }
                            )
                            customFound = true
                        }
                    }
                }

                if (customFound) found = true

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return found
    }
}
