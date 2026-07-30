package com.animex

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

class AnimeXProvider : MainAPI() {
    override var mainUrl = "https://animex.one"
    override var name = "AnimeX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // Parses SvelteKit's unquoted Javascript chunks gracefully without JSON libraries
    private fun parseJsAnimeList(html: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        // Splits by either slug:" or "slug":" to safely isolate each anime block
        val blocks = html.split(Regex(""""?slug"\s*:\s*""""))
        
        for (i in 1 until blocks.size) {
            val block = blocks[i]
            val slug = block.substringBefore("\"")
            if (slug.isBlank() || slug.length > 200) continue
            
            // Look only at the data for this specific anime
            val window = block.take(1500)
            
            val englishTitle = window.substringAfter("englishTitle:\"", "").substringBefore("\"").takeIf { it.isNotBlank() }
            val romajiTitle = window.substringAfter("romajiTitle:\"", "").substringBefore("\"").takeIf { it.isNotBlank() }
            val userPref = window.substringAfter("userPreferred:\"", "").substringBefore("\"").takeIf { it.isNotBlank() }
            val title = englishTitle ?: userPref ?: romajiTitle ?: slug
            
            val extraLarge = window.substringAfter("extraLarge:\"", "").substringBefore("\"").takeIf { it.startsWith("http") }
            val large = window.substringAfter("large:\"", "").substringBefore("\"").takeIf { it.startsWith("http") }
            val poster = extraLarge ?: large
            
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
        
        // Prevents black-screens by alerting the user ONLY if the actual Cloudflare block page is shown
        if (html.contains("<title>Just a moment...</title>")) {
            throw Error("Blocked by Cloudflare. Open in WebView to bypass.")
        }

        val homeItems = mutableListOf<HomePageList>()
        
        val sections = listOf(
            "trendingAnime" to "Trending",
            "seasonalAnime" to "Popular This Season",
            "popularMovies" to "Popular Movies",
            "upcomingAnime" to "Upcoming",
            "allTimePopular" to "All Time Popular"
        )
        
        for ((key, title) in sections) {
            val sectionIndex = html.indexOf(key)
            if (sectionIndex != -1) {
                // Safely grab the block for this section
                val start = html.indexOf("[", sectionIndex)
                val end = html.indexOf("}],", start)
                if (start != -1 && end != -1) {
                    val sectionBlock = html.substring(start, end)
                    val items = parseJsAnimeList(sectionBlock)
                    if (items.isNotEmpty()) {
                        homeItems.add(HomePageList(title, items))
                    }
                }
            }
        }
        
        // Absolute fallback: If categories break, dump all found animes into one row
        if (homeItems.isEmpty()) {
            val allItems = parseJsAnimeList(html)
            if (allItems.isNotEmpty()) {
                homeItems.add(HomePageList("Anime", allItems))
            } else {
                throw Error("Failed to parse layout. Please report to developer.")
            }
        }
        
        return newHomePageResponse(homeItems)
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.get("$mainUrl/catalog?search=$query").text
        return parseJsAnimeList(html)
    }

    // ---------------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast("/")
        val html = app.get(url).text
        
        var title = html.substringAfter("titleEnglish:\"", "").substringBefore("\"").takeIf { it.isNotBlank() }
            ?: html.substringAfter("titleRomaji:\"", "").substringBefore("\"").takeIf { it.isNotBlank() }
            ?: "Unknown"
            
        var plot = html.substringAfter("description:\"", "").substringBefore("\",")
            .replace("\\u003C", "<").replace("\\n", "\n").let { Jsoup.parse(it).text() }
            .takeIf { it.isNotBlank() }
            
        var poster = html.substringAfter("extraLarge:\"", "").substringBefore("\"").takeIf { it.startsWith("http") }
            ?: html.substringAfter("large:\"", "").substringBefore("\"").takeIf { it.startsWith("http") }
        
        // HTML fallback in case the Javascript object is missing
        val document = Jsoup.parse(html)
        if (title == "Unknown") {
            title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
            poster = document.selectFirst("img[src*='anilistcdn']")?.attr("src") ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            plot = document.selectFirst("meta[property=og:description]")?.attr("content")
        }

        val maxEps = html.substringAfter("episodeCount:", "").substringBefore(",").toIntOrNull()
            ?: html.substringAfter("episodes:", "").substringBefore(",").toIntOrNull()
            ?: 0

        val episodes = mutableListOf<Episode>()
        
        // 1. Try DOM rendered episodes
        document.select("a[href^=/watch/]").forEach { a ->
            val epHref = fixUrlNull(a.attr("href")) ?: return@forEach
            val textNodes = a.select("div").map { it.text().trim() }.filter { it.isNotEmpty() }
            val epTitleText = textNodes.firstOrNull { it.matches(Regex("""^\d+\..*""")) } ?: "Episode"
            
            val epNum = Regex("""-episode-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""^(\d+)""").find(epTitleText)?.groupValues?.get(1)?.toIntOrNull()
            
            episodes.add(newEpisode(epHref) {
                this.name = epTitleText
                this.episode = epNum
            })
        }
        
        // 2. Generate sequential episodes if hidden behind API pagination
        if (episodes.isEmpty() && maxEps > 0) {
            for (i in 1..maxEps) {
                episodes.add(newEpisode("$mainUrl/watch/$slug-episode-$i") {
                    this.name = "Episode $i"
                    this.episode = i
                })
            }
        }
        
        // 3. Absolute minimum fallback to prevent blank load screen
        if (episodes.isEmpty()) {
            episodes.add(newEpisode("$mainUrl/watch/$slug-episode-1") {
                this.name = "Episode 1"
                this.episode = 1
            })
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data })
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (Zen/Flixcloud Extraction)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text
        var found = false
        
        // Snags the embedded Flixcloud URL securely
        Regex("""player_url"?[^\w]*"(http[^"]+)""").findAll(html).forEach { match ->
            val playerUrl = match.groupValues[1].replace("\\/", "/")
            if (loadExtractor(playerUrl, data, subtitleCallback, callback)) {
                found = true
            }
        }

        val document = Jsoup.parse(html)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && loadExtractor(src, data, subtitleCallback, callback)) {
                found = true
            }
        }

        return found
    }
}
