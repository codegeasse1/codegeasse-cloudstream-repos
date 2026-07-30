package com.animex

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

// --- DATA CLASSES FOR SVELTEKIT JSON ---
data class AnimexSvelteData(
    @JsonProperty("sectionsData") val sectionsData: Map<String, List<AnimexMedia>>? = null,
    @JsonProperty("anime") val anime: AnimexAnime? = null
)

data class AnimexMedia(
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("title") val title: AnimexTitle? = null,
    @JsonProperty("coverImage") val coverImage: AnimexCover? = null,
)

data class AnimexTitle(
    @JsonProperty("english") val english: String? = null,
    @JsonProperty("userPreferred") val userPreferred: String? = null,
    @JsonProperty("romaji") val romaji: String? = null
)

data class AnimexCover(
    @JsonProperty("extraLarge") val extraLarge: String? = null,
    @JsonProperty("large") val large: String? = null
)

data class AnimexAnime(
    @JsonProperty("titleEnglish") val titleEnglish: String? = null,
    @JsonProperty("titleRomaji") val titleRomaji: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("coverImage") val coverImage: AnimexCover? = null,
    @JsonProperty("episodeCount") val episodeCount: Int? = null,
    @JsonProperty("episodes") val episodesCount: Int? = null
)

class AnimeXProvider : MainAPI() {
    override var mainUrl = "https://animex.one"
    override var name = "AnimeX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // Helper to extract the core JSON object from SvelteKit's SSR payload
    private fun extractSvelteJson(html: String): String? {
        val scriptText = Jsoup.parse(html).select("script").find { it.data().contains("type:\"data\",data:") }?.data()
        return scriptText?.substringAfter("type:\"data\",data:")?.substringBeforeLast("}]")
    }

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get("$mainUrl/home").text
        val homeItems = mutableListOf<HomePageList>()
        
        val jsonString = extractSvelteJson(html)
        if (!jsonString.isNullOrBlank()) {
            try {
                val parsed = parseJson<AnimexSvelteData>(jsonString)
                
                val sectionNames = mapOf(
                    "trendingAnime" to "Trending",
                    "seasonalAnime" to "Popular This Season",
                    "popularMovies" to "Popular Movies",
                    "allTimePopular" to "All Time Popular",
                    "upcomingAnime" to "Upcoming"
                )
                
                parsed.sectionsData?.forEach { (key, mediaList) ->
                    val title = sectionNames[key] ?: key
                    val items = mediaList.mapNotNull { media ->
                        val href = "$mainUrl/anime/${media.slug ?: return@mapNotNull null}"
                        val name = media.title?.english ?: media.title?.userPreferred ?: media.title?.romaji ?: "Unknown"
                        val poster = media.coverImage?.extraLarge ?: media.coverImage?.large
                        
                        newAnimeSearchResponse(name, href, TvType.Anime) {
                            this.posterUrl = poster
                        }
                    }
                    if (items.isNotEmpty()) {
                        homeItems.add(HomePageList(title, items))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return newHomePageResponse(homeItems)
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.get("$mainUrl/catalog?search=$query").text
        val searchItems = mutableListOf<SearchResponse>()
        
        // Regex brute-force search items because JSON tree structure can change on search routes
        val mediaBlocks = Regex(""""slug"\s*:\s*"([^"]+)".*?"title"\s*:\s*\{"romaji"\s*:\s*("[^"]+"|null),"english"\s*:\s*("[^"]+"|null),"userPreferred"\s*:\s*("[^"]+"|null)\}.*?"coverImage"\s*:\s*\{"extraLarge"\s*:\s*("[^"]+"|null),"large"\s*:\s*("[^"]+"|null)""").findAll(html)
        
        mediaBlocks.forEach { match ->
            val slug = match.groupValues[1]
            val romaji = match.groupValues[2].replace("\"", "").takeIf { it != "null" }
            val english = match.groupValues[3].replace("\"", "").takeIf { it != "null" }
            val userPref = match.groupValues[4].replace("\"", "").takeIf { it != "null" }
            val extraLarge = match.groupValues[5].replace("\"", "").takeIf { it != "null" }
            val large = match.groupValues[6].replace("\"", "").takeIf { it != "null" }
            
            val name = english ?: userPref ?: romaji ?: "Unknown"
            val poster = extraLarge ?: large
            
            searchItems.add(newAnimeSearchResponse(name, "$mainUrl/anime/$slug", TvType.Anime) {
                this.posterUrl = poster
            })
        }
        
        return searchItems.distinctBy { it.url }
    }

    // ---------------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast("/")
        val html = app.get(url).text
        
        var title = "Unknown"
        var plot: String? = null
        var poster: String? = null
        var maxEps = 0
        
        val jsonString = extractSvelteJson(html)
        if (!jsonString.isNullOrBlank()) {
            try {
                val parsed = parseJson<AnimexSvelteData>(jsonString)
                title = parsed.anime?.titleEnglish ?: parsed.anime?.titleRomaji ?: "Unknown"
                plot = parsed.anime?.description ?: parsed.anime?.synopsis
                poster = parsed.anime?.coverImage?.extraLarge ?: parsed.anime?.coverImage?.large
                maxEps = parsed.anime?.episodeCount ?: parsed.anime?.episodesCount ?: 0
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        if (title == "Unknown") {
            val document = Jsoup.parse(html)
            title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
            poster = document.selectFirst("img[src*='anilistcdn']")?.attr("src") 
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            plot = document.selectFirst("meta[property=og:description]")?.attr("content")
        }

        val episodes = mutableListOf<Episode>()
        
        // Fetch episodes via the internal API
        try {
            val epApiUrl = "$mainUrl/api/episodes?id=$slug"
            val epResponse = app.get(epApiUrl).text
            
            val epList = parseJson<List<Map<String, Any>>>(epResponse)
            epList.forEach { ep ->
                val epNum = ep["number"]?.toString()?.toDoubleOrNull()?.toInt() 
                    ?: ep["episode"]?.toString()?.toDoubleOrNull()?.toInt()
                val epTitle = ep["title"]?.toString()
                
                if (epNum != null) {
                    episodes.add(newEpisode("$mainUrl/watch/$slug-episode-$epNum") {
                        this.name = epTitle ?: "Episode $epNum"
                        this.episode = epNum
                    })
                }
            }
        } catch (e: Exception) {}
        
        // Fallback: Generate sequential episodes if API is blocked
        if (episodes.isEmpty() && maxEps > 0) {
            for (i in 1..maxEps) {
                episodes.add(newEpisode("$mainUrl/watch/$slug-episode-$i") {
                    this.name = "Episode $i"
                    this.episode = i
                })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data })
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
        val html = app.get(data).text
        var found = false
        
        // The player_url strings are embedded within the SvelteKit JS chunk payloads
        Regex(""""player_url"\s*:\s*"([^"]+)"""").findAll(html).forEach { match ->
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
