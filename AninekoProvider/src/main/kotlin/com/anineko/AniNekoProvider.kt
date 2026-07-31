package com.anineko

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AniNekoProvider : MainAPI() {
    override var mainUrl = "https://anineko.to"
    override var name = "AniNeko"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Home"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val homeItems = mutableListOf<HomePageList>()

        // 1. Hero Slider (Featured)
        val heroItems = doc.select(".nv-hero-slide").mapNotNull { slide ->
            val title = slide.selectFirst(".nv-hero-title")?.text() ?: return@mapNotNull null
            val url = fixUrl(slide.selectFirst(".nv-hero-actions a")?.attr("href") ?: return@mapNotNull null)
            val poster = slide.selectFirst("img.nv-hero-bg")?.attr("src") ?: ""
            
            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
            }
        }
        if (heroItems.isNotEmpty()) homeItems.add(HomePageList("Featured", heroItems))

        // 2. Featured Grid
        val featuredItems = doc.select(".nv-featured-grid .nv-anime-card").mapNotNull { card ->
            val title = card.selectFirst(".nv-anime-title a")?.text() ?: return@mapNotNull null
            val url = fixUrl(card.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val poster = card.selectFirst("img")?.attr("src") ?: ""
            
            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
            }
        }
        if (featuredItems.isNotEmpty()) homeItems.add(HomePageList("Featured Anime", featuredItems))

        // 3. Trending List (Sidebar)
        val trendingItems = doc.select(".nv-trending-list .nv-trending-item").mapNotNull { item ->
            val title = item.selectFirst("strong")?.text() ?: return@mapNotNull null
            val url = fixUrl(item.attr("href"))
            val poster = item.selectFirst("img")?.attr("src") ?: ""
            
            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
            }
        }
        if (trendingItems.isNotEmpty()) homeItems.add(HomePageList("Top Trending", trendingItems))

        // 4. Latest Updates (Sidebar)
        val latestItems = doc.select(".nv-latest-list .nv-latest-item").mapNotNull { item ->
            if (item.hasClass("nv-schedule-sidebar-item")) return@mapNotNull null // Skip schedule items
            val title = item.selectFirst("strong")?.text() ?: return@mapNotNull null
            val url = fixUrl(item.attr("href"))
            val poster = item.selectFirst("img")?.attr("src") ?: ""
            
            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
            }
        }
        if (latestItems.isNotEmpty()) homeItems.add(HomePageList("Latest Updates", latestItems))

        return newHomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // The LD+JSON schema states the search path is /browser?keyword=
        val doc = app.get("$mainUrl/browser?keyword=$query").document
        
        return doc.select(".nv-anime-card, .nv-search-item").mapNotNull { card ->
            val title = card.selectFirst(".nv-anime-title a, .title")?.text() ?: return@mapNotNull null
            val url = fixUrl(card.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val poster = card.selectFirst("img")?.attr("src") ?: ""
            
            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text() ?: "No Title"
        val poster = doc.selectFirst(".nv-info-poster img")?.attr("src") 
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content") 
            ?: ""
            
        val plot = doc.selectFirst(".nv-info-desc, .nv-info-synopsis p")?.text() ?: ""
        
        // Parse episodes from the grid
        val episodes = doc.select(".nv-info-episode-item a.nv-info-episode-main, .nv-episode-list a.nv-episode-item").mapNotNull { epNode ->
            val epHref = fixUrl(epNode.attr("href"))
            val epName = epNode.selectFirst("strong")?.text() ?: epNode.text()
            val epNum = Regex("""ep-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
            
            newEpisode(epHref) {
                this.name = epName
                this.episode = epNum
            }
        }.distinctBy { it.data }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false

        doc.select(".nv-server-btn").forEach { btn ->
            val serverUrl = btn.attr("data-video")
            val serverName = btn.ownText().trim()
            val typeInfo = btn.selectFirst("span")?.text()?.trim() ?: "SUB"
            
            val fullName = "$serverName ($typeInfo)"

            if (serverUrl.isNotBlank()) {
                // Extract embedded subtitles from the iframe URL (e.g. ?sub=... or ?caption_1=...)
                val subUrl = Regex("""[?&](?:sub|c1_file|caption_1)=([^&]+)""").find(serverUrl)?.groupValues?.get(1)
                if (subUrl != null) {
                    subtitleCallback(SubtitleFile("English", subUrl))
                }

                // If it's a known extractor (like Doodstream), let CloudStream handle it natively
                if (serverUrl.contains("dood") || serverUrl.contains("playmogo")) {
                    if (loadExtractor(serverUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                } else {
                    // For custom JWPlayer embeds (vivibebe, otakuhg, bibiemb), fetch the iframe and rip the .m3u8 directly
                    try {
                        val iframeHtml = app.get(serverUrl, referer = "$mainUrl/").text
                        
                        // Aggressive hunt for Master Playlists in the player configuration
                        val m3u8Regex = Regex("""(?:file|source|url)["']?\s*:\s*["']([^"']+\.(?:m3u8|txt)[^"']*)["']""")
                        val match = m3u8Regex.find(iframeHtml)
                        
                        if (match != null) {
                            val videoUrl = match.groupValues[1].replace("\\/", "/")
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = fullName,
                                    url = videoUrl,
                                    referer = serverUrl,
                                    // Sometimes they hide M3U8s as .txt files to avoid detection (Screenshot 358)
                                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        } else {
                            // Absolute fallback: Find ANY unquoted m3u8 link in the iframe's DOM
                            val rawMatch = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(iframeHtml)
                            if (rawMatch != null) {
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = fullName,
                                        url = rawMatch.value,
                                        referer = serverUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                found = true
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        return found
    }
}
