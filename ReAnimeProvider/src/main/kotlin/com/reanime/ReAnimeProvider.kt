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
        val slug = url.substringAfter("/anime/").substringAfter("/watch/").substringBefore("?")

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

        val episodes = mutableListOf<Episode>()

        // 1. Try to extract the full episodes list from the embedded page JSON
        val epsDataMatch = Regex(""""episodes"\s*:\s*(\[\{.*?\}\])""").find(html)
        if (epsDataMatch != null) {
            try {
                val epsArray = JSONArray(epsDataMatch.groupValues[1])
                for (i in 0 until epsArray.length()) {
                    val epObj = epsArray.getJSONObject(i)
                    val epNum = epObj.optInt("episode_number", epObj.optInt("number", -1))
                    if (epNum == -1) continue
                    
                    val epTitle = epObj.optString("title", "").ifBlank { "Episode $epNum" }
                    val thumbnail = epObj.optString("thumbnail", "")
                    
                    episodes.add(
                        newEpisode("$mainUrl/watch/$slug?ep=$epNum") {
                            this.name = epTitle
                            this.episode = epNum
                            this.posterUrl = thumbnail.ifBlank { poster }
                        }
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Fallback to API if embedded JSON fails
        if (episodes.isEmpty()) {
            try {
                val epsRes = app.get("$mainUrl/api/v1/anime/$slug/episodes?limit=2000").text
                val jsonArray = if (epsRes.trim().startsWith("[")) {
                    JSONArray(epsRes)
                } else {
                    JSONObject(epsRes).optJSONArray("data") ?: JSONObject(epsRes).optJSONArray("episodes") ?: JSONArray()
                }
                for (i in 0 until jsonArray.length()) {
                    val epObj = jsonArray.getJSONObject(i)
                    val epNum = epObj.optInt("episode_number", epObj.optInt("number", -1))
                    if (epNum == -1) continue
                    
                    val epTitle = epObj.optString("title", "").ifBlank { "Episode $epNum" }
                    val thumbnail = epObj.optString("thumbnail", "")
                    
                    episodes.add(
                        newEpisode("$mainUrl/watch/$slug?ep=$epNum") {
                            this.name = epTitle
                            this.episode = epNum
                            this.posterUrl = thumbnail.ifBlank { poster }
                        }
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
        val slug = cleanData.substringAfter("/watch/").substringBefore("?")
        val epNum = Regex("""[?&]ep=(\d+)""").find(cleanData)?.groupValues?.get(1) ?: "1"

        val videoHeaders = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        val extractedUrls = hashSetOf<String>()
        val streamRegex = Regex("""(https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*|https?://prox\.anicore\.tv/stream/[^\s"'<>\\]+)""")

        // Helper to add links without duplicates
        val addStream = { url: String, name: String ->
            val cleanUrl = url.replace("\\/", "/")
            if (!extractedUrls.contains(cleanUrl)) {
                extractedUrls.add(cleanUrl)
                val isM3u8 = cleanUrl.contains(".m3u8", true) || cleanUrl.contains("prox.anicore", true)
                callback(
                    newExtractorLink(
                        source = "Re:Anime",
                        name = name,
                        url = cleanUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = videoHeaders
                    }
                )
                found = true
            }
        }

        // 1. Check primary watch page (HD-1)
        try {
            val watchHtml = app.get(cleanData, headers = videoHeaders).text
            
            // Extract raw streams from HTML state
            streamRegex.findAll(watchHtml).forEach { match ->
                addStream(match.groupValues[1], "Direct Stream 1")
            }

            // Extract Iframes
            Jsoup.parse(watchHtml).select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.startsWith("http")) {
                    loadExtractor(src, cleanData, subtitleCallback, callback)
                    found = true
                }
            }

            // 2. Hit the Downloads API as a bypass
            val aniId = Regex(""""anilist(?:_id)?"\s*:\s*(\d+)""").find(watchHtml)?.groupValues?.get(1) ?: ""
            val malId = Regex(""""mal(?:_id)?"\s*:\s*(\d+)""").find(watchHtml)?.groupValues?.get(1) ?: ""
            if (aniId.isNotBlank()) {
                val dlRes = app.get("$mainUrl/api/v1/downloads/check?anilist_id=$aniId&mal_id=$malId&episode=$epNum", headers = videoHeaders).text
                streamRegex.findAll(dlRes).forEach { match ->
                    addStream(match.groupValues[1], "Download Stream")
                }

                // Also check internal Flix API for raw unencrypted strings
                val flixRes = app.get("$mainUrl/api/flix/$aniId/$epNum", headers = videoHeaders).text
                streamRegex.findAll(flixRes).forEach { match ->
                    addStream(match.groupValues[1], "Flix Stream")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Check alternate server (HD-2) to bypass HD-1 Encryption
        if (!found) {
            try {
                val hd2Url = "$mainUrl/watch/$slug?ep=$epNum&server=HD-2"
                val hd2Html = app.get(hd2Url, headers = videoHeaders).text
                
                streamRegex.findAll(hd2Html).forEach { match ->
                    addStream(match.groupValues[1], "Direct Stream 2")
                }
                
                Jsoup.parse(hd2Html).select("iframe").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && src.startsWith("http")) {
                        loadExtractor(src, cleanData, subtitleCallback, callback)
                        found = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return found
    }
}
