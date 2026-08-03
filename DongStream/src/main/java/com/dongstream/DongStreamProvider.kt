package com.dongstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class DongStreamProvider : MainAPI() {
    override var mainUrl = "https://dongstream.com"
    override var name = "DongStream"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime)

    private val apiUrl = "https://backend.dongstream.com/api"

    override val mainPage = mainPageOf(
        "latestReleases" to "Latest Releases",
        "popularSeries" to "Popular Series",
        "completedSeries" to "Completed Series",
        "movies" to "Donghua Movies",
        "comingSoon" to "Coming Soon"
    )

    private data class HomeData(val categories: Map<String, List<CategoryItem>>?)
    private data class CategoryItem(val title: String?, val thumbnail_url: String?, val slug: String?)
    private data class JsonLdData(val embedUrl: String?)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeItems = mutableListOf<HomePageList>()
        
        runCatching {
            val jsonText = app.get("$apiUrl/home?region=en").text
            val data = parseJson<HomeData>(jsonText)
            
            val list = data.categories?.get(request.data) ?: emptyList()
            val cards = list.mapNotNull { item ->
                if (item.title == null) return@mapNotNull null
                
                val itemSlug = item.slug ?: item.title.lowercase().replace(Regex("\\s+"), "-").replace(Regex("[^a-z0-9\\-]"), "")
                val img = item.thumbnail_url?.let { 
                    if (it.startsWith("http")) it else "https://backend.dongstream.com${if (it.startsWith("/")) "" else "/"}$it"
                }

                newAnimeSearchResponse(item.title, "$mainUrl/$itemSlug", TvType.Anime) {
                    this.posterUrl = img
                }
            }

            if (cards.isNotEmpty()) {
                homeItems.add(HomePageList(request.name, cards))
            }
        }
        
        return newHomePageResponse(homeItems, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query").document
        
        return document.select(".ssr-home a, .ssr-search a, a[href^=/]").mapNotNull {
            val href = it.attr("href")
            // Scrape the barebones SSR HTML links
            if (href.startsWith("/") && href.length > 2 && !href.contains("video")) {
                newAnimeSearchResponse(it.text(), fixUrl(href), TvType.Anime)
            } else null
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.replace(" - DongStream", "") ?: "Unknown Title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        val episodes = mutableListOf<Episode>()
        
        // Find episode links from SSR HTML
        document.select("a[href*=/video/]").forEach { a ->
            val epUrl = fixUrl(a.attr("href"))
            val epTitle = a.text().trim()
            
            // Try to extract the episode number from the URL or Title
            val epNum = Regex("""episode[s]?[-_]?(\d+)""").find(epUrl.lowercase())?.groupValues?.get(1)?.toIntOrNull() 
                ?: Regex("""/(\d+)$""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
            
            episodes.add(newEpisode(epUrl) {
                this.name = epTitle
                this.episode = epNum
            })
        }

        return newAnimeSearchResponse(title, finalUrl, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.url })
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
        
        // The HTML contains a hidden JSON-LD schema with the direct embedUrl (FastSharePro, etc.)
        val jsonLd = document.selectFirst("script#json-ld-data")?.data()
        
        if (!jsonLd.isNullOrBlank()) {
            runCatching {
                val jsonData = parseJson<JsonLdData>(jsonLd)
                
                jsonData.embedUrl?.let { embedUrl ->
                    // 1. First, let Cloudstream's built-in extractors try to handle it
                    if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                        found = true
                    } else {
                        // 2. Custom fallback to scrape the M3U8 from the iframe manually
                        val iframeHtml = app.get(embedUrl, headers = mapOf("Referer" to data)).text
                        val m3u8Match = Regex("""(?:file|src|url)["']?\s*:\s*["']([^"']+(?:m3u8|mp4)[^"']*)["']""").find(iframeHtml)
                        
                        m3u8Match?.groupValues?.get(1)?.let { stream ->
                            val isM3u8 = stream.contains("m3u8")
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "FastSharePro / Embedded",
                                    url = stream,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = embedUrl
                                }
                            )
                            found = true
                        }
                    }
                }
            }
        }
        
        // Backup: Look for standard iframes if JSON-LD fails
        if (!found) {
            document.select("iframe[src]").forEach { iframe ->
                val src = fixUrl(iframe.attr("src"))
                if (loadExtractor(src, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }
        
        return found
    }
}
