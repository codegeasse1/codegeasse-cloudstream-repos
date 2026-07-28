package com.miruro

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MiruroProvider : MainAPI() {
    override var mainUrl = "https://www.miruro.to"
    override var name = "Miruro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        // Grabbing the cards based exactly on the data-card-wrapper attribute
        val home = document.select("a[data-card-wrapper=true]").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawHref = fixUrlNull(this.attr("href")) ?: return null
        
        // The homepage often appends ?ep=1 to the URL. We strip it here so it links to the series page.
        val href = rawHref.substringBefore("?")
        
        val title = this.attr("title").ifBlank { this.selectFirst("img")?.attr("alt") }?.replace("Play ", "")?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select("a[data-card-wrapper=true]").mapNotNull { it.toSearchResult() }
    }

    // ---------------------------------------------------------------
    // LOAD (anime detail page + episode list)
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1, .title")?.text()?.trim() ?: ""
        val poster = fixUrlNull(document.selectFirst("img[alt*=poster], .poster img")?.attr("src"))
        val synopsis = document.selectFirst("div[id*=description], .description")?.text()

        val anilistId = url.substringAfter("/watch/").substringBefore("/")

        // Extracting episodes and passing the raw ID to loadLinks
        val episodes = document.select("div[data-episode-list=true] button[data-episode-id]").mapNotNull { btn ->
            val epTitle = btn.attr("title").ifBlank { btn.text() }.trim()
            val epNum = Regex("(?i)(?:EP|Episode)\\s*(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            
            // This is the secret ID needed for the API
            val epId = btn.attr("data-episode-id")
            if (epId.isBlank()) return@mapNotNull null

            // Pack the ID and Anilist ID together to send to loadLinks
            val linkData = "$epId||$anilistId"

            newEpisode(linkData) {
                this.name = epTitle
                this.episode = epNum
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = synopsis
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (video extraction via Hidden API)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val parts = data.split("||")
        val epId = parts[0]
        val anilistId = parts.getOrNull(1)

        // Loop through the video servers you found (kiwi, ally, bonk)
        val providers = listOf("kiwi", "ally", "bonk") 
        
        providers.forEach { provider ->
            listOf("sub", "dub").forEach { category ->
                // 1. Construct the raw JSON payload just like the browser does
                val jsonPayload = if (anilistId != null && anilistId.toIntOrNull() != null) {
                    """{"path":"sources","method":"GET","query":{"episodeId":"$epId","provider":"$provider","category":"$category","anilistId":$anilistId},"body":null,"version":"0.2.0"}"""
                } else {
                    """{"path":"sources","method":"GET","query":{"episodeId":"$epId","provider":"$provider","category":"$category"},"body":null,"version":"0.2.0"}"""
                }
                
                // 2. Base64 Encode the JSON safely
                val encodedPayload = android.util.Base64.encodeToString(jsonPayload.toByteArray(), android.util.Base64.NO_WRAP)
                val apiUrl = "$mainUrl/api/secure/pipe?e=$encodedPayload"

                // 3. Hit the API and aggressively scrape any URL it returns
                try {
                    val response = app.get(apiUrl).text
                    
                    // The API returns a JSON with "sources" arrays. We use a Regex to yank the direct URLs out.
                    val urls = Regex(""""url"\s*:\s*"([^"]+)"""").findAll(response).map { it.groupValues[1] }.toList()
                    
                    urls.forEach { parsedUrl ->
                        val fixedUrl = parsedUrl.replace("\\/", "/") // Clean any JSON escaped slashes
                        if (fixedUrl.startsWith("http")) {
                            loadExtractor(fixedUrl, data, subtitleCallback, callback)
                            found = true
                        }
                    }
                } catch (e: Exception) {
                    // Silently ignore if a specific provider or dub category fails
                }
            }
        }
        
        return found
    }
}
