package com.anikoto

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import org.jsoup.Jsoup
import org.json.JSONObject
import android.util.Base64

@CloudstreamPlugin
class AnikotoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnikotoProvider())
    }
}

class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikoto.cz"
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
        val animeId = document.selectFirst("#watch-main")?.attr("data-id") ?: ""
        val slug = url.substringAfter("/watch/").substringBefore("/ep-").substringBefore("?")

        if (animeId.isNotBlank()) {
            val ajaxHeaders = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to url)
            val endpoints = listOf(
                "$mainUrl/ajax/episode/list/$animeId",
                "$mainUrl/ajax/v2/episode/list/$animeId",
                "$mainUrl/ajax/episode/list?id=$animeId"
            )

            for (epUrl in endpoints) {
                try {
                    val res = app.get(epUrl, headers = ajaxHeaders).text
                    val htmlString = if (res.trim().startsWith("{")) {
                        val json = JSONObject(res)
                        json.optString("html", json.optString("result", res))
                    } else res
                    
                    val epDoc = Jsoup.parse(htmlString)
                    val links = epDoc.select("a[data-num], a[data-slug], a[href*=/watch/]")
                    
                    for (a in links) {
                        val href = fixUrlNull(a.attr("href")) ?: continue
                        val epNum = a.attr("data-num").toIntOrNull() 
                            ?: a.attr("data-slug").toIntOrNull()
                            ?: Regex("""/ep-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                            ?: continue
                        val epName = a.attr("title").ifBlank { a.text().trim() }.ifBlank { "Episode $epNum" }
                        val dataIds = a.attr("data-ids")

                        val finalUrl = if (dataIds.isNotBlank()) "$href${if(href.contains("?")) "&" else "?"}dataids=$dataIds" else href

                        episodes.add(
                            newEpisode(finalUrl) {
                                this.name = epName
                                this.episode = epNum
                            }
                        )
                    }
                    if (episodes.isNotEmpty()) break
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        if (episodes.isEmpty()) {
            val infoBlock = document.selectFirst(".binfo, #w-info, .anime-info")
            val epsDiv = infoBlock?.select("div.meta > div")?.firstOrNull { it.text().contains("Episodes:") }
            val totalEps = epsDiv?.selectFirst("span")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 1
            
            for (i in 1..totalEps) {
                episodes.add(
                    newEpisode("$mainUrl/watch/$slug/ep-$i") {
                        this.name = "Episode $i"
                        this.episode = i
                    }
                )
            }
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
        val dataIdsFromUrl = Regex("""[?&]dataids=([^&]+)""").find(data)?.groupValues?.get(1) ?: ""
        val cleanData = data.substringBefore("?dataids=").substringBefore("&dataids=").substringBefore("#")

        val html = app.get(cleanData).text
        val document = Jsoup.parse(html)

        val activeEp = document.selectFirst("#w-episodes a.active, .episodes a.active")
        val dataIds = dataIdsFromUrl.ifBlank { activeEp?.attr("data-ids") ?: "" }

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to cleanData,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        val linkIds = mutableSetOf<String>()

        if (dataIds.isNotBlank()) {
            try {
                val res = app.get("$mainUrl/ajax/server/list?servers=$dataIds", headers = ajaxHeaders).text
                val doc = Jsoup.parse(if (res.contains("<")) res else JSONObject(res).optString("result", res))
                
                // Extract data-link-id attributes (these are base64-encoded server IDs)
                val serverItems = doc.select("li[data-link-id], li[data-id]")
                for (li in serverItems) {
                    val id = li.attr("data-link-id").ifBlank { li.attr("data-id") }
                    if (id.isNotBlank()) linkIds.add(id)
                }
                
                if (linkIds.isEmpty()) {
                    val regexMatches = Regex("""data-(?:link-)?id=["']([^"']+)["']""").findAll(res)
                    for (m in regexMatches) {
                        linkIds.add(m.groupValues[1])
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

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
                    val isCloneEmbed = streamUrl.contains("megaplay") || streamUrl.contains("vidtube") || 
                                     streamUrl.contains("mewcdn") || streamUrl.contains("kwik") || 
                                     streamUrl.contains("vidplay")
                    
                    if (isCloneEmbed) {
                        val embedHtml = app.get(streamUrl, headers = videoHeaders).text.replace("\\/", "/")
                        
                        var m3u8Match = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""").find(embedHtml)
                        
                        if (m3u8Match == null && embedHtml.contains("eval(function(p,a,c,k,e,d)")) {
                            try {
                                val unpacked = getAndUnpack(embedHtml)
                                m3u8Match = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""").find(unpacked)
                            } catch (e: Exception) {}
                        }

                        if (m3u8Match == null) {
                            val base64Regex = Regex("""["'](aHR0cHM6Ly[a-zA-Z0-9+/=]+)["']""").findAll(embedHtml)
                            for (b64 in base64Regex) {
                                try {
                                    val decoded = String(Base64.decode(b64.groupValues[1], Base64.DEFAULT))
                                    if (decoded.contains(".m3u8") || decoded.contains(".mp4")) {
                                        m3u8Match = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""").find(decoded)
                                        break
                                    }
                                } catch (e: Exception) {}
                            }
                        }

                        if (m3u8Match == null) {
                            try {
                                val host = "https://" + java.net.URI(streamUrl).host
                                val dataId = Regex("""data-id=["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)
                                    ?: Regex("""['"]?id['"]?\s*:\s*['"]([^"']+)['"]""").find(embedHtml)?.groupValues?.get(1)
                                    ?: streamUrl.substringAfterLast("/").substringBefore("?")
                                
                                if (dataId.isNotBlank() && dataId.length > 2) {
                                    val apis = listOf("/ajax/embed-4/getSources?id=", "/ajax/embed-5/getSources?id=", "/api/source/")
                                    for (api in apis) {
                                        try {
                                            val apiRes = app.get("$host$api$dataId", headers = mapOf("X-Requested-With" to "XMLHttpRequest")).text.replace("\\/", "/")
                                            m3u8Match = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""").find(apiRes)
                                            if (m3u8Match != null) break
                                        } catch (e: Exception) {}
                                    }
                                }
                            } catch (e: Exception) {
                                // URI parsing failed, continue
                            }
                        }

                        if (m3u8Match != null) {
                            val mediaUrl = m3u8Match.value
                            try {
                                val serverName = java.net.URI(streamUrl).host.substringBeforeLast(".")
                                callback(
                                    newExtractorLink(
                                        source = "Anikoto Native",
                                        name = serverName,
                                        url = mediaUrl,
                                        type = if (mediaUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.headers = mapOf("Referer" to streamUrl)
                                    }
                                )
                                found = true
                            } catch (e: Exception) {
                                // Fallback if URI parsing fails
                                callback(
                                    newExtractorLink(
                                        source = "Anikoto Native",
                                        name = "Server",
                                        url = mediaUrl,
                                        type = if (mediaUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.headers = mapOf("Referer" to streamUrl)
                                    }
                                )
                                found = true
                            }
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