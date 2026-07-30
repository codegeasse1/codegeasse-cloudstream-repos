package com.anikage

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class AniKageProvider : MainAPI() {
    override var mainUrl = "https://anikage.cc"
    override var name = "AniKage"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private fun extractSectionArray(scriptData: String, key: String): List<SearchResponse> {
        val startStr = "$key:["
        val startIndex = scriptData.indexOf(startStr)
        if (startIndex == -1) return emptyList()
        
        var bracketCount = 1
        var endIndex = -1
        
        for (i in (startIndex + startStr.length) until scriptData.length) {
            if (scriptData[i] == '[') bracketCount++
            else if (scriptData[i] == ']') bracketCount--
            
            if (bracketCount == 0) {
                endIndex = i
                break
            }
        }
        
        if (endIndex == -1) return emptyList()
        
        val arrayStr = scriptData.substring(startIndex, endIndex)
        val parts = arrayStr.split("slug:\"")
        val items = mutableListOf<SearchResponse>()
        
        for (i in 1 until parts.size) {
            val part = parts[i]
            val slug = part.substringBefore("\"")
            if (slug.isBlank() || slug.length > 200) continue
            
            val titleBlock = part.substringAfter("title:{", "").substringBefore("}")
            var title = titleBlock.substringAfter("english:\"", "").substringBefore("\"")
            if (title.isBlank() || title == titleBlock) title = titleBlock.substringAfter("userPreferred:\"", "").substringBefore("\"")
            if (title.isBlank() || title == titleBlock) title = titleBlock.substringAfter("romaji:\"", "").substringBefore("\"")
            if (title.isBlank() || title == titleBlock) title = slug
            
            val coverBlock = part.substringAfter("coverImage:{", "").substringBefore("}")
            var poster = coverBlock.substringAfter("extraLarge:\"", "").substringBefore("\"")
            if (poster.isBlank() || poster == coverBlock) poster = coverBlock.substringAfter("large:\"", "").substringBefore("\"")
            if (poster == coverBlock) poster = ""
            
            val url = "$mainUrl/anime/info/$slug"
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
        val html = app.get(mainUrl).text
        val scriptData = Jsoup.parse(html).select("script").html()
        
        val homeItems = mutableListOf<HomePageList>()
        val sections = listOf(
            "trendinganime" to "Trending",
            "seasonalanime" to "Popular This Season",
            "top10anime" to "Top 10 Anime",
            "popularmovies" to "Popular Movies",
            "upcominganime" to "Coming Soon"
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
        val html = app.get("$mainUrl/browse?search=$query").text
        val scriptData = Jsoup.parse(html).select("script").html()
        
        val parts = scriptData.split("slug:\"")
        val items = mutableListOf<SearchResponse>()
        
        for (i in 1 until parts.size) {
            val part = parts[i]
            val slug = part.substringBefore("\"")
            if (slug.isBlank() || slug.length > 200) continue
            
            val window = part.take(1500)
            val titleBlock = window.substringAfter("title:{", "").substringBefore("}")
            var title = titleBlock.substringAfter("english:\"", "").substringBefore("\"")
            if (title.isBlank() || title == titleBlock) title = titleBlock.substringAfter("userPreferred:\"", "").substringBefore("\"")
            if (title.isBlank() || title == titleBlock) title = titleBlock.substringAfter("romaji:\"", "").substringBefore("\"")
            if (title.isBlank() || title == titleBlock) title = slug
            
            val coverBlock = window.substringAfter("coverImage:{", "").substringBefore("}")
            var poster = coverBlock.substringAfter("extraLarge:\"", "").substringBefore("\"")
            if (poster.isBlank() || poster == coverBlock) poster = coverBlock.substringAfter("large:\"", "").substringBefore("\"")
            if (poster == coverBlock) poster = ""
            
            val url = "$mainUrl/anime/info/$slug"
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
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("img[src*='anilistcdn']")?.attr("src") 
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        
        val scriptData = document.select("script").html()
        var plot = scriptData.substringAfter("description:\"", "").substringBefore("\",")
            .replace("\\u003C", "<").replace("\\n", "\n")
        
        if (plot.isBlank() || plot.length > 5000) {
            plot = document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        } else {
            plot = Jsoup.parse(plot).text()
        }

        val currentEps = scriptData.substringAfter("currentEpisode:", "").substringBefore(",").toIntOrNull() ?: 0
        val totalEps = scriptData.substringAfter("totalEpisodes:", "").substringBefore(",").toIntOrNull() ?: 0
        val availableEpisodes = if (currentEps > 0) currentEps else totalEps

        val episodes = mutableListOf<Episode>()
        
        val limit = if (availableEpisodes > 0) availableEpisodes else 1
        for (i in 1..limit) {
            episodes.add(newEpisode("$mainUrl/anime/watch/$slug?ep=$i") {
                this.name = "Episode $i"
                this.episode = i
            })
        }
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (Fully Asynchronous Array Probing)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val cleanData = data.substringBefore("#")
        val slug = cleanData.substringAfter("/watch/").substringBefore("?").substringBefore("/")
        val ep = Regex("""[?&]ep=(\d+)""").find(cleanData)?.groupValues?.get(1)
            ?: Regex("""/(\d+)""").find(cleanData)?.groupValues?.get(1)
            ?: "1"

        // The exact master list of servers and embeds you found
        val providers = listOf("neko", "ken", "miko", "megg", "dib", "wave", "koto", "e-neko", "e-ken", "e-koto", "e-wish")
        val langs = listOf("sub", "dub")

        val standardHeaders = mapOf(
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        
        // Exclude domains that definitely aren't videos
        val exclusions = listOf("jquery", "fonts", "anilist", "thetvdb", "jsdelivr", "w3.org", "w3.org")

        coroutineScope {
            val jobs = langs.flatMap { lang ->
                providers.map { provider ->
                    async {
                        val apiUrl = "$mainUrl/api/media/anime/$slug/episodes/$ep/sources?provider=$provider&lang=$lang"
                        try {
                            val responseText = app.get(apiUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                            
                            Regex("""https?://[^\s"'<>\\]+""").findAll(responseText).forEach { match ->
                                val cleanUrl = match.value.replace("\\/", "/")
                                if (exclusions.any { cleanUrl.contains(it) }) return@forEach
                                
                                val isDirectM3u8 = cleanUrl.contains(".m3u8") || cleanUrl.contains("/m3u8/") || cleanUrl.contains("master.m3u8")
                                val isDirectMp4 = cleanUrl.contains(".mp4")
                                val isKnownHost = cleanUrl.contains("prox.anicore") || cleanUrl.contains("prox.anikage") || cleanUrl.contains("workers.dev")
                                
                                if (isDirectM3u8 || isDirectMp4 || isKnownHost) {
                                    val serverName = "${provider.uppercase()} ($lang)"
                                    
                                    callback(
                                        newExtractorLink(
                                            source = "AniKage",
                                            name = "AniKage - $serverName",
                                            url = cleanUrl,
                                            type = if (isDirectM3u8 || cleanUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            quality = Qualities.Unknown.value
                                            headers = standardHeaders
                                        }
                                    )
                                    found = true
                                } else if (cleanUrl.startsWith("http")) {
                                    if (loadExtractor(cleanUrl, data, subtitleCallback, callback)) {
                                        found = true
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Endpoint doesn't exist for this specific anime/provider combo; ignore.
                        }
                    }
                }
            }
            // Wait for all 22 requests to finish checking simultaneously
            jobs.awaitAll()
        }

        // Ultimate fallback to parse the HTML page if API fails entirely
        if (!found) {
            try {
                val html = app.get(cleanData).text
                val cleanHtml = html.replace("\\/", "/")

                Regex("""https?://(?:prox\.anicore\.tv|prox\.anikage\.cc|morning-credit-[^\s"'<>\\]+\.workers\.dev)/[^\s"'<>\\]+""").findAll(cleanHtml).forEach { match ->
                    val extractedUrl = match.value
                    val isM3u8 = extractedUrl.contains(".m3u8") || extractedUrl.contains("/m3u8/")
                    
                    callback(
                        newExtractorLink(
                            source = "AniKage",
                            name = "AniKage - Direct",
                            url = extractedUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            quality = Qualities.Unknown.value
                            headers = standardHeaders
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
