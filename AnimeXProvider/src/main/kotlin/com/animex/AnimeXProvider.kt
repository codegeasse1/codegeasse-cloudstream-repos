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

    // Upgraded with Regex to completely ignore spaces in the site's minified Javascript
    private fun extractSectionArray(scriptData: String, key: String): List<SearchResponse> {
        val match = Regex("""$key\s*:\s*\[""").find(scriptData) ?: return emptyList()
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
        val parts = arrayStr.split(Regex("""slug\s*:\s*["']"""))
        val items = mutableListOf<SearchResponse>()
        
        for (i in 1 until parts.size) {
            val part = parts[i]
            val slug = part.substringBefore("\"").substringBefore("'")
            if (slug.isBlank() || slug.length > 200) continue
            
            val window = part.take(2000)
            
            var title = Regex("""english\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""userPreferred\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""romaji\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
            
            var poster = Regex("""extraLarge\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""large\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            
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
        
        val parts = scriptData.split(Regex("""slug\s*:\s*["']"""))
        val items = mutableListOf<SearchResponse>()
        
        for (i in 1 until parts.size) {
            val part = parts[i]
            val slug = part.substringBefore("\"").substringBefore("'")
            if (slug.isBlank() || slug.length > 200) continue
            
            val window = part.take(2000)
            
            var title = Regex("""english\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""userPreferred\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""romaji\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
            
            var poster = Regex("""extraLarge\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""large\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            
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
        val slug = url.substringAfter("/anime/").substringBefore("?")
        val html = app.get(url).text
        val document = Jsoup.parse(html)
        val scriptData = document.select("script").html()
        
        // Isolate data for THIS specific anime to prevent pulling random trending data
        var animeBlock = scriptData.substringAfter("slug:\"$slug\"", "")
        if (animeBlock.isBlank()) animeBlock = scriptData 
        
        // 1. Title
        var title = document.selectFirst("h1")?.text()?.trim()
        if (title.isNullOrEmpty() || title == "Unknown") {
            title = Regex("""english\s*:\s*["']([^"']+)""").find(animeBlock)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""userPreferred\s*:\s*["']([^"']+)""").find(animeBlock)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""romaji\s*:\s*["']([^"']+)""").find(animeBlock)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
        }
        
        // 2. Posters & Banner (Fixes inside header blank image)
        var poster = Regex("""extraLarge\s*:\s*["']([^"']+)""").find(animeBlock)?.groupValues?.get(1) ?: ""
        if (poster.isBlank()) poster = Regex("""large\s*:\s*["']([^"']+)""").find(animeBlock)?.groupValues?.get(1) ?: ""
        if (poster.isBlank()) poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        
        var banner = Regex("""bannerImage\s*:\s*["']([^"']+)""").find(animeBlock)?.groupValues?.get(1) ?: ""
        if (banner.isBlank()) banner = poster 
        
        // 3. Plot
        var plot = Regex("""(?:description|synopsis)\s*:\s*["']([^"'\\]+)""").find(animeBlock)?.groupValues?.get(1) ?: ""
        if (plot.isBlank()) {
            plot = document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content") ?: ""
        } else {
            plot = Jsoup.parse(plot.replace("\\n", "\n").replace("\\u003C", "<")).text()
        }

        val episodes = mutableListOf<Episode>()
        
        // 4. Hit internal API for episodes
        try {
            val epApiUrl = "https://pp.animex.one/rest/api/episodes?id=$slug"
            val apiHeaders = mapOf("Accept" to "application/json", "Origin" to mainUrl, "Referer" to "$mainUrl/")
            val epJson = app.get(epApiUrl, headers = apiHeaders).text
            
            val epBlocks = epJson.split("{").drop(1)
            for (block in epBlocks) {
                val epNum = block.substringAfter("\"number\":", "").substringBefore(",").trim().toIntOrNull()
                val epTitle = block.substringAfter("\"title\":\"", "").substringBefore("\"")
                
                if (epNum != null) {
                    episodes.add(newEpisode("$mainUrl/anime/$slug?epNum=$epNum") {
                        this.name = if (epTitle.isNotBlank() && epTitle != block) epTitle else "Episode $epNum"
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
            this.backgroundPosterUrl = banner // Connects the missing banner image to the app header
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
        val slug = cleanData.substringAfter("/anime/").substringBefore("?")
        val ep = Regex("""[?&]epNum=(\d+)""").find(cleanData)?.groupValues?.get(1)
            ?: Regex("""[?&]ep=(\d+)""").find(cleanData)?.groupValues?.get(1)
            ?: Regex("""-episode-(\d+)""").find(cleanData)?.groupValues?.get(1)
            ?: "1"

        val apiHeaders = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        try {
            // STEP 1: Fetch Server List
            val serversUrl = "https://pp.animex.one/rest/api/servers?id=$slug&epNum=$ep"
            val serversJson = app.get(serversUrl, headers = apiHeaders).text
            
            val serverBlocks = serversJson.split("{").drop(1)
            for (block in serverBlocks) {
                val providerId = block.substringAfter("\"providerId\":\"", "").substringBefore("\"")
                val type = block.substringAfter("\"type\":\"", "").substringBefore("\"").ifBlank { "sub" }
                var serverName = block.substringAfter("\"serverName\":\"", "").substringBefore("\"")
                
                if (serverName.isBlank() || serverName == block) serverName = providerId.replaceFirstChar { it.uppercase() }

                if (providerId.isNotBlank() && providerId != block) {
                    // STEP 2: Fetch Source Streams
                    val sourcesUrl = "https://pp.animex.one/rest/api/sources?id=$slug&epNum=$ep&type=$type&providerId=$providerId"
                    val sourcesJson = app.get(sourcesUrl, headers = apiHeaders).text
                    
                    val urls = Regex(""""(?:url|file|link)"\s*:\s*"([^"]+)"""").findAll(sourcesJson)
                    for (urlMatch in urls) {
                        val videoUrl = urlMatch.groupValues[1].replace("\\/", "/")
                        
                        if (videoUrl.contains(".m3u8") || videoUrl.contains(".m3u")) {
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
                    
                    // Extract Subtitles directly from API response
                    val subBlocks = sourcesJson.split("file\":\"").drop(1)
                    for (subBlock in subBlocks) {
                        val subUrl = subBlock.substringBefore("\"").replace("\\/", "/")
                        val label = subBlock.substringAfter("label\":\"", "").substringBefore("\"")
                        if (subUrl.isNotBlank() && subUrl.endsWith(".vtt") && label.isNotBlank() && label != subBlock) {
                            subtitleCallback(SubtitleFile(label, subUrl))
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
