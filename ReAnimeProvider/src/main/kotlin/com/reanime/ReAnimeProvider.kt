package com.reanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.json.JSONArray

class ReAnimeProvider : MainAPI() {
    override var mainUrl = "https://reanime.to"
    override var name = "Re:Anime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private fun parseAnimeArray(jsonArrayStr: String?): List<SearchResponse> {
        if (jsonArrayStr.isNullOrBlank()) return emptyList()
        val items = mutableListOf<SearchResponse>()
        try {
            val arr = JSONArray(jsonArrayStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("anime_id")
                if (id.isBlank()) continue
                
                val titleObj = obj.optJSONObject("title")
                val title = titleObj?.optString("english")?.ifBlank { null }
                    ?: titleObj?.optString("user_preferred")?.ifBlank { null }
                    ?: titleObj?.optString("romaji") ?: id
                    
                val coverObj = obj.optJSONObject("cover_image")
                val poster = coverObj?.optString("extra_large")?.ifBlank { null }
                    ?: coverObj?.optString("large") ?: ""
                    
                items.add(newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
                    this.posterUrl = poster.replace("\\/", "/")
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get("$mainUrl/home").text
        val homeItems = mutableListOf<HomePageList>()
        
        val sections = listOf(
            "latest_aired" to "Latest Episodes",
            "new_on_site" to "New on Site",
            "trending" to "Trending",
            "upcoming" to "Upcoming"
        )
        
        for ((key, title) in sections) {
            // SvelteKit stores the data arrays directly in the HTML script tag
            val regex = Regex(""""$key"\s*:\s*(\[.*?\])\s*,\s*"(?:[a-zA-Z0-9_]+_cursor|upcoming|new_on_site)"""")
            val match = regex.find(html)
            val items = parseAnimeArray(match?.groupValues?.get(1))
            if (items.isNotEmpty()) {
                homeItems.add(HomePageList(title, items))
            }
        }
        
        return newHomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.get("$mainUrl/search?q=$query").text
        val parts = html.split(Regex("""["']?anime_id["']?\s*:\s*["']"""))
        val items = mutableListOf<SearchResponse>()
        
        for (i in 1 until parts.size) {
            val part = parts[i]
            val id = part.substringBefore("\"").substringBefore("'")
            if (id.isBlank() || id.length > 200) continue
            
            val window = part.take(1500).replace("\\/", "/")
            
            var title = Regex(""""english"\s*:\s*"([^"]+)"""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex(""""user_preferred"\s*:\s*"([^"]+)"""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex(""""romaji"\s*:\s*"([^"]+)"""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = id.replace("-", " ").replaceFirstChar { it.uppercase() }
            
            var poster = Regex(""""extra_large"\s*:\s*"([^"]+)"""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex(""""large"\s*:\s*"([^"]+)"""").find(window)?.groupValues?.get(1) ?: ""
            
            val url = "$mainUrl/anime/$id"
            if (items.none { it.url == url }) {
                items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url).text
        val document = Jsoup.parse(html)
        
        var title = Regex(""""english"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        if (title.isNullOrBlank()) title = Regex(""""user_preferred"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        if (title.isNullOrBlank()) title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"

        var poster = Regex(""""extra_large"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        if (poster.isNullOrBlank()) poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        poster = poster.replace("\\/", "/")

        var plot = Regex(""""description"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(html)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\u003C", "<")
        if (plot.isNullOrBlank()) {
            plot = document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        } else {
            plot = Jsoup.parse(plot).text()
        }

        val episodes = mutableListOf<Episode>()
        // Re:Anime renders all episode links directly into the DOM
        document.select("a[href*=/watch/]").forEach { a ->
            val epHref = fixUrlNull(a.attr("href")) ?: return@forEach
            val epNum = a.attr("data-episode").toIntOrNull() ?: return@forEach
            val epName = a.selectFirst(".text-[13px] span")?.text() ?: "Episode $epNum"
            
            episodes.add(
                newEpisode(epHref) {
                    name = epName
                    episode = epNum
                    posterUrl = poster
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
        val html = app.get(data).text
        var found = false

        // 1. Hunt for FlixCloud URLs inside the page source
        val flixCloudMatches = Regex("""https?://(?:fetch7\.)?flixcloud\.cc[^\s"'<>\\]+""").findAll(html)
        flixCloudMatches.forEach { match ->
            val flixUrl = match.value.replace("\\/", "/")
            if (flixUrl.contains(".m3u8")) {
                callback(
                    newExtractorLink(
                        source = "FlixCloud",
                        name = "FlixCloud",
                        url = flixUrl,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
                found = true
            }
        }

        // 2. Extract Subtitles (VTT) if available
        val vttMatches = Regex("""https?://(?:fetch\.)?flixcloud\.cc/thumbnails_vtt/[^\s"'<>\\]+""").findAll(html)
        vttMatches.forEach { match ->
            val vttUrl = match.value.replace("\\/", "/")
            subtitleCallback(SubtitleFile("English", vttUrl))
        }

        // 3. Fallback: Search for standard video APIs (matching AniKage's architecture)
        if (!found) {
            val slug = data.substringAfter("/watch/").substringBefore("?")
            val ep = Regex("""[?&]ep=(\d+)""").find(data)?.groupValues?.get(1) ?: "1"

            try {
                val apiUrl = "$mainUrl/api/media/anime/$slug/episodes/$ep/sources?provider=flixcloud&lang=sub"
                val response = app.get(apiUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                
                val m3u8Regex = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
                m3u8Regex.findAll(response).forEach { match ->
                    callback(
                        newExtractorLink(
                            source = "ReAnime",
                            name = "Direct Stream",
                            url = match.value.replace("\\/", "/"),
                            referer = "$mainUrl/",
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    found = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return found
    }
}
