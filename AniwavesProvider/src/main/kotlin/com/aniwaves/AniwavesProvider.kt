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
        val html = app.get(request.data, headers = headers).text
        val document = Jsoup.parse(html)
        val homeItems = mutableListOf<HomePageList>()

        // Scrape Top Carousel (Trending) - Flagged as Horizontal to prevent stretching
        if (request.name == "Home") {
            val sliderItems = document.select(".swiper-wrapper .swiper-slide.item").mapNotNull { parseSliderItem(it) }
            if (sliderItems.isNotEmpty()) {
                homeItems.add(HomePageList("Trending Now", sliderItems, isHorizontalImages = true))
            }
        }

        // Scrape Standard Grid Items
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
        val document = Jsoup.parse(app.get(searchUrl, headers = headers).text)
        return document.select(".ani.items .item").mapNotNull { parseStandardItem(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = headers).text
        val document = Jsoup.parse(html)

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        
        val animeId = url.substringAfterLast("-")
        val episodes = mutableListOf<Episode>()

        try {
            val epHtml = app.get("$mainUrl/ajax/episode/list/$animeId?vrf=", headers = headers).text
            val cleanEpHtml = if (epHtml.trim().startsWith("{")) JSONObject(epHtml).optString("result", epHtml) else epHtml
            val epDoc = Jsoup.parse(cleanEpHtml)
            
            epDoc.select("a").forEach { el ->
                val rawNum = el.attr("data-num").ifBlank { el.attr("data-ep") }.ifBlank { el.text() }
                val epNum = Regex("""\d+""").find(rawNum)?.value?.toIntOrNull() ?: return@forEach
                
                var epName = el.attr("title").ifBlank { el.text() }.trim()
                if (epName.isBlank() || epName.matches(Regex("""^\d+$"""))) {
                    epName = "Episode $epNum"
                }
                
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
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.episode })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val animeId = Regex("""animeId=([^&]+)""").find(data)?.groupValues?.get(1) ?: return false
        val epNum = Regex("""epNum=([^&]+)""").find(data)?.groupValues?.get(1) ?: "1"

        try {
            val serverUrl = "$mainUrl/ajax/server/list?servers=$animeId&eps=$epNum"
            val serverRes = app.get(serverUrl, headers = headers).text
            
            // Clean the server response whether it's raw HTML or wrapped in JSON
            val serverJson = try { JSONObject(serverRes) } catch (e: Exception) { null }
            val serverHtml = serverJson?.optString("result")?.ifBlank { serverJson.optString("html") } ?: serverRes
            val serverDoc = Jsoup.parse(serverHtml)

            // Aggressively capture all server tokens across different UI layouts
            val serverIds = mutableSetOf<String>()
            serverDoc.select("a, div, li, span").forEach { el ->
                val id = el.attr("data-link").ifBlank { el.attr("data-id") }.ifBlank { el.attr("data-server") }
                if (id.isNotBlank() && id.length > 3) {
                    serverIds.add(id)
                }
            }

            for (id in serverIds) {
                // Try multiple common AJAX source endpoints used by Aniwave clones
                val sourceEndpoints = listOf(
                    "$mainUrl/ajax/sources?id=$id&asi=0&autoPlay=0",
                    "$mainUrl/ajax/episode/sources?id=$id"
                )
                
                for (sourceUrl in sourceEndpoints) {
                    try {
                        val sourceRes = app.get(sourceUrl, headers = headers).text
                        if (!sourceRes.trim().startsWith("{")) continue
                        
                        val json = JSONObject(sourceRes)
                        val result = json.optJSONObject("result") ?: json
                        var embedUrl = result.optString("url").ifBlank { result.optString("link") }
                        
                        if (embedUrl.isNotBlank()) {
                            embedUrl = embedUrl.replace("\\/", "/")
                            if (embedUrl.startsWith("/")) embedUrl = "https:$embedUrl"
                            
                            // CORE FIX: Intercept Echovideo/Flixcloud URLs and forcefully translate the domain
                            val embedIdMatch = Regex("""(?:/e/|/embed-\d+/|/v/|\?id=)([\w-]+)""").find(embedUrl)
                            if (embedIdMatch != null) {
                                val embedId = embedIdMatch.groupValues[1]
                                
                                // Route through CloudStream's verified built-in extractors
                                val vidplayUrl = "https://vidplay.site/e/$embedId"
                                val megacloudUrl = "https://megacloud.tv/embed-2/e-1/$embedId"
                                val filemoonUrl = "https://filemoon.sx/e/$embedId"
                                
                                if (loadExtractor(vidplayUrl, data, subtitleCallback, callback)) found = true
                                if (!found && loadExtractor(megacloudUrl, data, subtitleCallback, callback)) found = true
                                if (!found && loadExtractor(filemoonUrl, data, subtitleCallback, callback)) found = true
                            }
                            
                            // Safety Fallback: Attempt to extract the raw unmodified URL
                            if (!found && loadExtractor(embedUrl, data, subtitleCallback, callback)) found = true
                        }
                    } catch (e: Exception) {
                        continue
                    }
                    if (found) break // Prevent hitting redundant endpoints if one works
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return found
    }
}
