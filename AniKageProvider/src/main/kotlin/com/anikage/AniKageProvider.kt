package com.anikage

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

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
    // LOAD LINKS
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        
        // 1. Try standard query param format page (?ep=1)
        var html = app.get(data).text
        found = extractLinksFromHtml(html, data, subtitleCallback, callback)
        
        // 2. Try SvelteKit hidden JSON payload endpoint
        if (!found) {
            val jsonUrl = data.replace("?", "/__data.json?")
            if (jsonUrl != data) {
                val jsonData = app.get(jsonUrl).text
                found = extractLinksFromHtml(jsonData, data, subtitleCallback, callback)
            }
        }
        
        // 3. Try standard path param format page (/1)
        if (!found && data.contains("?ep=")) {
            val altUrl = data.replace("?ep=", "/")
            html = app.get(altUrl).text
            found = extractLinksFromHtml(html, altUrl, subtitleCallback, callback)
            
            // 4. Try SvelteKit hidden JSON endpoint for path param format
            if (!found) {
                val altJsonUrl = "$altUrl/__data.json"
                val altJsonData = app.get(altJsonUrl).text
                found = extractLinksFromHtml(altJsonData, altUrl, subtitleCallback, callback)
            }
        }
        
        return found
    }

    private suspend fun extractLinksFromHtml(
        html: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        // SvelteKit usually escapes JSON payload URLs; clean them before scanning
        val cleanHtml = html.replace("\\/", "/")

        // 1. Precise Match: Search strictly for valid anicore proxy URLs, NO token guessing
        Regex("""https?://(?:prox\.anicore\.tv|prox\.anikage\.cc)/m3u8/[a-zA-Z0-9_=-]+""").findAll(cleanHtml).forEach { match ->
            val extractedUrl = match.value
            callback(
                newExtractorLink(
                    source = "AniKage",
                    name = "AniKage HD",
                    url = extractedUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    quality = Qualities.Unknown.value
                    headers = mapOf(
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                }
            )
            found = true
        }

        // 2. Fallback Svelte variables that point directly to streaming files
        Regex("""(?:player_url|url|link|src|iframe|serverUrl|file)"?\s*:\s*"([^"]+)"""").findAll(cleanHtml).forEach { match ->
            val extractedUrl = match.groupValues[1]
            if (extractedUrl.startsWith("http")) {
                if (loadExtractor(extractedUrl, data, subtitleCallback, callback)) {
                    found = true
                } else if (extractedUrl.contains(".m3u8") || extractedUrl.contains(".mp4")) {
                    val isM3u8 = extractedUrl.contains(".m3u8")
                    callback(
                        newExtractorLink(
                            source = "AniKage",
                            name = "AniKage Server",
                            url = extractedUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            quality = Qualities.Unknown.value
                            headers = mapOf(
                                "Origin" to mainUrl,
                                "Referer" to "$mainUrl/",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            )
                        }
                    )
                    found = true
                }
            }
        }

        // 3. Absolute Fallback: Standard embedded iframes inside HTML
        Jsoup.parse(html).select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.startsWith("http") && loadExtractor(src, data, subtitleCallback, callback)) {
                found = true
            }
        }
        
        return found
    }
}
