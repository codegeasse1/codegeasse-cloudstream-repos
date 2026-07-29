package com.animexin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimeXinProvider : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "AnimeXin"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AsianDrama)

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Release",
        "$mainUrl/anime/?status=&type=&order=popular" to "Popular Series",
        "$mainUrl/anime/?status=&type=movie&order=update" to "New Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.contains("?")) {
                "${request.data}&page=$page"
            } else {
                "${request.data}page/$page/"
            }
        }
        
        val document = app.get(url).document
        val homeItems = document.select("article.bs, .listupd .bsx").mapNotNull { element ->
            element.toSearchResult()
        }
        
        return newHomePageResponse(request.name, homeItems)
    }

    // ---------------------------------------------------------------
    // ITEM PARSING
    // ---------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        
        val title = this.selectFirst(".tt h2, .tt, .eggtitle")?.text()?.trim() ?: return null
        
        val img = this.selectFirst("img")
        val rawPoster = img?.attr("data-lazy-src")?.ifBlank { img.attr("src") }
        val posterUrl = fixUrlNull(rawPoster)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            this.dubStatus = if (this@toSearchResult.text().contains("Dub", ignoreCase = true)) {
                enumValues<DubStatus>().toList()
            } else {
                null
            }
        }
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        
        return document.select("article.bs, .listupd .bsx").mapNotNull { element ->
            element.toSearchResult()
        }
    }

    // ---------------------------------------------------------------
    // LOAD (Handles both Series pages and direct Episode pages)
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Try to safely extract the series title, falling back to episode title if needed
        val title = document.selectFirst(".infolimit h2[itemprop=partOfSeries], .infolimit h2, .infox h1.entry-title")?.text() 
            ?: document.selectFirst("h1.entry-title")?.text()?.replace(Regex("Episode.*"), "")?.trim() 
            ?: "Unknown Series"

        val poster = document.selectFirst(".thumb img, .ts-post-image")?.let { 
            it.attr("data-lazy-src").ifBlank { it.attr("src") } 
        }
        
        val plot = document.selectFirst(".entry-content, .desc")?.text()
        val tags = document.select(".genxed a").map { it.text() }

        val episodes = mutableListOf<Episode>()

        // 1. If loaded from an Episode page, extract list from the sidebar
        document.select(".episodelist ul li").forEach { ep ->
            val aTag = ep.selectFirst("a") ?: return@forEach
            val epUrl = fixUrlNull(aTag.attr("href")) ?: return@forEach
            val epTitle = ep.selectFirst(".playinfo h3")?.text() ?: ""
            val epThumb = fixUrlNull(ep.selectFirst(".thumbnel img")?.attr("src"))
            
            // Extract episode number
            val epNum = Regex("""(?i)episode\s+(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
            
            episodes.add(Episode(epUrl, epTitle, posterUrl = epThumb, episode = epNum))
        }

        // 2. If loaded from a Series page, extract list from the episode lister
        document.select(".eplister ul li").forEach { ep ->
            val aTag = ep.selectFirst("a") ?: return@forEach
            val epUrl = fixUrlNull(aTag.attr("href")) ?: return@forEach
            val epNum = ep.selectFirst(".epl-num")?.text()?.toIntOrNull()
            val epTitle = ep.selectFirst(".epl-title")?.text() ?: "Episode $epNum"
            
            episodes.add(Episode(epUrl, epTitle, episode = epNum))
        }

        if (episodes.isEmpty()) {
            episodes.add(Episode(url, title))
        } else {
            // Reverses the list so episode 1 plays first 
            episodes.reverse() 
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.distinctBy { it.data }) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (Decodes Base64 Server Options)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val document = app.get(data).document

        // 1. Decodes the Base64 iframes hiding inside the server selection dropdown
        document.select("select.mirror option").forEach { opt ->
            val encodedValue = opt.attr("value")
            
            if (encodedValue.isNotBlank()) {
                try {
                    val decodedHtml = String(Base64.decode(encodedValue, Base64.DEFAULT), Charsets.UTF_8)
                    val iframeSrc = fixUrlNull(Jsoup.parse(decodedHtml).select("iframe").attr("src"))
                    
                    if (iframeSrc != null) {
                        found = loadExtractor(iframeSrc, data, subtitleCallback, callback) || found
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 2. Fallback check for any direct iframes natively embedded in the player block
        document.select(".player-embed iframe, #embed_holder iframe").forEach { iframe ->
            val iframeSrc = fixUrlNull(iframe.attr("src"))
            if (iframeSrc != null) {
                found = loadExtractor(iframeSrc, data, subtitleCallback, callback) || found
            }
        }

        return found
    }
}
