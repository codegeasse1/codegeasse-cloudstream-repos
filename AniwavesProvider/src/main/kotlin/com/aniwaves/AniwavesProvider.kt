package com.aniwaves

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject

class AniwavesProvider : MainAPI() {
    override var mainUrl = "https://aniwaves.ru"
    override var name = "Aniwaves"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Home",
        "$mainUrl/newest" to "Newest",
        "$mainUrl/added" to "Recently Added",
        "$mainUrl/completed" to "Completed",
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "X-Requested-With" to "XMLHttpRequest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get(request.data).text
        val document = Jsoup.parse(html)
        val homeItems = mutableListOf<HomePageList>()

        // Scrape Top Carousel (Hotest)
        if (request.name == "Home") {
            val sliderItems = document.select(".swiper-wrapper .swiper-slide.item").mapNotNull { parseSliderItem(it) }
            if (sliderItems.isNotEmpty()) {
                homeItems.add(HomePageList("Trending Now", sliderItems))
            }
        }

        // Scrape Grid Items
        val gridElements = document.select(".ani.items .item, .top-table .item")
        val standardItems = gridElements.mapNotNull { parseStandardItem(it) }
        
        if (standardItems.isNotEmpty()) {
            homeItems.add(HomePageList(request.name, standardItems))
        }

        return newHomePageResponse(homeItems)
    }

    private fun parseSliderItem(element: Element): SearchResponse? {
        val title = element.selectFirst(".title")?.text()?.trim() ?: return null
        val url = element.selectFirst(".actions a.play")?.attr("href") ?: return null
        val styleAttr = element.selectFirst(".image div")?.attr("style") ?: ""
        val poster = Regex("""url\(['"]?(.*?)['"]?\)""").find(styleAttr)?.groupValues?.get(1) ?: ""

        return newAnimeSearchResponse(title, "$mainUrl$url", TvType.Anime) {
            this.posterUrl = poster
        }
    }

    private fun parseStandardItem(element: Element): SearchResponse? {
        val titleElement = element.selectFirst(".name.d-title") ?: return null
        val title = titleElement.text().trim()
        val url = titleElement.attr("href") ?: return null
        var poster = element.selectFirst("img")?.attr("src") ?: ""
        if (poster.isBlank() || poster.contains("data:image")) {
            poster = element.selectFirst("img")?.attr("data-src") ?: ""
        }
        
        val subEps = element.selectFirst(".ep-status.sub span")?.text()?.trim()?.toIntOrNull()
        val dubEps = element.selectFirst(".ep-status.dub span")?.text()?.trim()?.toIntOrNull()

        return newAnimeSearchResponse(title, "$mainUrl$url", TvType.Anime) {
            this.posterUrl = poster
            addSub(subEps)
            addDub(dubEps)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/filter?keyword=$query"
        val document = Jsoup.parse(app.get(searchUrl).text)
        return document.select(".ani.items .item").mapNotNull { parseStandardItem(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = headers).text
        val document = Jsoup.parse(html)

        // 1. Extract Metadata
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        
        // 2. Extract Anime ID from URL (e.g., one-piece-81553 -> 81553)
        val animeId = url.substringAfterLast("-")

        // 3. Fetch AJAX Episode List
        val episodes = mutableListOf<Episode>()
        try {
            val epHtml = app.get("$mainUrl/ajax/episode/list/$animeId?vrf=", headers = headers).text
            val cleanEpHtml = if (epHtml.trim().startsWith("{")) JSONObject(epHtml).optString("result", epHtml) else epHtml
            
            val epDoc = Jsoup.parse(cleanEpHtml)
            
            epDoc.select("a[data-ep]").forEach { el ->
                val epNum = el.attr("data-ep").toIntOrNull() ?: return@forEach
                val epName = el.attr("title").ifBlank { "Episode $epNum" }
                
                episodes.add(
                    newEpisode("$mainUrl/watch?animeId=$animeId&epNum=$epNum") {
                        this.name = epName
                        this.episode = epNum
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        
        // Extract internal ID map from the payload we built in load()
        val animeId = Regex("""animeId=([^&]+)""").find(data)?.groupValues?.get(1) ?: return false
        val epNum = Regex("""epNum=([^&]+)""").find(data)?.groupValues?.get(1) ?: "1"

        try {
            // 1. Fetch Server List mapped to the specific Episode
            val serverUrl = "$mainUrl/ajax/server/list?servers=$animeId&eps=$epNum"
            val serverRes = app.get(serverUrl, headers = headers).text
            
            val cleanServerHtml = if (serverRes.trim().startsWith("{")) JSONObject(serverRes).optString("result", serverRes) else serverRes
            val serverDoc = Jsoup.parse(cleanServerHtml)

            // Extract the encrypted tokens for each server
            val serverTokens = serverDoc.select("[data-link]").map { it.attr("data-link") }.filter { it.isNotBlank() }

            // 2. Fetch Embed Source for each token
            for (token in serverTokens) {
                try {
                    val sourceUrl = "$mainUrl/ajax/sources?id=$token&asi=0&autoPlay=0"
                    val sourceRes = app.get(sourceUrl, headers = headers).text
                    
                    if (sourceRes.isBlank() || !sourceRes.trim().startsWith("{")) continue
                    
                    val json = JSONObject(sourceRes)
                    var embedUrl = json.optJSONObject("result")?.optString("url") ?: json.optString("url", "")
                    
                    if (embedUrl.isNotBlank()) {
                        embedUrl = embedUrl.replace("\\/", "/")
                        
                        // 3. DOMAIN SPOOFING (The Fix for Echovideo / FlixCloud)
                        // Converts proprietary domains into recognizable CloudStream Extractors
                        val vidplayUrl = embedUrl.replace(Regex("""https?://[^/]+"""), "https://vidplay.site")
                        val megacloudUrl = embedUrl.replace(Regex("""https?://[^/]+"""), "https://megacloud.tv")
                        
                        // Pass URLs to CloudStream's native decryption extractors
                        if (loadExtractor(vidplayUrl, data, subtitleCallback, callback)) found = true
                        if (!found && loadExtractor(megacloudUrl, data, subtitleCallback, callback)) found = true
                        if (!found && loadExtractor(embedUrl, data, subtitleCallback, callback)) found = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return found
    }
}
