package com.zh.anime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class ZHAnimeProvider : MainAPI() {
    override var mainUrl = "https://zhanime.se"
    override var name = "ZH Anime"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    // 1. Define homepage tabs for CloudStream
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/type/Anime" to "Anime",
        "$mainUrl/type/Donghua" to "Donghua",
        "$mainUrl/type/Movie" to "Movies"
    )

    private data class QtipData(
        val title: String? = null,
        val japanese: String? = null,
        val slug: String? = null,
        val type: String? = null
    )

    // 2. Fetch and parse main page content
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val homeItems = mutableListOf<HomePageList>()

        // Parse Spotlight Carousel on Home Page (Page 1)
        if (request.data == "$mainUrl/" && page == 1) {
            val spotlightItems = document.select("#hero-slider .hero-slide").mapNotNull { slide ->
                val title = slide.selectFirst(".title-en")?.text()?.trim() ?: return@mapNotNull null
                val href = slide.selectFirst("a[href*=/anime/]")?.attr("href") ?: return@mapNotNull null
                val poster = slide.selectFirst("img")?.attr("src")

                newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                    this.posterUrl = fixUrlNull(poster)
                }
            }
            if (spotlightItems.isNotEmpty()) {
                homeItems.add(HomePageList("Spotlight", spotlightItems, isHorizontalImages = true))
            }
        }

        // Parse Grid Anime Cards
        val animeCards = document.select(".anime-card").mapNotNull { card ->
            card.toSearchResult()
        }

        if (animeCards.isNotEmpty()) {
            homeItems.add(HomePageList(request.name, animeCards))
        }

        return newHomePageResponse(homeItems, hasNext = animeCards.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val qtipStr = this.attr("data-qtip")
        var title: String? = null
        var animeSlug: String? = null

        if (qtipStr.isNotBlank()) {
            runCatching {
                val data = parseJson<QtipData>(qtipStr)
                title = data.title
                animeSlug = data.slug
            }
        }

        val anchor = this.selectFirst("a[href*=/anime/]") ?: this.selectFirst("a[href*=/watch/]") ?: this.selectFirst("a")
        val href = anchor?.attr("href") ?: return null

        if (title.isNullOrBlank()) {
            title = this.selectFirst(".title-en")?.text()?.trim()
                ?: this.selectFirst("img")?.attr("alt")?.trim()
                ?: "Anime"
        }

        val finalUrl = if (!animeSlug.isNullOrBlank()) {
            "$mainUrl/anime/$animeSlug"
        } else {
            val cleanPath = href.replace("/watch/", "/anime/").substringBefore("/episode")
            fixUrl(cleanPath)
        }

        val imgEl = this.selectFirst("img")
        val poster = imgEl?.attr("src")?.ifBlank { imgEl.attr("data-src") }

        return newAnimeSearchResponse(title!!, finalUrl, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    // 3. Search
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        return document.select(".anime-card").mapNotNull { card ->
            card.toSearchResult()
        }
    }

    // 4. Load Anime Details and Episodes
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document

        val title = document.selectFirst(".vc-info__title .title-en")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Anime"

        val poster = document.selectFirst(".vc-info__poster")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = document.selectFirst("#synopsis-full")?.text()?.trim()
            ?: document.selectFirst(".vc-info__synopsis")?.text()?.trim()

        val episodes = mutableListOf<Episode>()

        val epElements = document.select(".vc-eplist-item, a.ep-btn, a[href*=/watch/]")
        for (epEl in epElements) {
            val href = epEl.attr("href")
            if (href.isBlank() || !href.contains("/watch/")) continue

            val epNumStr = epEl.attr("data-ep").ifBlank {
                Regex("""episode-(\d+)""").find(href)?.groupValues?.get(1) ?: "1"
            }
            val epNum = epNumStr.toIntOrNull() ?: 1
            val epTitle = epEl.selectFirst(".vc-eplist-item__title")?.text()?.trim() ?: "Episode $epNum"

            episodes.add(
                newEpisode(fixUrl(href)) {
                    this.name = epTitle
                    this.episode = epNum
                    this.posterUrl = fixUrlNull(poster)
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.episode })
        }
    }

    // 5. Load Video Links (With Multi-Server Support)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        val document = app.get(data, headers = headers).document

        // Select BOTH the iframe and the server buttons to ensure we get all available servers!
        val serverLinks = document.select(".server-btn[data-embed], iframe[src]").map {
            it.attr("data-embed").ifBlank { it.attr("src") }
        }.filter { it.isNotBlank() }.distinct()

        serverLinks.forEach { embedUrl ->
            val fixedUrl = fixUrl(embedUrl)

            when {
                // Extract native players and artplayer specifically
                fixedUrl.contains("artplayer.php") || fixedUrl.contains("player.php") -> {
                    runCatching {
                        val playerHtml = app.get(fixedUrl, headers = mapOf("Referer" to data, "User-Agent" to USER_AGENT)).text
                        
                        // Regex catches file: "..." or url: '...' or src: "..."
                        val m3u8Regex = Regex("""(?:file|src|url)["']?\s*:\s*["']([^"']+(?:m3u8|index\.txt|mp4)[^"']*)["']""")
                        val match = m3u8Regex.find(playerHtml)
                        
                        match?.groupValues?.get(1)?.let { streamUrlRaw ->
                            val streamUrlClean = streamUrlRaw.replace("\\/", "/")
                            
                            // Proper handling for relative CDNs specific to zhanime
                            val finalStreamUrl = when {
                                streamUrlClean.startsWith("//") -> "https:$streamUrlClean"
                                streamUrlClean.startsWith("/") -> "https://cdn.zhanime.online$streamUrlClean"
                                else -> streamUrlClean
                            }
                            
                            val isM3u8 = finalStreamUrl.contains("m3u8") || finalStreamUrl.contains("index.txt")
                            val serverName = if (fixedUrl.contains("artplayer")) "Artplayer" else "Native Player"

                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = serverName,
                                    url = finalStreamUrl,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = fixedUrl
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to fixedUrl)
                                }
                            )
                            found = true
                        }
                    }
                }
                
                // Fallback to loadExtractor for Megaplay, Vidplay, Mp4Upload, etc.
                else -> {
                    runCatching {
                        if (loadExtractor(fixedUrl, data, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                }
            }
        }
        return found
    }
}
