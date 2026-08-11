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
        
        // FIXED: Added distinctBy { it.url } so Cloudstream removes the duplicate shows 
        // that appear in both the slider and the grid.
        val homeItems = document.select("article.bs, .listupd .bsx").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, homeItems)
    }

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
                mutableSetOf(DubStatus.Dubbed)
            } else {
                mutableSetOf(DubStatus.Subbed)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        val targetLimit = 100
        var page = 1

        while (searchResults.size < targetLimit) {
            val url = if (page == 1) {
                "$mainUrl/?s=$query"
            } else {
                "$mainUrl/page/$page/?s=$query"
            }

            try {
                val document = app.get(url).document
                
                // FIXED: Also added distinctBy here just in case search returns duplicates
                val items = document.select("article.bs, .listupd .bsx").mapNotNull { element ->
                    element.toSearchResult()
                }.distinctBy { it.url }

                if (items.isEmpty()) break

                searchResults.addAll(items)
                page++
            } catch (e: Exception) {
                break
            }
        }

        return searchResults.take(targetLimit)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".infolimit h2[itemprop=partOfSeries], .infolimit h2, .infox h1.entry-title")?.text()
            ?: document.selectFirst("h1.entry-title")?.text()?.replace(Regex("Episode.*"), "")?.trim()
            ?: "Unknown Series"

        val poster = document.selectFirst(".thumb img, .ts-post-image")?.let {
            it.attr("data-lazy-src").ifBlank { it.attr("src") }
        }

        val plot = document.selectFirst(".entry-content, .desc")?.text()
        val tags = document.select(".genxed a").map { it.text() }

        val episodes = mutableListOf<Episode>()

        // 1. Sidebar episode list
        document.select(".episodelist ul li").forEach { ep ->
            val aTag = ep.selectFirst("a") ?: return@forEach
            val epUrl = fixUrlNull(aTag.attr("href")) ?: return@forEach
            val epTitle = ep.selectFirst(".playinfo h3")?.text() ?: ""
            val epThumb = fixUrlNull(ep.selectFirst(".thumbnel img")?.attr("src"))

            val epNum = Regex("""(?i)episode\s+(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            episodes.add(newEpisode(epUrl) {
                this.name = epTitle
                this.posterUrl = epThumb
                this.episode = epNum
            })
        }

        // 2. Series page episode list
        document.select(".eplister ul li").forEach { ep ->
            val aTag = ep.selectFirst("a") ?: return@forEach
            val epUrl = fixUrlNull(aTag.attr("href")) ?: return@forEach
            
            // FIXED: We now extract the raw text and use a Regex to pull out ONLY the digits. 
            // This safely bypasses text like "14 END" so it correctly reads as Episode 14.
            val epNumText = ep.selectFirst(".epl-num")?.text() ?: ""
            val epTitleText = ep.selectFirst(".epl-title")?.text() ?: ""
            
            val epNum = Regex("""\d+""").find(epNumText)?.value?.toIntOrNull() 
                ?: Regex("""\d+""").find(epTitleText)?.value?.toIntOrNull()
                
            val epName = if (epTitleText.isNotBlank()) epTitleText else if (epNumText.isNotBlank()) epNumText else "Episode $epNum"

            episodes.add(newEpisode(epUrl) {
                this.name = epName.trim()
                this.episode = epNum
            })
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = title
            })
        } else {
            episodes.reverse()
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.distinctBy { it.data }) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val document = app.get(data).document

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

        document.select(".player-embed iframe, #embed_holder iframe").forEach { iframe ->
            val iframeSrc = fixUrlNull(iframe.attr("src"))
            if (iframeSrc != null) {
                found = loadExtractor(iframeSrc, data, subtitleCallback, callback) || found
            }
        }

        return found
    }
}
