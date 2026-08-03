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

    private fun resolveImageUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.startsWith("http")) return raw
        return "https://backend.dongstream.com${if (raw.startsWith("/")) "" else "/"}$raw"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeItems = mutableListOf<HomePageList>()
        
        runCatching {
            val jsonText = app.get("$apiUrl/home?region=en").text
            val rootMap = AppUtils.parseJson<Map<String, Any>>(jsonText)
            
            val categories = rootMap["categories"] as? Map<*, *>
            val rawList = categories?.get(request.data) as? List<*> ?: emptyList<Any>()
            
            val cards = mutableListOf<SearchResponse>()
            
            for (element in rawList) {
                val item = element as? Map<*, *> ?: continue
                
                val title = (item["title"] as? String) 
                    ?: (item["name"] as? String) 
                    ?: ((item["playlist"] as? Map<*, *>)?.get("title") as? String) 
                    ?: continue

                val playlist = item["playlist"] as? Map<*, *>
                val playlistSlug = playlist?.get("slug") as? String
                val itemPlaylistSlug = item["playlist_slug"] as? String
                val itemSlug = item["slug"] as? String
                
                val actualSlug = playlistSlug ?: itemPlaylistSlug ?: itemSlug 
                    ?: title.lowercase().replace(Regex("\\s+"), "-").replace(Regex("[^a-z0-9\\-]"), "")

                val rawImg = (item["thumbnail_url"] as? String) 
                    ?: (item["image_url"] as? String)
                    ?: (playlist?.get("thumbnail_url") as? String)
                    ?: (playlist?.get("image_url") as? String)
                
                val img = resolveImageUrl(rawImg)

                val epNumStr = item["episode_number"]?.toString()
                val playlistTitle = playlist?.get("title") as? String
                val displayTitle = if (!epNumStr.isNullOrBlank() && !title.contains("Episode", true)) {
                    "${playlistTitle ?: title} E${epNumStr.replace(".0", "")}"
                } else {
                    title
                }

                cards.add(
                    newAnimeSearchResponse(displayTitle, "$mainUrl/$actualSlug", TvType.Anime) {
                        this.posterUrl = img
                    }
                )
            }

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
            val rawList = runCatching { AppUtils.parseJson<List<Map<String, Any>>>(jsonText) }.getOrNull()
                ?: emptyList()

            rawList.forEach { item ->
                val title = (item["title"] as? String) ?: (item["name"] as? String) ?: return@forEach
                val slug = (item["slug"] as? String) ?: title.lowercase().replace(Regex("\\s+"), "-")
                val rawImg = (item["thumbnail_url"] as? String) ?: (item["image_url"] as? String)
                val img = resolveImageUrl(rawImg)

                results.add(
                    newAnimeSearchResponse(title, "$mainUrl/$slug", TvType.Anime) {
                        this.posterUrl = img
                    }
                )
            }
        }
        
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
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
