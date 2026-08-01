package com.anikoto

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.json.JSONObject

class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikototv.to"
    override var name = "Anikoto"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private fun extractItems(html: String, selector: String): List<SearchResponse> {
        val document = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()
        
        val elements = document.select(selector)
        for (element in elements) {
            val a = element.selectFirst("a.name, a.title, .info a") ?: element.selectFirst("a") ?: continue
            val url = fixUrlNull(a.attr("href")) ?: continue
            val title = a.text().trim()
            val poster = element.selectFirst("img")?.attr("src") ?: ""
            
            items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
            })
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get("$mainUrl/home").text
        val homeItems = mutableListOf<HomePageList>()

        val latest = extractItems(html, "#recent-update .item")
        if (latest.isNotEmpty()) homeItems.add(HomePageList("Latest Episodes", latest))

        val trending = extractItems(html, "#hotest .item, .w-side-section .item")
        if (trending.isNotEmpty()) homeItems.add(HomePageList("Trending", trending))

        val newAdded = extractItems(html, ".top-table[data-name=new-release] .item, .top-table[data-name=new-added] .item")
        if (newAdded.isNotEmpty()) homeItems.add(HomePageList("New Added", newAdded))

        return newHomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.get("$mainUrl/filter?keyword=$query").text
        return extractItems(html, ".item")
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url).text
        val document = Jsoup.parse(html)

        val title = document.selectFirst("h1.title")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst(".poster img")?.attr("src") ?: document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val plot = document.selectFirst(".synopsis .content")?.text()?.trim() ?: ""

        val episodes = mutableListOf<Episode>()
        val slug = url.substringAfter("/watch/").substringBefore("/ep-").substringBefore("?")
        
        // Extract total episodes from info text
        val episodesCountStr = document.select("div.meta:contains(Episodes:) span").text().replace(Regex("[^0-9]"), "")
        val totalEps = episodesCountStr.toIntOrNull() ?: 1

        for (i in 1..totalEps) {
            episodes.add(
                newEpisode("$mainUrl/watch/$slug/ep-$i") {
                    this.name = "Episode $i"
                    this.episode = i
                }
            )
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
        val cleanData = data.substringBefore("#")
        val html = app.get(cleanData).text
        val document = Jsoup.parse(html)

        val animeId = document.selectFirst("#watch-main")?.attr("data-id") ?: ""
        val epNum = Regex("""/ep-(\d+)""").find(cleanData)?.groupValues?.get(1) ?: "1"

        if (animeId.isBlank()) return false

        // 1. Guess the correct server list endpoints based on typical Clone architectures
        val endpoints = listOf(
            "$mainUrl/ajax/server/list?anime_id=$animeId&ep=$epNum",
            "$mainUrl/ajax/server/list?manga_id=$animeId&ep=$epNum",
            "$mainUrl/ajax/episode/servers?episodeId=$animeId"
        )

        val linkIds = mutableSetOf<String>()

        for (endpoint in endpoints) {
            try {
                val res = app.get(endpoint).text
                val matches = Regex("""data-link-id=["']([^"']+)["']""").findAll(res)
                for (match in matches) {
                    linkIds.add(match.groupValues[1])
                }
                if (linkIds.isNotEmpty()) break
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val videoHeaders = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        // 2. Fetch the stream URLs from the encrypted IDs
        for (id in linkIds) {
            try {
                val res = app.get("$mainUrl/ajax/server?get=$id").text
                val json = JSONObject(res)
                
                // Usually returns {"status": 200, "result": {"url": "https://megaplay.buzz/..."}}
                val streamUrl = json.optJSONObject("result")?.optString("url") ?: json.optString("url")
                
                if (streamUrl.isNotBlank() && streamUrl.startsWith("http")) {
                    val isMegaplayOrVidtube = streamUrl.contains("megaplay.buzz") || streamUrl.contains("vidtube.site")
                    
                    if (isMegaplayOrVidtube) {
                        // Natively extract the .m3u8 from the embed page to bypass CloudStream Extractor issues
                        val embedHtml = app.get(streamUrl, headers = videoHeaders).text.replace("\\/", "/")
                        val m3u8Match = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(embedHtml)
                        
                        if (m3u8Match != null) {
                            callback(
                                ExtractorLink(
                                    source = "Anikoto",
                                    name = if (streamUrl.contains("vidtube")) "Vidtube" else "MegaPlay",
                                    url = m3u8Match.value,
                                    referer = streamUrl,
                                    quality = Qualities.Unknown.value,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                            found = true
                        } else {
                            if (loadExtractor(streamUrl, cleanData, subtitleCallback, callback)) found = true
                        }
                    } else {
                        if (loadExtractor(streamUrl, cleanData, subtitleCallback, callback)) found = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return found
    }
}
