package com.animekhor

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.Jsoup
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

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?status=&type=&order=update" to "Latest Release",
        "$mainUrl/anime/?status=&type=&order=popular" to "Popular",
        "$mainUrl/anime/?status=completed&type=&order=update" to "Completed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data.replace("?", "page/$page/?")
        val document = app.get(url).document
        
        val homeItems = mutableListOf<SearchResponse>()
        for (element in document.select("article.bs > div.bsx")) {
            val item = element.toSearchResult()
            if (item != null) homeItems.add(item)
        }

        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
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

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        val targetLimit = 100
        var page = 1

        while (searchResults.size < targetLimit) {
            val url = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
            try {
                val document = app.get(url).document
                val elements = document.select("article.bs > div.bsx")
                if (elements.isEmpty()) break

                for (element in elements) {
                    val item = element.toSearchResult()
                    if (item != null) searchResults.add(item)
                }
                page++
            } catch (e: Exception) {
                break
            }
        }
        return searchResults.distinctBy { it.url }.take(targetLimit)
    }

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
            val epList = mutableListOf<Episode>()
            for (li in doc.select("div.eplister ul li, div.episodelist ul li, ul.episodelist li, div.ep_list ul li, .bixbox.bxcl ul li")) {
                val epLink = li.selectFirst("a")
                val epHref = if (epLink != null && epLink.hasAttr("href")) fixUrlNull(epLink.attr("href")) 
                             else if (li.hasClass("selected") || li.hasAttr("selected") || li.select("div.playinfo").isNotEmpty()) currentUrl 
                             else continue
                             
                if (epHref == null) continue
                val epTitle = (epLink?.attr("title")?.ifBlank { epLink.text() } ?: li.text()).trim()
                val epNumText = li.selectFirst(".epl-num")?.text() ?: epTitle
                
                // Safely handles texts like "14 END" by extracting only digits
                val epNum = Regex("(?i)episode\\s*(\\d+)").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(?i)ep\\s*(\\d+)").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("\\d+").find(epNumText)?.value?.toIntOrNull()

                epList.add(newEpisode(epHref) {
                    this.name = epTitle.ifBlank { "Episode $epNum" }
                    this.episode = epNum
                })
            }
            return epList.distinctBy { it.data }.reversed()
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false
        val embedLinks = mutableSetOf<String>()

        // 1. Fully Decode All Dropdown Mirrors
        val mirrorOptions = document.select("select.mirror option[value]")
        for (element in mirrorOptions) {
            val value = element.attr("value").trim()
            if (value.isNotBlank()) {
                if (value.startsWith("http") || value.startsWith("//")) {
                    embedLinks.add(if (value.startsWith("//")) "https:$value" else value)
                } else {
                    try {
                        val decoded = String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8).trim()
                        val iframeSrc = Jsoup.parse(decoded).select("iframe").attr("src")
                        if (iframeSrc.isNotBlank()) {
                            embedLinks.add(iframeSrc)
                        } else {
                            val directUrl = Regex("""https?://[^\s"'<>]+""").find(decoded)?.value
                            if (directUrl != null) embedLinks.add(directUrl)
                        }
                    } catch (e: Exception) {}
                }
            }
        }

        // 2. Extract Visible Iframes
        val iframes = document.select(".player-embed iframe, #embed_holder iframe, #pembed iframe, iframe")
        for (iframe in iframes) {
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
            if (src.isNotBlank()) {
                embedLinks.add(if (src.startsWith("//")) "https:$src" else src)
            }
        }

        // 3. Process every unique link found
        for (url in embedLinks) {
            val cleanUrl = url.trim().replace("\\/", "/")

            try {
                // A. Try Cloudstream Native Extractors First (OK.ru, VidHide, StreamWish, Turbovid)
                // We pass EXACTLY what the extractor returns to avoid breaking OK.ru with forced headers
                val tempLinks = mutableListOf<ExtractorLink>()
                if (loadExtractor(cleanUrl, data, subtitleCallback) { tempLinks.add(it) }) {
                    for (link in tempLinks) {
                        callback(link)
                    }
                    found = true
                    continue
                }

                // B. Custom Handlers for Unsupported/Embedded Servers
                
                // AbyssPlayer Handler (Strip Referer to fix infinite buffering)
                if (cleanUrl.contains("abyssplayer.com", true) || cleanUrl.contains("abyss", true)) {
                    val html = app.get(cleanUrl).text
                    val mp4 = Regex("""<source[^>]+src=["']([^"']+\.mp4[^\s"'<>]*)["']""").find(html)?.groupValues?.get(1)
                        ?: Regex("""(?:file|src):\s*["']([^"']+\.mp4[^\s"'<>]*)["']""").find(html)?.groupValues?.get(1)
                    if (mp4 != null) {
                        callback(
                            ExtractorLink(
                                source = "AbyssPlayer",
                                name = "AbyssPlayer",
                                url = mp4,
                                referer = "", // Left blank intentionally to prevent Google/Cloudflare 403 blocks
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.VIDEO,
                                headers = emptyMap(),
                                extractorData = ""
                            )
                        )
                        found = true
                    }
                } 
                // Dailymotion Handler
                else if (cleanUrl.contains("dailymotion.com", true) || cleanUrl.contains("geo.dailymotion", true)) {
                    val vidId = Regex("""(?:video/|video=|embed/|/video/)([a-zA-Z0-9_]+)""").find(cleanUrl)?.groupValues?.get(1)
                    if (vidId != null) {
                        if (loadExtractor("https://www.dailymotion.com/video/$vidId", data, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                } 
                // Rumble Handler
                else if (cleanUrl.contains("rumble", true)) {
                    val html = app.get(cleanUrl).text
                    val m3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)?.value
                    if (m3u8 != null) {
                        val links = M3u8Helper.generateM3u8("Rumble", m3u8, cleanUrl)
                        for (link in links) {
                            callback(link)
                            found = true
                        }
                    }
                } 
                // D.Tube Handler
                else if (cleanUrl.contains("d.tube", true)) {
                    val vidId = Regex("""v=([a-zA-Z0-9-]+)""").find(cleanUrl)?.groupValues?.get(1) ?: cleanUrl.substringAfter("/videos/").substringBefore("/")
                    if (vidId.isNotBlank()) {
                        val m3u8 = "https://nas2.d.tube/videos/$vidId/master.m3u8"
                        val links = M3u8Helper.generateM3u8("DPlayer", m3u8, cleanUrl)
                        for (link in links) {
                            callback(link)
                            found = true
                        }
                    }
                } 
                // UPNS / CloudPlayer & P2PStream / FilePlayer Handlers
                else if (cleanUrl.contains("upns.live", true) || cleanUrl.contains("p2pstream.vip", true)) {
                    val host = URI(cleanUrl).host
                    val id = cleanUrl.substringAfterLast("/").substringBefore("?")
                    val apiCall = "https://$host/api/v1/video?id=$id&w=1280&h=800&r=animekhor.org"
                    try {
                        val apiRes = app.get(apiCall, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT)).text
                        val m3u8 = Regex("""https?://[^\s"'<>]+\.(?:m3u8|txt)[^\s"'<>]*""").find(apiRes)?.value
                        if (m3u8 != null) {
                            val links = M3u8Helper.generateM3u8("CloudPlayer", m3u8, "https://$host/")
                            for (link in links) {
                                callback(link)
                                found = true
                            }
                        }
                    } catch (e: Exception) {}
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return found
    }
}
