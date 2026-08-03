package com.dongstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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

    // Using a generic Map structure makes parsing indestructible against API changes
    private data class HomeResponse(val categories: Map<String, List<Map<String, Any>>>?)

    // Universal parser that handles both "Series" objects and "Episode" objects seamlessly
    private fun parseItem(item: Map<String, Any>): SearchResponse? {
        val title = (item["title"] as? String) ?: (item["name"] as? String) ?: return null

        // "Latest Releases" returns Episodes, so the image/slug is hidden inside a nested playlist object
        val playlist = item["playlist"] as? Map<*, *>
        
        val seriesTitle = (playlist?.get("title") as? String) ?: title
        
        val actualSlug = (playlist?.get("slug") as? String) 
            ?: (item["playlist_slug"] as? String) 
            ?: (item["slug"] as? String) 
            ?: seriesTitle.lowercase().replace(Regex("\\s+"), "-").replace(Regex("[^a-z0-9\\-]"), "")
            
        val rawImg = (item["thumbnail_url"] as? String) 
            ?: (item["image_url"] as? String)
            ?: (playlist?.get("thumbnail_url") as? String)
            ?: (playlist?.get("image_url") as? String)
            
        val img = rawImg?.let { 
            if (it.startsWith("http")) it else "https://backend.dongstream.com${if (it.startsWith("/")) "" else "/"}$it"
        }

        // Format nicely for Latest Releases (e.g., "Swallowed Star E235")
        val epNum = item["episode_number"]?.toString()?.toDoubleOrNull()?.toInt()
        val displayTitle = if (epNum != null && !seriesTitle.contains("Episode", true)) {
            "$seriesTitle E$epNum"
        } else {
            seriesTitle
        }

        return newAnimeSearchResponse(displayTitle, "$mainUrl/$actualSlug", TvType.Anime) {
            this.posterUrl = img
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeItems = mutableListOf<HomePageList>()
        
        runCatching {
            val jsonText = app.get("$apiUrl/home?region=en").text
            val data = AppUtils.parseJson<HomeResponse>(jsonText)
            
            val list = data.categories?.get(request.data) ?: emptyList()
            val cards = list.mapNotNull { parseItem(it) }

            if (cards.isNotEmpty()) {
                homeItems.add(HomePageList(request.name, cards))
            }
        }
        
        return newHomePageResponse(homeItems, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        // 1. Try the primary Search API first
        runCatching {
            val res = app.get("$apiUrl/search?q=$query").parsedSafe<List<Map<String, Any>>>()
            res?.forEach { item -> parseItem(item)?.let { results.add(it) } }
        }
        
        // 2. Fallback to Playlists API if standard search fails
        if (results.isEmpty()) {
            runCatching {
                val res = app.get("$apiUrl/playlists?search=$query").parsedSafe<List<Map<String, Any>>>()
                res?.forEach { item -> parseItem(item)?.let { results.add(it) } }
            }
        }

        // 3. Last Resort: Fetch the homepage and filter the cache manually
        if (results.isEmpty()) {
            runCatching {
                val res = app.get("$apiUrl/home?region=en").parsedSafe<HomeResponse>()
                res?.categories?.values?.flatten()?.forEach { item ->
                    val parsed = parseItem(item)
                    if (parsed != null && parsed.name.contains(query, ignoreCase = true)) {
                        results.add(parsed)
                    }
                }
            }
        }
        
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.replace(" - DongStream", "") ?: "Unknown Title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        val episodes = mutableListOf<Episode>()
        
        document.select("a[href*=/video/]").forEach { a ->
            val epUrl = fixUrl(a.attr("href"))
            val epTitle = a.text().trim()
            
            val epNum = Regex("""episode[s]?[-_]?(\d+)""").find(epUrl.lowercase())?.groupValues?.get(1)?.toIntOrNull() 
                ?: Regex("""/(\d+)$""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
            
            episodes.add(newEpisode(epUrl) {
                this.name = epTitle
                this.episode = epNum
            })
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data }) 
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
        
        val jsonLd = document.selectFirst("script#json-ld-data")?.data()
        
        if (!jsonLd.isNullOrBlank()) {
            runCatching {
                // Quick regex to grab the embedUrl safely from the Schema
                val embedUrl = Regex(""""embedUrl"\s*:\s*"([^"]+)"""").find(jsonLd)?.groupValues?.get(1)
                
                embedUrl?.let { embed ->
                    if (loadExtractor(embed, data, subtitleCallback, callback)) {
                        found = true
                    } else {
                        val iframeHtml = app.get(embed, headers = mapOf("Referer" to data)).text
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
                                    this.referer = embed
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        }
                    }
                }
            }
        }
        
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
