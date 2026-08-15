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

        // Sort buttons to push HD-1 (vivibebe) to the absolute bottom of the list.
        // This ensures CloudStream auto-plays HD-2, StreamHG, or Earnvids first.
        val serverButtons = doc.select(".nv-server-btn").sortedBy { btn ->
            val sName = btn.ownText().trim()
            val sUrl = btn.attr("data-video")
            sName.contains("HD-1", ignoreCase = true) || sUrl.contains("vivibebe", ignoreCase = true)
        }

        serverButtons.forEach { btn ->
            var serverUrl = btn.attr("data-video")
            val serverName = btn.ownText().trim()
            val typeInfo = btn.selectFirst("span")?.text()?.trim() ?: "SUB"
            val fullName = "$serverName ($typeInfo)"

            if (serverUrl.isNotBlank()) {
                val subUrl = Regex("""[?&](?:sub|c1_file|caption_1)=([^&]+)""").find(serverUrl)?.groupValues?.get(1)
                if (subUrl != null) {
                    subtitleCallback(newSubtitleFile("English", subUrl))
                }

                if (serverUrl.contains("playmogo.com") || serverUrl.contains("dood")) {
                    val doodUrl = serverUrl.replace("playmogo.com", "dood.to")
                    if (loadExtractor(doodUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                } else {
                    try {
                        val iframeHeaders = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                            "Referer" to "$mainUrl/"
                        )
                        val iframeHtml = app.get(serverUrl, headers = iframeHeaders).text
                        val unpackedHtml = getAndUnpack(iframeHtml) + "\n" + iframeHtml

                        val m3u8Matches = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|txt)[^\s"'<>\\]*""")
                            .findAll(unpackedHtml)
                            .map { it.value.replace("\\/", "/") }
                            .distinct()
                            .toList()

                        if (m3u8Matches.isNotEmpty()) {
                            m3u8Matches.forEach { videoUrl ->
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = fullName,
                                        url = videoUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.referer = serverUrl
                                        this.headers = mapOf(
                                            "Referer" to serverUrl,
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                                            "Origin" to fixUrl(serverUrl).removeSuffix("/")
                                        )
                                    }
                                )
                                found = true
                            }
                        } else {
                            val fileMatch = Regex("""(?:file|source|url)["']?\s*:\s*["']([^"']+)["']""")
                                .find(unpackedHtml)?.groupValues?.get(1)?.replace("\\/", "/")

                            if (!fileMatch.isNullOrBlank() && fileMatch.startsWith("http")) {
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = fullName,
                                        url = fileMatch,
                                        type = if (fileMatch.contains(".m3u8") || fileMatch.contains(".txt")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.referer = serverUrl
                                        this.headers = mapOf(
                                            "Referer" to serverUrl,
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                                            "Origin" to fixUrl(serverUrl).removeSuffix("/")
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
