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

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/home").document
        val homeItems = mutableListOf<HomePageList>()
        
        document.select("section").forEach { section ->
            val title = section.selectFirst("h2")?.text()?.trim() ?: return@forEach
            
            val items = section.select("a[href^=/anime/]").mapNotNull { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                
                val name = a.attr("aria-label").replace("View ", "", ignoreCase = true).trim().ifEmpty {
                    a.select("img").attr("alt")
                }
                val poster = a.select("img").attr("src")
                
                newAnimeSearchResponse(name, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            }
            
            if (items.isNotEmpty()) {
                homeItems.add(HomePageList(title, items))
            }
        }
        
        // FIX: Use the new HomePage builder
        return newHomePageResponse(homeItems)
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/catalog?search=$query"
        val document = app.get(url).document
        
        return document.select("a[href^=/anime/]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            
            val name = a.attr("aria-label").replace("View ", "", ignoreCase = true).trim().ifEmpty {
                a.select("img").attr("alt")
            }
            val poster = a.select("img").attr("src")
            
            newAnimeSearchResponse(name, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    // ---------------------------------------------------------------
    // LOAD (Anime Detail Page + Episodes)
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        
        val poster = document.selectFirst("img[src*='anilistcdn']")?.attr("src") 
            ?: document.selectFirst("img.object-cover")?.attr("src")
            
        val plot = document.selectFirst("div.my-3.rounded-md p")?.text()?.trim()
            ?: document.select("p.text-white\\/60").firstOrNull()?.text()?.trim()

        val episodes = document.select("a[href^=/watch/]").mapNotNull { a ->
            val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            
            val textNodes = a.select("div").map { it.text().trim() }.filter { it.isNotEmpty() }
            val epTitleText = textNodes.firstOrNull { it.matches(Regex("""^\d+\..*""")) } 
                ?: textNodes.firstOrNull() 
                ?: "Episode"
            
            val epNum = Regex("""^(\d+)""").find(epTitleText)?.groupValues?.get(1)?.toIntOrNull() 
                ?: Regex("""-episode-(\d+)$""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
            
            newEpisode(epHref) {
                this.name = epTitleText
                this.episode = epNum
            }
        }
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            // FIX: Episodes use 'data' instead of 'url'
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data })
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (Player Extraction)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text
        var found = false
        
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
