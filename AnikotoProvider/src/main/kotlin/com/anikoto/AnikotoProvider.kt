package com.anikoto

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import org.jsoup.Jsoup
import org.json.JSONObject

@CloudstreamPlugin
class AnikotoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnikotoProvider())
    }
}

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
            val a = element.selectFirst("a[href*=/watch/]") ?: element.selectFirst("a") ?: continue
            val url = fixUrlNull(a.attr("href")) ?: continue
            
            val title = element.selectFirst("h2.title, h2, .name, .title")?.text()?.trim()
                ?: element.selectFirst("a.name, a.title, .info a")?.text()?.trim()
                ?: "Unknown"

            var poster = element.selectFirst("img")?.attr("data-src")?.ifBlank { null }
                ?: element.selectFirst("img")?.attr("src")?.ifBlank { null }

            if (poster.isNullOrBlank()) {
                val style = element.selectFirst(".image div, .poster div, div[style*=background]")?.attr("style") ?: ""
                poster = Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)
            }
            
            items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster ?: ""
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
        val poster = document.selectFirst(".poster img")?.attr("src") 
            ?: document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val plot = document.selectFirst(".synopsis .content")?.text()?.trim() ?: ""

        val episodes = mutableListOf<Episode>()
        
        // 1. Parse released episodes directly from the HTML (#w-episodes)
        var epLinks = document.select("#w-episodes a[href*=/watch/], .episodes a[href*=/watch/], ul.ep-range a")
        
        // 2. Fallback: If on details page without #w-episodes, fetch the ep-1 page
        if (epLinks.isEmpty()) {
            val slug = url.substringAfter("/watch/").substringBefore("/ep-").substringBefore("?")
            try {
                val watchHtml = app.get("$mainUrl/watch/$slug/ep-1").text
                val watchDoc = Jsoup.parse(watchHtml)
                epLinks = watchDoc.select("#w-episodes a[href*=/watch/], .episodes a[href*=/watch/], ul.ep-range a")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        for (a in epLinks) {
            val href = fixUrlNull(a.attr("href")) ?: continue
            val epNum = a.attr("data-num").toIntOrNull() 
                ?: a.attr("data-slug").toIntOrNull()
                ?: Regex("""/ep-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            val epName = a.attr("title").ifBlank { "Episode $epNum" }
            val dataIds = a.attr("data-ids")

            episodes.add(
                newEpisode("$href#dataids=$dataIds") {
                    this.name = epName
                    this.episode = epNum
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
        val dataIdsFromUrl = if (data.contains("#dataids=")) data.substringAfter("#dataids=") else ""

        val html = app.get(cleanData).text
        val document = Jsoup.parse(html)

        // Grab data-ids from the current episode element if absent in URL
        val activeEp = document.selectFirst("#w-episodes a.active, .episodes a.active")
        val dataIds = dataIdsFromUrl.ifBlank { activeEp?.attr("data-ids") ?: "" }

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to cleanData,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        val linkIds = mutableSetOf<String>()

        // Call server list API using exact parameter name found in screenshot: servers=
        if (dataIds.isNotBlank()) {
            try {
                val res = app.get("$mainUrl/ajax/server/list?servers=$dataIds", headers = ajaxHeaders).text
                val doc = Jsoup.parse(if (res.contains("<")) res else JSONObject(res).optString("result", res))
                
                doc.select("li[data-link-id], li[data-id]").forEach { li ->
                    val id = li.attr("data-link-id").ifBlank { li.attr("data-id") }
                    if (id.isNotBlank()) linkIds.add(id)
                }
                
                if (linkIds.isEmpty()) {
                    Regex("""data-(?:link-)?id=["']([^"']+)["']""").findAll(res).forEach { m ->
                        linkIds.add(m.groupValues[1])
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Direct iframe fallback from HTML
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && src.startsWith("http")) {
                if (loadExtractor(src, cleanData, subtitleCallback, callback)) found = true
            }
        }

        val videoHeaders = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        for (id in linkIds) {
            try {
                val res = app.get("$mainUrl/ajax/server?get=$id", headers = ajaxHeaders).text
                val json = JSONObject(res)
                
                val streamUrl = json.optJSONObject("result")?.optString("url") 
                    ?: json.optString("result")
                    ?: json.optString("url")
                
                if (streamUrl.isNotBlank() && streamUrl.startsWith("http")) {
                    val isEmbedServer = streamUrl.contains("megaplay.buzz") || streamUrl.contains("vidtube.site") || streamUrl.contains("mewcdn.online") || streamUrl.contains("kwik")
                    
                    if (isEmbedServer) {
                        val embedHtml = app.get(streamUrl, headers = videoHeaders).text.replace("\\/", "/")
                        val m3u8Match = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""").find(embedHtml)
                        
                        if (m3u8Match != null) {
                            val mediaUrl = m3u8Match.value
                            callback(
                                newExtractorLink(
                                    source = "Anikoto",
                                    name = when {
                                        streamUrl.contains("vidtube") -> "Vidtube"
                                        streamUrl.contains("megaplay") -> "MegaPlay"
                                        else -> "Server Stream"
                                    },
                                    url = mediaUrl,
                                    type = if (mediaUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf("Referer" to streamUrl)
                                }
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
