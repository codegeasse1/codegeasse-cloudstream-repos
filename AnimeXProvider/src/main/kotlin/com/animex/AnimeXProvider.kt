package com.animex

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject

class AnimeXProvider : MainAPI() {
    override var mainUrl = "https://animex.one"
    override var name = "AnimeX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private fun extractSectionArray(scriptData: String, key: String): List<SearchResponse> {
        val match = Regex("""["']?$key["']?\s*:\s*\[""").find(scriptData) ?: return emptyList()
        val startIndex = match.range.first
        
        var bracketCount = 0
        var arrayStart = scriptData.indexOf('[', startIndex)
        if (arrayStart == -1) return emptyList()
        
        var endIndex = -1
        for (i in arrayStart until scriptData.length) {
            if (scriptData[i] == '[') bracketCount++
            else if (scriptData[i] == ']') bracketCount--
            
            if (bracketCount == 0) {
                endIndex = i + 1
                break
            }
        }
        
        if (endIndex == -1) return emptyList()
        
        val arrayStr = scriptData.substring(arrayStart, endIndex)
        val parts = arrayStr.split(Regex("""["']?slug["']?\s*:\s*["']"""))
        val items = mutableListOf<SearchResponse>()
        
        for (i in 1 until parts.size) {
            val part = parts[i]
            val slug = part.substringBefore("\"").substringBefore("'")
            if (slug.isBlank() || slug.length > 200) continue
            
            val window = part.take(1500).replace("\\/", "/")
            
            var title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?name["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?romaji["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
            
            var poster = Regex("""["']?extraLarge["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""["']?large["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""https?://serveproxy\.com[^"'\s]+""").find(window)?.value ?: ""
            if (poster.isBlank()) poster = Regex("""https?://s4\.anilist\.co[^"'\s]+""").find(window)?.value ?: ""
            
            if (poster.isBlank()) {
                val relPath = Regex("""["']?poster_path["']?\s*:\s*["'](/[^"'\s]+)""").find(window)?.groupValues?.get(1)
                if (relPath != null) poster = "https://image.tmdb.org/t/p/original$relPath"
            }
            
            val url = "$mainUrl/anime/$slug"
            if (items.none { it.url == url }) {
                items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        }
        return items
    }

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get("$mainUrl/home").text
        val scriptData = Jsoup.parse(html).select("script").html()
        
        val homeItems = mutableListOf<HomePageList>()
        
        val sections = listOf(
            "trendingAnime" to "Trending",
            "seasonalAnime" to "Popular This Season",
            "popularMovies" to "Popular Movies",
            "upcomingAnime" to "Upcoming",
            "allTimePopular" to "All Time Popular"
        )
        
        for ((key, title) in sections) {
            val items = extractSectionArray(scriptData, key)
            if (items.isNotEmpty()) {
                homeItems.add(HomePageList(title, items))
            }
        }
        
        return newHomePageResponse(homeItems)
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.get("$mainUrl/catalog?search=$query").text
        val scriptData = Jsoup.parse(html).select("script").html()
        
        val parts = scriptData.split(Regex("""["']?slug["']?\s*:\s*["']"""))
        val items = mutableListOf<SearchResponse>()
        
        for (i in 1 until parts.size) {
            val part = parts[i]
            val slug = part.substringBefore("\"").substringBefore("'")
            if (slug.isBlank() || slug.length > 200) continue
            
            val window = part.take(1500).replace("\\/", "/")
            
            var title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?name["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?romaji["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
            
            var poster = Regex("""["']?extraLarge["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""["']?large["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""https?://serveproxy\.com[^"'\s]+""").find(window)?.value ?: ""
            if (poster.isBlank()) poster = Regex("""https?://s4\.anilist\.co[^"'\s]+""").find(window)?.value ?: ""
            
            if (poster.isBlank()) {
                val relPath = Regex("""["']?poster_path["']?\s*:\s*["'](/[^"'\s]+)""").find(window)?.groupValues?.get(1)
                if (relPath != null) poster = "https://image.tmdb.org/t/p/original$relPath"
            }
            
            val url = "$mainUrl/anime/$slug"
            if (items.none { it.url == url }) {
                items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        }
        return items
    }

    // ---------------------------------------------------------------
    // LOAD — reads the real embedded JSON fields directly (titleEnglish,
    // coverImage.extraLarge, bannerImage, synopsis, genres, anilistId)
    // instead of fragile slug-based string splitting. Episode watch
    // URLs are built as {slugBase}-{anilistId}-episode-{epNum}.
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfter("/anime/").substringBefore("?")
        val html = app.get(url).text
        val document = Jsoup.parse(html)

        val title = Regex(""""titleEnglish"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex(""""romajiTitle"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

        var poster = Regex(""""coverImage"\s*:\s*\{[^}]*?"extraLarge"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        if (poster.isNullOrBlank()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        }

        var banner = Regex(""""bannerImage"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        if (banner.isNullOrBlank()) banner = poster

        var plot = Regex(""""synopsis"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(html)?.groupValues?.get(1)
            ?.replace("\\u003C", "<")?.replace("\\/", "/")
        if (!plot.isNullOrBlank()) {
            plot = Jsoup.parse(plot).text()
        } else {
            plot = document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")
        }

        val genresBlock = Regex(""""genres"\s*:\s*\[(.*?)\]""").find(html)?.groupValues?.get(1)
        val genres = genresBlock?.let {
            Regex(""""name"\s*:\s*"([^"]+)"""").findAll(it).map { m -> m.groupValues[1] }.toList()
        } ?: emptyList()

        // anilistId needed to build correct /watch/ URLs
        val anilistId = Regex(""""anilistId"\s*:\s*(\d+)""").find(html)?.groupValues?.get(1)
        val slugBase = slug.substringBeforeLast("-")

        val episodes = mutableListOf<Episode>()

        try {
            val epApiUrl = "https://pp.animex.one/rest/api/episodes?id=$slug"
            val apiHeaders = mapOf(
                "Accept" to "application/json",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )
            val epJson = app.get(epApiUrl, headers = apiHeaders).text
            val jsonArray = JSONArray(epJson)
            for (i in 0 until jsonArray.length()) {
                val epObj = jsonArray.getJSONObject(i)
                val epNum = epObj.optInt("number", -1)
                if (epNum == -1) continue

                val titlesObj = epObj.optJSONObject("titles")
                val epTitle = titlesObj?.optString("en", "")?.ifBlank { null } ?: "Episode $epNum"

                val thumbnail = epObj.optString("img", "")

                val epUrl = if (anilistId != null)
                    "$mainUrl/watch/$slugBase-$anilistId-episode-$epNum"
                else
                    "$mainUrl/anime/$slug?epNum=$epNum"

                episodes.add(
                    newEpisode(epUrl) {
                        name = epTitle
                        episode = epNum
                        posterUrl = thumbnail.ifBlank { poster }
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback: scrape real /watch/ links straight from the page HTML
        if (episodes.isEmpty()) {
            document.select("a[href*=/watch/]").forEach { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@forEach
                val epNum = Regex("""-episode-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""/(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                val epName = a.text().replace(Regex("""^\d+\.\s*"""), "").trim().ifEmpty { "Episode $epNum" }
                episodes.add(
                    newEpisode(epHref) {
                        name = epName
                        episode = epNum
                        posterUrl = poster
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.plot = plot
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.episode })
        }
    }

// ---------------------------------------------------------------
    // LOAD LINKS
    // Confirmed: https://pp.animex.one/rest/api/servers?id=...&epNum=...
    //   → {"subProviders":[{"id":"beep","default":true,...}],"dubProviders":[...]}
    // Sources: https://pp.animex.one/rest/api/sources?id=...&epNum=...&type=...&providerId=...
    //   → {"sources":[{"url":"...m3u8",...}],"tracks":null or [...],"headers":{"Referer":"..."}}
    // Fallback: embedded player_url in the watch page's resolve() block,
    // then any iframe found directly in the DOM.
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val cleanData = data.substringBefore("#")

        // 1. PRIMARY METHOD: Scrape the embedded SvelteKit JSON data from the HTML
        // The site uses SvelteKit which embeds episode data directly in the page.
        // The API (pp.animex.one) is currently returning 404, so we must rely on the HTML.
        try {
            val html = app.get(cleanData).text
            
            // SvelteKit often serializes JSON keys without quotes (e.g., player_url:"...")
            // This regex handles both quoted and unquoted keys/values.
            val playerUrls = Regex("""["']?player_url["']?\s*:\s*["']([^"']+)["']""")
                .findAll(html)
                .map { it.groupValues[1].replace("\\/", "/") }
                .distinct()
                .toList()

            for (playerUrl in playerUrls) {
                if (playerUrl.isNotBlank()) {
                    try {
                        if (loadExtractor(playerUrl, cleanData, subtitleCallback, callback)) {
                            found = true
                        }
                    } catch (_: Exception) { }
                }
            }
            
            // Also check for any iframes that might be rendered in the HTML
            if (!found) {
                val document = Jsoup.parse(html)
                document.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && src.startsWith("http")) {
                        try {
                            if (loadExtractor(src, cleanData, subtitleCallback, callback)) {
                                found = true
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. FALLBACK METHOD: The old API (kept in case it comes back online)
        if (!found) {
            val slug = cleanData.substringAfter("/watch/").substringBeforeLast("-episode-")
                .let { if (it.isNotBlank()) it else cleanData.substringAfter("/anime/").substringBefore("?") }
            val epNum = Regex("""-episode-(\d+)""").find(cleanData)?.groupValues?.get(1)
                ?: Regex("""[?&]epNum=(\d+)""").find(cleanData)?.groupValues?.get(1)
                ?: "1"

            val apiHeaders = mapOf(
                "Accept" to "application/json",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )

            try {
                val serversUrl = "https://pp.animex.one/rest/api/servers?id=$slug&epNum=$epNum"
                val serversResponse = app.get(serversUrl, headers = apiHeaders)
                
                // Only proceed if the API is actually responding
                if (serversResponse.code == 200) {
                    val serversJson = JSONObject(serversResponse.text)

                    suspend fun fetchSources(type: String, providerId: String) {
                        try {
                            val sourcesUrl = "https://pp.animex.one/rest/api/sources?id=$slug&epNum=$epNum&type=$type&providerId=$providerId"
                            val raw = app.get(sourcesUrl, headers = apiHeaders).text.replace("\\/", "/")
                            val sourceObj = JSONObject(raw)

                            val referer = sourceObj.optJSONObject("headers")?.optString("Referer", mainUrl) ?: mainUrl

                            val sourcesArray = sourceObj.optJSONArray("sources")
                            if (sourcesArray != null) {
                                for (i in 0 until sourcesArray.length()) {
                                    val src = sourcesArray.getJSONObject(i)
                                    val streamUrl = src.optString("url", "")
                                    if (streamUrl.isBlank()) continue

                                    val isM3u8 = streamUrl.contains(".m3u8") || src.optString("type", "").contains("mpegurl", true)

                                    callback(
                                        newExtractorLink(
                                            source = "AnimeX",
                                            name = "$providerId ($type)",
                                            url = streamUrl,
                                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = referer
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                    found = true
                                }
                            }

                            val tracksArray = sourceObj.optJSONArray("tracks")
                            if (tracksArray != null) {
                                for (i in 0 until tracksArray.length()) {
                                    val track = tracksArray.getJSONObject(i)
                                    val trackUrl = track.optString("url", track.optString("file", ""))
                                    val label = track.optString("label", track.optString("lang", "Subtitle"))
                                    if (trackUrl.isNotBlank()) {
                                        subtitleCallback(SubtitleFile(label, trackUrl))
                                    }
                                }
                            }
                        } catch (_: Exception) { }
                    }

                    val subProviders = serversJson.optJSONArray("subProviders")
                    if (subProviders != null) {
                        for (i in 0 until subProviders.length()) {
                            val p = subProviders.getJSONObject(i)
                            if (p.optBoolean("default", false)) {
                                fetchSources("sub", p.optString("id"))
                            }
                        }
                        if (!found && subProviders.length() > 0) {
                            fetchSources("sub", subProviders.getJSONObject(0).optString("id"))
                        }
                    }

                    val dubProviders = serversJson.optJSONArray("dubProviders")
                    if (dubProviders != null) {
                        for (i in 0 until dubProviders.length()) {
                            val p = dubProviders.getJSONObject(i)
                            if (p.optBoolean("default", false)) {
                                fetchSources("dub", p.optString("id"))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return found
    }
} 
