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

        // Extract Anilist & MAL IDs to use in API calls later
        val aniIdMatch = Regex(""""anilist_id"\s*:\s*(\d+)""").find(html) ?: Regex(""""anilist"\s*:\s*(\d+)""").find(html)
        val anilistId = aniIdMatch?.groupValues?.get(1) ?: ""

        val malIdMatch = Regex(""""mal_id"\s*:\s*(\d+)""").find(html) ?: Regex(""""mal"\s*:\s*(\d+)""").find(html)
        val malId = malIdMatch?.groupValues?.get(1) ?: ""

        val episodes = mutableListOf<Episode>()

        // 1. Fetch Episodes from API
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
                    newEpisode("$mainUrl/watch/$slug?ep=$epNum&ani=$anilistId&mal=$malId") {
                        name = epTitle
                        episode = epNum
                        posterUrl = thumbnail.ifBlank { poster }
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fallback: Parse from page
        if (episodes.isEmpty()) {
            val links = document.select("a[href*=/watch/]")
            for (a in links) {
                val epHref = fixUrlNull(a.attr("href")) ?: continue
                val epNum = Regex("""[?&]ep=(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: a.attr("data-episode").toIntOrNull()
                    ?: 1
                val epName = a.selectFirst(".text-[13px] span")?.text() ?: "Episode $epNum"

                episodes.add(
                    newEpisode("$epHref&ani=$anilistId&mal=$malId") {
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
        val slug = cleanData.substringAfter("/watch/").substringBefore("?")
        val epNum = Regex("""[?&]ep=(\d+)""").find(cleanData)?.groupValues?.get(1) ?: "1"
        val aniId = Regex("""[?&]ani=(\d+)""").find(cleanData)?.groupValues?.get(1) ?: ""
        val malId = Regex("""[?&]mal=(\d+)""").find(cleanData)?.groupValues?.get(1) ?: ""

        val videoHeaders = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        // Helper to add streams avoiding duplicates and deprecated constructors
        val extractedUrls = hashSetOf<String>()
        val addStream = { streamUrl: String, sourceName: String ->
            val cleanUrl = streamUrl.replace("\\/", "/")
            if (!extractedUrls.contains(cleanUrl)) {
                extractedUrls.add(cleanUrl)
                val isM3u8 = cleanUrl.contains(".m3u8", true)
                callback(
                    ExtractorLink(
                        source = "Re:Anime",
                        name = sourceName,
                        url = cleanUrl,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                found = true
            }
        }

        val urlExtractRegex = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""")
        val hosts = listOf("vidhide", "streamwish", "filemoon", "dood", "voe", "ok.ru", "vk.com", "mixdrop", "anicore", "mp4upload", "mega")

        // 1. Scan multiple servers (HD-1, HD-2, HD-3) from the Watch Page to bypass FlixCloud
        val servers = listOf("HD-1", "HD-2", "HD-3", "Backup")
        for (server in servers) {
            try {
                val watchUrl = "$mainUrl/watch/$slug?ep=$epNum&lang=sub&server=$server"
                val watchHtml = app.get(watchUrl, headers = videoHeaders).text.replace("\\/", "/")
                
                // Find direct streams inside the HTML payload
                val streamMatches = urlExtractRegex.findAll(watchHtml)
                for (match in streamMatches) {
                    addStream(match.value, "Server $server")
                }

                // Check for embedded known host iframes
                val allUrls = Regex("""https?://[^\s"'<>\\]+""").findAll(watchHtml).map { it.value }.toSet()
                for (u in allUrls) {
                    if (hosts.any { u.contains(it, true) }) {
                        if (loadExtractor(u, data, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Bypass Encryption: Hit the Downloads API directly
        if (aniId.isNotBlank()) {
            try {
                val dlUrl = "$mainUrl/api/v1/downloads/check?anilist_id=$aniId&mal_id=$malId&episode=$epNum"
                val dlRes = app.get(dlUrl, headers = videoHeaders).text.replace("\\/", "/")
                
                // Aggressively extract any URL inside the JSON response
                val urlMatches = Regex(""""url"\s*:\s*"([^"]+)"""").findAll(dlRes)
                for (match in urlMatches) {
                    addStream(match.groupValues[1], "Direct Download")
                }
                
                // Backup Regex just in case "url" key is named differently
                val backupMatches = urlExtractRegex.findAll(dlRes)
                for (match in backupMatches) {
                    addStream(match.value, "Direct Download")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. Fallback: Check internal Flix API
        if (aniId.isNotBlank()) {
            try {
                val flixUrl = "$mainUrl/api/flix/$aniId/$epNum"
                val flixRes = app.get(flixUrl, headers = videoHeaders).text.replace("\\/", "/")

                val streamMatches = urlExtractRegex.findAll(flixRes)
                for (match in streamMatches) {
                    addStream(match.value, "Flix API")
                }

                // If no direct link, try passing the 24-char ID to CloudStream's MegaCloud/RabbitStream decryptor
                if (!found) {
                    val idMatch = Regex("""([a-fA-F0-9]{24})""").find(flixRes)
                    if (idMatch != null) {
                        val flixId = idMatch.groupValues[1]
                        if (loadExtractor("https://megacloud.tv/embed-2/e-1/$flixId", data, subtitleCallback, callback)) found = true
                        if (loadExtractor("https://rabbitstream.net/v2/embed-4/$flixId", data, subtitleCallback, callback)) found = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return found
    }
}
