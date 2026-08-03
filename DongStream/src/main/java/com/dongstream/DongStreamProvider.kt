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

    // Strict Data Classes to guarantee JSON parsing doesn't fail
    private data class DongHome(val categories: Map<String, List<DongItem>>?)
    private data class DongSearch(val playlists: List<DongItem>?, val data: List<DongItem>?)
    
    private data class DongItem(
        val title: String? = null,
        val name: String? = null,
        val slug: String? = null,
        val playlist_slug: String? = null,
        val thumbnail_url: String? = null,
        val image_url: String? = null,
        val episode_number: Any? = null,
        val playlist: DongPlaylist? = null
    )

    private data class DongPlaylist(
        val title: String? = null,
        val slug: String? = null,
        val thumbnail_url: String? = null,
        val image_url: String? = null
    )

    private data class JsonLdData(val embedUrl: String?)

    // Fixes the "Site Logo" bug by routing relative images to the backend server
    private fun resolveImageUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.startsWith("http")) return raw
        return "https://backend.dongstream.com${if (raw.startsWith("/")) "" else "/"}$raw"
    }

    // Universal parser for both Series and Episode cards
    private fun parseToSearchResponse(item: DongItem): SearchResponse? {
        val title = item.title ?: item.name ?: item.playlist?.title ?: return null
        val actualSlug = item.playlist?.slug ?: item.playlist_slug ?: item.slug ?: return null
        
        val rawImg = item.thumbnail_url ?: item.image_url ?: item.playlist?.thumbnail_url ?: item.playlist?.image_url
        val img = resolveImageUrl(rawImg)

        val epNumStr = item.episode_number?.toString()
        val displayTitle = if (!epNumStr.isNullOrBlank() && !title.contains("Episode", true)) {
            "${item.playlist?.title ?: title} E${epNumStr.replace(".0", "")}"
        } else {
            title
        }

        return newAnimeSearchResponse(displayTitle, "$mainUrl/$actualSlug", TvType.Anime) {
            this.posterUrl = img
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeItems = mutableListOf<HomePageList>()
        
        runCatching {
            val jsonText = app.get("$apiUrl/home?region=en").text
            val data = AppUtils.parseJson<DongHome>(jsonText)
            
            val list = data.categories?.get(request.data) ?: emptyList()
            val cards = list.mapNotNull { parseToSearchResponse(it) }

            if (cards.isNotEmpty()) {
                homeItems.add(HomePageList(request.name, cards))
            }
        }
        
        return newHomePageResponse(homeItems, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        runCatching {
            val jsonText = app.get("$apiUrl/playlists?search=$query").text
            
            // Handle different API array/object structural possibilities
            val list = runCatching { AppUtils.parseJson<List<DongItem>>(jsonText) }.getOrNull()
                ?: runCatching { AppUtils.parseJson<DongSearch>(jsonText).let { it.data ?: it.playlists } }.getOrNull()
            
            list?.forEach { item ->
                parseToSearchResponse(item)?.let { results.add(it) }
            }
        }

        // Fallback if search API is completely empty
        if (results.isEmpty()) {
            runCatching {
                val jsonText = app.get("$apiUrl/home?region=en").text
                val data = AppUtils.parseJson<DongHome>(jsonText)
                
                data.categories?.values?.flatten()?.forEach { item ->
                    val parsed = parseToSearchResponse(item)
                    if (parsed != null && parsed.name.contains(query, ignoreCase = true)) {
                        results.add(parsed)
                    }
                }
            }
        }
        
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        // INTERCEPTOR: If a "Latest Release" episode link is clicked, redirect it to the Series page
        if (url.contains("/video/")) {
            val seriesSlug = url.substringAfter("/video/").substringBefore("/")
            return load("$mainUrl/$seriesSlug")
        }

        val document = app.get(url).document
        
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.replace(" - DongStream", "") ?: "Unknown Title"
        val rawPoster = document.selectFirst("meta[property=og:image]")?.attr("content")
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
            this.posterUrl = resolveImageUrl(rawPoster)
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
