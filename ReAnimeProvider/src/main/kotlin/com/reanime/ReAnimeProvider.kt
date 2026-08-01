package com.reanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject

class ReAnimeProvider : MainAPI() {
    override var mainUrl = "https://reanime.to"
    override var name = "Re:Anime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // Extracts SvelteKit arrays even if the keys aren't wrapped in quotes
    private fun extractSectionArray(html: String, key: String): List<SearchResponse> {
        val match = Regex("""["']?$key["']?\s*:\s*\[""").find(html) ?: return emptyList()
        val startIdx = html.indexOf('[', match.range.first)
        if (startIdx == -1) return emptyList()

        var bracketCount = 0
        var endIdx = -1
        for (i in startIdx until html.length) {
            if (html[i] == '[') bracketCount++
            else if (html[i] == ']') bracketCount--

            if (bracketCount == 0) {
                endIdx = i + 1
                break
            }
        }
        if (endIdx == -1) return emptyList()

        val arrayStr = html.substring(startIdx, endIdx)
        val items = mutableListOf<SearchResponse>()
        val parts = arrayStr.split(Regex("""["']?anime_id["']?\s*:\s*["']"""))

        for (i in 1 until parts.size) {
            val part = parts[i]
            val animeId = part.substringBefore("\"").substringBefore("'")
            if (animeId.isBlank() || animeId.length > 200) continue

            val window = part.take(2000).replace("\\/", "/")

            var title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?user_preferred["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?romaji["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?native["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = animeId.replace("-", " ").replaceFirstChar { it.uppercase() }

            var poster = Regex("""["']?extra_large["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""["']?large["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""["']?medium["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""

            val url = "$mainUrl/anime/$animeId"
            if (items.none { it.url == url }) {
                items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster.replace("\\/", "/")
                })
            }
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
            val items = extractSectionArray(html, key)
            if (items.isNotEmpty()) {
                homeItems.add(HomePageList(title, items))
            }
        }

        return newHomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.get("$mainUrl/search?q=$query").text
        val items = mutableListOf<SearchResponse>()
        val parts = html.split(Regex("""["']?anime_id["']?\s*:\s*["']"""))

        for (i in 1 until parts.size) {
            val part = parts[i]
            val animeId = part.substringBefore("\"").substringBefore("'")
            if (animeId.isBlank() || animeId.length > 200) continue

            val window = part.take(2000).replace("\\/", "/")

            var title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?user_preferred["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?romaji["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = animeId.replace("-", " ").replaceFirstChar { it.uppercase() }

            var poster = Regex("""["']?extra_large["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""["']?large["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""

            val url = "$mainUrl/anime/$animeId"
            if (items.none { it.url == url }) {
                items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster.replace("\\/", "/")
                })
            }
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url).text
        val document = Jsoup.parse(html)
        val slug = url.substringAfter("/anime/").substringBefore("?")

        var title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
        if (title.isNullOrBlank()) title = Regex("""["']?user_preferred["']?\s*:\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
        if (title.isNullOrBlank()) title = document.selectFirst("h1")?.text()?.trim() ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

        var poster = Regex("""["']?extra_large["']?\s*:\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
        if (poster.isNullOrBlank()) poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        poster = poster.replace("\\/", "/")

        var plot = Regex("""["']?description["']?\s*:\s*["']((?:[^"'\\]|\\.)*)["']""").find(html)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\u003C", "<")
        if (plot.isNullOrBlank()) {
            plot = document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        } else {
            plot = Jsoup.parse(plot).text()
        }

        // Extract Anilist ID to use in API calls later
        val aniIdMatch = Regex(""""anilist_id"\s*:\s*(\d+)""").find(html) 
            ?: Regex(""""anilist"\s*:\s*(\d+)""").find(html)
        val anilistId = aniIdMatch?.groupValues?.get(1) ?: ""

        val episodes = mutableListOf<Episode>()

        // Fetch all episodes from the native JSON API
        try {
            val epsRes = app.get("$mainUrl/api/v1/anime/$slug/episodes?limit=2000").text
            val jsonArray = if (epsRes.trim().startsWith("[")) {
                JSONArray(epsRes)
            } else {
                JSONObject(epsRes).optJSONArray("data") ?: JSONObject(epsRes).optJSONArray("episodes") ?: JSONArray()
            }
            
            for (i in 0 until jsonArray.length()) {
                val epObj = jsonArray.getJSONObject(i)
                val epNum = epObj.optInt("episode_number", -1)
                if (epNum == -1) continue
                
                val epTitle = epObj.optString("title", "").ifBlank { "Episode $epNum" }
                val thumbnail = epObj.optString("thumbnail", "")
                
                episodes.add(
                    newEpisode("$mainUrl/watch/$slug?ep=$epNum&ani=$anilistId") {
                        name = epTitle
                        episode = epNum
                        posterUrl = thumbnail.ifBlank { poster }
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback if API fails
        if (episodes.isEmpty()) {
            document.select("a[href*=/watch/]").forEach { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@forEach
                val epNum = Regex("""[?&]ep=(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: a.attr("data-episode").toIntOrNull()
                    ?: 1
                val epName = a.selectFirst(".text-[13px] span")?.text() ?: "Episode $epNum"
                episodes.add(
                    newEpisode("$epHref&ani=$anilistId") {
                        name = epName
                        episode = epNum
                        posterUrl = poster
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
        val cleanData = data.substringBefore("#")
        val epNum = Regex("""[?&]ep=(\d+)""").find(cleanData)?.groupValues?.get(1) ?: "1"
        val aniId = Regex("""[?&]ani=(\d+)""").find(cleanData)?.groupValues?.get(1) ?: ""

        val videoHeaders = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        if (aniId.isNotBlank()) {
            // 1. Fetch encrypted FlixCloud ID from Re:Anime API
            try {
                val flixRes = app.get("$mainUrl/api/flix/$aniId/$epNum", headers = videoHeaders).text
                
                // Look for the unique FlixCloud/MegaCloud ID
                val idMatch = Regex("""flixcloud\.cc/(?:watch|embed|api/m3u8)/([a-zA-Z0-9]+)""").find(flixRes)
                    ?: Regex(""""(?:id|url)"\s*:\s*"([a-zA-Z0-9]{15,})"""").find(flixRes)
                    ?: Regex("""([a-zA-Z0-9]{15,})""").find(flixRes)

                if (idMatch != null) {
                    val flixId = idMatch.groupValues[1]
                    // TRICK: CloudStream's MegaCloud extractor uses the exact same decryption algorithm as FlixCloud.
                    // By passing a fake megacloud.tv URL, we trigger the native decryptor.
                    val fakeMegaUrl = "https://megacloud.tv/embed-2/e-1/$flixId"
                    if (loadExtractor(fakeMegaUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Fetch from Re:Anime Downloads Check API (Direct MP4/M3U8 fallback)
            try {
                val dlRes = app.get("$mainUrl/api/v1/downloads/check?anilist_id=$aniId&episode=$epNum", headers = videoHeaders).text
                Regex("""https?://[^\s"'<>\\]+\.(?:mp4|mkv|m3u8)[^\s"'<>\\]*""").findAll(dlRes.replace("\\/", "/")).forEach { match ->
                    val isM3u8 = match.value.contains(".m3u8")
                    callback(
                        newExtractorLink(
                            source = "Re:Anime Direct",
                            name = "Direct Stream",
                            url = match.value,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = videoHeaders
                        }
                    )
                    found = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. Absolute Fallback: Scan the watch page HTML
        if (!found) {
            try {
                val html = app.get(cleanData).text
                Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").findAll(html.replace("\\/", "/")).forEach { match ->
                    callback(
                        newExtractorLink(
                            source = "Re:Anime",
                            name = "Fallback Stream",
                            url = match.value,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = videoHeaders
                        }
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
