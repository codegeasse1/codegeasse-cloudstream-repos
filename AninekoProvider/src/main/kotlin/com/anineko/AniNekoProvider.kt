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

        // 1. Hero Slider
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

        // 3. Trending List
        val trendingItems = doc.select(".nv-trending-list .nv-trending-item").mapNotNull { item ->
            val title = item.selectFirst("strong")?.text() ?: return@mapNotNull null
            val url = fixUrl(item.attr("href"))
            val poster = item.selectFirst("img")?.attr("src") ?: ""
            
            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
            }
        }
        if (trendingItems.isNotEmpty()) homeItems.add(HomePageList("Top Trending", trendingItems))

        // 4. Latest Updates
        val latestItems = doc.select(".nv-latest-list .nv-latest-item").mapNotNull { item ->
            if (item.hasClass("nv-schedule-sidebar-item")) return@mapNotNull null
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
            var serverUrl = btn.attr("data-video")
            val serverName = btn.ownText().trim()
            val typeInfo = btn.selectFirst("span")?.text()?.trim() ?: "SUB"
            val fullName = "$serverName ($typeInfo)"

            if (serverUrl.isNotBlank()) {
                // Extract subtitles if passed as URL parameters
                val subUrl = Regex("""[?&](?:sub|c1_file|caption_1)=([^&]+)""").find(serverUrl)?.groupValues?.get(1)
                if (subUrl != null) {
                    subtitleCallback(newSubtitleFile("English", subUrl))
                }

                // Map mirror domains to standard extractors
                if (serverUrl.contains("playmogo.com")) {
                    serverUrl = serverUrl.replace("playmogo.com", "dood.to")
                }

                // 1. Attempt native extractor resolution (Doodstream, StreamTape, etc.)
                if (loadExtractor(serverUrl, data, subtitleCallback, callback)) {
                    found = true
                } else {
                    // 2. Custom unpacker & scraper for HD-1, HD-2, StreamHG, Earnvids
                    try {
                        val iframeHtml = app.get(serverUrl, referer = "$mainUrl/").text
                        
                        // Unpack packed JavaScript obfuscation (StreamHG & Earnvids)
                        val unpackedHtml = getAndUnpack(iframeHtml) + "\n" + iframeHtml
                        
                        // Regex search for direct stream URLs (.m3u8 or .txt)
                        val m3u8Matches = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|txt)[^\s"'<>\\]*""")
                            .findAll(unpackedHtml)
                            .map { it.value }
                            .distinct()
                            .toList()

                        if (m3u8Matches.isNotEmpty()) {
                            m3u8Matches.forEach { rawUrl ->
                                val cleanUrl = rawUrl.replace("\\/", "/")
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = fullName,
                                        url = cleanUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.referer = serverUrl
                                        this.headers = mapOf(
                                            "Referer" to serverUrl,
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                        )
                                    }
                                )
                                found = true
                            }
                        } else {
                            // Fallback: Check JWPlayer / Video object configs
                            val fileMatch = Regex("""(?:file|source|url)["']?\s*:\s*["']([^"']+)["']""")
                                .find(unpackedHtml)?.groupValues?.get(1)

                            if (fileMatch != null && fileMatch.startsWith("http")) {
                                val cleanUrl = fileMatch.replace("\\/", "/")
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = fullName,
                                        url = cleanUrl,
                                        type = if (cleanUrl.contains(".m3u8") || cleanUrl.contains(".txt")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.referer = serverUrl
                                        this.headers = mapOf(
                                            "Referer" to serverUrl,
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                        )
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
