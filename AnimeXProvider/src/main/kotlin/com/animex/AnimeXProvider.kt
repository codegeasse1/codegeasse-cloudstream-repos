package com.animex

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class AnimeXProvider : MainAPI() {
    override var mainUrl = "https://animex.one"
    override var name = "AnimeX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // Upgraded to handle both strict JSON ("slug":) and loose JS (slug:) objects
    private fun extractSectionArray(scriptData: String, key: String): List<SearchResponse> {
        val startStrMatches = Regex("""["']?$key["']?\s*:\s*\[""").find(scriptData) ?: return emptyList()
        val startIndex = startStrMatches.range.first
        
        var bracketCount = 0
        var endIndex = -1
        
        // Find the start of the actual array '['
        var arrayStart = scriptData.indexOf('[', startIndex)
        if (arrayStart == -1) return emptyList()
        
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
            
            val window = part.take(1500)
            
            var title = ""
            val titleBlock = window.substringAfter("title", "").substringAfter("{", "").substringBefore("}")
            
            title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(titleBlock)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?userPreferred["']?\s*:\s*["']([^"']+)""").find(titleBlock)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?romaji["']?\s*:\s*["']([^"']+)""").find(titleBlock)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
            
            val coverBlock = window.substringAfter("coverImage", "").substringAfter("{", "").substringBefore("}")
            var poster = Regex("""["']?extraLarge["']?\s*:\s*["']([^"']+)""").find(coverBlock)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""["']?large["']?\s*:\s*["']([^"']+)""").find(coverBlock)?.groupValues?.get(1) ?: ""
            
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
            
            val window = part.take(1500)
            
            val titleBlock = window.substringAfter("title", "").substringAfter("{", "").substringBefore("}")
            var title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(titleBlock)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?userPreferred["']?\s*:\s*["']([^"']+)""").find(titleBlock)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?romaji["']?\s*:\s*["']([^"']+)""").find(titleBlock)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
            
            val coverBlock = window.substringAfter("coverImage", "").substringAfter("{", "").substringBefore("}")
            var poster = Regex("""["']?extraLarge["']?\s*:\s*["']([^"']+)""").find(coverBlock)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""["']?large["']?\s*:\s*["']([^"']+)""").find(coverBlock)?.groupValues?.get(1) ?: ""
            
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
    // LOAD
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast("/")
        val html = app.get(url).text
        val document = Jsoup.parse(html)
        val scriptData = document.select("script").html()
        
        // 1. Title
        var title = document.selectFirst("h1")?.text()?.trim()
        if (title.isNullOrEmpty() || title == "Unknown") {
            val titleBlock = scriptData.substringAfter("title", "").substringAfter("{", "").substringBefore("}")
            title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(titleBlock)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?userPreferred["']?\s*:\s*["']([^"']+)""").find(titleBlock)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?romaji["']?\s*:\s*["']([^"']+)""").find(titleBlock)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
        }
        
        // 2. Poster
        var poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        if (poster.isNullOrEmpty()) {
            val coverBlock = scriptData.substringAfter("coverImage", "").substringAfter("{", "").substringBefore("}")
            poster = Regex("""["']?extraLarge["']?\s*:\s*["']([^"']+)""").find(coverBlock)?.groupValues?.get(1) ?: ""
            if (poster.isNullOrEmpty()) poster = Regex("""["']?large["']?\s*:\s*["']([^"']+)""").find(coverBlock)?.groupValues?.get(1) ?: ""
        }
        
        // 3. Plot
        var plot = document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content") ?: ""
        if (plot.isBlank() || plot.contains("AnimeX", ignoreCase = true)) {
            val plotExtracted = Regex("""["']?(?:description|synopsis)["']?\s*:\s*["']([^"'\\]+)""").find(scriptData)?.groupValues?.get(1)
            if (!plotExtracted.isNullOrBlank()) {
                plot = Jsoup.parse(plotExtracted.replace("\\n", "\n").replace("\\u003C", "<")).text()
            }
        }

        val episodes = mutableListOf<Episode>()
        
        // 4. API Episode Generation
        try {
            val epApiUrl = "https://pp.animex.one/rest/api/episodes?id=$slug"
            val epJson = app.get(epApiUrl, headers = mapOf("Referer" to "$mainUrl/")).text
            
            val epNumbers = Regex("""["']?(?:number|epNum)["']?\s*:\s*(\d+)""").findAll(epJson)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .distinct()
                .sorted()
                .toList()
                
            if (epNumbers.isNotEmpty()) {
                epNumbers.forEach { epNum ->
                    episodes.add(newEpisode("$mainUrl/anime/$slug?ep=$epNum") {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                    })
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Fallback to DOM if API fails
        if (episodes.isEmpty()) {
            document.select("a[href*=/watch/]").forEach { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@forEach
                val textContent = a.text()
                val epNum = Regex("""-episode-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""/(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                
                val epName = textContent.replace(Regex("""^\d+\.\s*"""), "").trim().ifEmpty { "Episode $epNum" }
                
                episodes.add(newEpisode(epHref) {
                    this.name = epName
                    this.episode = epNum
                })
            }
        }
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.episode })
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val cleanData = data.substringBefore("#")
        val slug = cleanData.substringAfter("/anime/").substringAfter("/watch/").substringBefore("?").substringBefore("-episode")
        val ep = Regex("""[?&]ep=(\d+)""").find(cleanData)?.groupValues?.get(1)
            ?: Regex("""-episode-(\d+)""").find(cleanData)?.groupValues?.get(1)
            ?: "1"

        val apiHeaders = mapOf(
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        try {
            // STEP 1: Fetch Server List
            val serversUrl = "https://pp.animex.one/rest/api/servers?id=$slug&epNum=$ep"
            val serversJson = app.get(serversUrl, headers = apiHeaders).text
            
            // Extract individual server objects from JSON array
            val serverObjects = Regex("""\{[^\}]+\}""").findAll(serversJson)
            
            for (obj in serverObjects) {
                val providerId = Regex("""["']?providerId["']?\s*:\s*["']([^"']+)["']""").find(obj.value)?.groupValues?.get(1)
                val type = Regex("""["']?type["']?\s*:\s*["']([^"']+)["']""").find(obj.value)?.groupValues?.get(1) ?: "sub"
                var serverName = Regex("""["']?serverName["']?\s*:\s*["']([^"']+)["']""").find(obj.value)?.groupValues?.get(1)
                
                if (serverName.isNullOrBlank()) serverName = providerId?.replaceFirstChar { it.uppercase() } ?: "Unknown"

                if (providerId != null) {
                    // STEP 2: Fetch Source Streams
                    val sourcesUrl = "https://pp.animex.one/rest/api/sources?id=$slug&epNum=$ep&type=$type&providerId=$providerId"
                    val sourcesJson = app.get(sourcesUrl, headers = apiHeaders).text
                    
                    // Extract Video Links
                    val urls = Regex("""["']?(?:url|file|link)["']?\s*:\s*["']([^"']+)["']""").findAll(sourcesJson)
                    for (urlMatch in urls) {
                        val videoUrl = urlMatch.groupValues[1].replace("\\/", "/")
                        
                        if (videoUrl.contains(".m3u8") || videoUrl.contains(".m3u")) {
                            // FIXED SYNTAX: Quality and Referer moved inside lambda block
                            callback(
                                newExtractorLink(
                                    source = "AnimeX",
                                    name = "$serverName ($type)",
                                    url = videoUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.referer = "$mainUrl/"
                                }
                            )
                            found = true
                        } else if (videoUrl.contains(".mp4")) {
                            // FIXED SYNTAX: Quality and Referer moved inside lambda block
                            callback(
                                newExtractorLink(
                                    source = "AnimeX",
                                    name = "$serverName ($type)",
                                    url = videoUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.referer = "$mainUrl/"
                                }
                            )
                            found = true
                        }
                    }
                    
                    // Extract Subtitles
                    val tracks = Regex("""\{[^}]*["']?file["']?\s*:\s*["']([^"']+)["'][^}]*["']?label["']?\s*:\s*["']([^"']+)["'][^}]*\}""").findAll(sourcesJson)
                    for (track in tracks) {
                        val subUrl = track.groupValues[1].replace("\\/", "/")
                        val subLabel = track.groupValues[2]
                        
                        if (subUrl.isNotBlank()) {
                            subtitleCallback(
                                SubtitleFile(
                                    subLabel,
                                    subUrl
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback: If APIs completely fail, check DOM for mounted iframes
        if (!found) {
            try {
                val html = app.get(cleanData).text
                val document = Jsoup.parse(html)
                document.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && src.startsWith("http") && loadExtractor(src, data, subtitleCallback, callback)) {
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
