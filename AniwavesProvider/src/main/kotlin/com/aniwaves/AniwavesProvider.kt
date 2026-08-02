package com.aniwaves

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject
import org.json.JSONArray

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

        if (request.name == "Home") {
            val sliderItems = document.select(".swiper-wrapper .swiper-slide.item").mapNotNull { parseSliderItem(it) }
            if (sliderItems.isNotEmpty()) {
                homeItems.add(HomePageList("Trending Now", sliderItems, isHorizontalImages = true))
            }
        }

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
            
            val serverJson = try { JSONObject(serverRes) } catch (e: Exception) { null }
            val serverHtml = serverJson?.optString("result")?.ifBlank { serverJson.optString("html") } ?: serverRes
            val serverDoc = Jsoup.parse(serverHtml)

            val serverIds = mutableSetOf<String>()
            serverDoc.select("a, div, li, span").forEach { el ->
                val id = el.attr("data-link").ifBlank { el.attr("data-id") }.ifBlank { el.attr("data-server") }
                if (id.isNotBlank() && id.length > 3) {
                    serverIds.add(id)
                }
            }
            
            if (serverIds.isEmpty()) {
                Regex("""data-(?:link-)?id=["']([^"']+)["']""").findAll(serverHtml).forEach { m ->
                    serverIds.add(m.groupValues[1])
                }
            }

            for (id in serverIds) {
                val apiEndpoints = listOf(
                    "$mainUrl/ajax/sources?id=$id&asi=0&autoPlay=0",
                    "$mainUrl/ajax/server?get=$id",
                    "$mainUrl/ajax/episode/sources?id=$id"
                )
                
                for (apiUrl in apiEndpoints) {
                    try {
                        val sourceRes = app.get(apiUrl, headers = headers).text
                        if (!sourceRes.trim().startsWith("{")) continue
                        
                        val json = JSONObject(sourceRes)
                        val result = json.optJSONObject("result") ?: json
                        var embedUrl = result.optString("url").ifBlank { result.optString("link") }
                        
                        if (embedUrl.isNotBlank()) {
                            embedUrl = embedUrl.replace("\\/", "/")
                            if (embedUrl.startsWith("/")) embedUrl = "https:$embedUrl"

                            // ======================================================
                            // 1. DYNAMIC DOMAIN EXTRACTOR (Solves the gn1r5n.org 2001 Error)
                            // ======================================================
                            val embedMatch = Regex("""https?://([^/]+)/(embed-\d+)/([\w-]+)""").find(embedUrl)
                            
                            if (embedMatch != null) {
                                val host = embedMatch.groupValues[1]       // Captures echovideo.ru, gn1r5n.org, etc.
                                val segment = embedMatch.groupValues[2]    // Captures embed-1, embed-20, etc.
                                val echoId = embedMatch.groupValues[3]
                                val echoApiUrl = "https://$host/$segment/getSources?id=$echoId"
                                
                                val echoRes = app.get(
                                    echoApiUrl,
                                    headers = mapOf(
                                        "User-Agent" to headers["User-Agent"]!!,
                                        "Referer" to embedUrl,
                                        "X-Requested-With" to "XMLHttpRequest"
                                    )
                                ).text

                                if (echoRes.trim().startsWith("{")) {
                                    val echoJson = JSONObject(echoRes)
                                    val extractedUrls = mutableListOf<String>()

                                    val sourcesOpt = echoJson.opt("sources")
                                    if (sourcesOpt is String && sourcesOpt.isNotBlank()) {
                                        extractedUrls.add(sourcesOpt.replace("\\/", "/"))
                                    } else if (sourcesOpt is JSONObject) {
                                        listOf("HD", "SD", "HQ").forEach { q ->
                                            val arr = sourcesOpt.optJSONArray(q)
                                            if (arr != null) {
                                                for (i in 0 until arr.length()) extractedUrls.add(arr.getString(i).replace("\\/", "/"))
                                            }
                                        }
                                    } else if (sourcesOpt is JSONArray) {
                                        for (i in 0 until sourcesOpt.length()) {
                                            val obj = sourcesOpt.optJSONObject(i)
                                            if (obj != null) extractedUrls.add(obj.optString("file").replace("\\/", "/"))
                                        }
                                    }

                                    for (streamUrl in extractedUrls) {
                                        if (streamUrl.isBlank()) continue
                                        val isM3u8 = streamUrl.contains("m3u8", ignoreCase = true)

                                        callback(
                                            newExtractorLink(
                                                source = "Aniwaves ($host)",
                                                name = "Aniwaves Server",
                                                url = streamUrl,
                                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) {
                                                this.quality = Qualities.Unknown.value
                                                // INJECT EXACT REQUIRED ORIGIN HEADERS TO PREVENT CDN DROPS
                                                this.headers = mapOf(
                                                    "Origin" to "https://$host",
                                                    "Referer" to "https://$host/",
                                                    "User-Agent" to headers["User-Agent"]!!,
                                                    "Accept" to "*/*"
                                                )
                                            }
                                        )
                                        found = true
                                    }
                                }
                            }
                            
                            // ======================================================
                            // 2. NATIVE EXTRACTOR FALLBACK
                            // ======================================================
                            if (!found) {
                                if (loadExtractor(embedUrl, data, subtitleCallback, callback)) found = true
                            }
                            
                            // ======================================================
                            // 3. BRUTE-FORCE JS UNPACKER FALLBACK
                            // ======================================================
                            if (!found) {
                                try {
                                    val embedHtml = app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/")).text.replace("\\/", "/")
                                    var m3u8Match = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""").find(embedHtml)
                                    
                                    if (m3u8Match == null && embedHtml.contains("eval(function(p,a,c,k,e,d)")) {
                                        try {
                                            val unpacked = getAndUnpack(embedHtml)
                                            m3u8Match = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""").find(unpacked)
                                        } catch (e: Exception) {}
                                    }

                                    if (m3u8Match != null) {
                                        val mediaUrl = m3u8Match.value
                                        callback(
                                            newExtractorLink(
                                                source = "Aniwaves Native",
                                                name = java.net.URI(embedUrl).host.substringBeforeLast("."),
                                                url = mediaUrl,
                                                type = if (mediaUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) {
                                                this.quality = Qualities.Unknown.value
                                                this.headers = mapOf("Referer" to embedUrl)
                                            }
                                        )
                                        found = true
                                    }
                                } catch (e: Exception) {}
                            }
                        }
                    } catch (e: Exception) {}
                    
                    if (found) break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return found
    }
}
