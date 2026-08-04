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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val homeItems = mutableListOf<HomePageList>()

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

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        return document.select(".anime-card").mapNotNull { card ->
            card.toSearchResult()
        }
    }

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

    private suspend fun emitEmbedFallback(url: String, serverName: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val isM3u8 = url.contains("m3u8", true) || url.contains(".txt", true)
        callback(
            newExtractorLink(name, serverName, url, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.headers = mapOf("Referer" to referer, "Origin" to mainUrl)
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
        val document = app.get(data, headers = headers).document

        // Collect all servers from buttons and iframes, keeping their names
        val serverButtons = document.select("button.server-btn[data-embed], a.server-btn[data-embed], iframe[src]")
        val serverLinks = mutableListOf<Pair<String, String>>()

        for (el in serverButtons) {
            val embedUrl = el.attr("data-embed").ifBlank { el.attr("src") }.trim()
            if (embedUrl.isBlank()) continue
            
            val serverName = el.text().trim().ifBlank { 
                when {
                    embedUrl.contains("artplayer") -> "Artplayer"
                    embedUrl.contains("player.php") -> "Player"
                    embedUrl.contains("megaplay") -> "Megaplay"
                    embedUrl.contains("vidplay") -> "Vidplay"
                    else -> "Server"
                }
            }
            serverLinks.add(Pair(embedUrl, serverName))
        }

        val distinctServers = serverLinks.distinctBy { it.first }

        for ((rawEmbedUrl, serverName) in distinctServers) {
            val fixedUrl = fixUrl(rawEmbedUrl)

            if (fixedUrl.contains("artplayer.php") || fixedUrl.contains("player.php") || fixedUrl.contains("zhanime.se")) {
                try {
                    val playerHtml = app.get(fixedUrl, headers = mapOf("Referer" to data, "User-Agent" to USER_AGENT)).text
                    
                    // The site uses "videoUrl:" for artplayer, and "file:" or "url:" for others.
                    val jsonRegex = Regex("""(?:file|src|url|videoUrl|source|hls|playlist|masterPlaylist)["']?\s*:\s*["']([^"']+)["']""")
                    val streamUrlRaw = jsonRegex.find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/")
                    
                    // Fallback: find any m3u8/txt/mp4 URL in the page
                    val finalStreamUrl = streamUrlRaw?.takeIf { it.contains(".m3u8") || it.contains(".txt") || it.contains(".mp4") }
                        ?: Regex("""https?://[^\s"'\\]+?\.(?:m3u8|txt|mp4)[^\s"'\\]*""").find(playerHtml)?.value

                    if (finalStreamUrl != null && finalStreamUrl.isNotBlank()) {
                        val cleanUrl = if (finalStreamUrl.startsWith("//")) "https:$finalStreamUrl"
                                        else if (finalStreamUrl.startsWith("/")) "https://cdn.zhanime.online$finalStreamUrl"
                                        else finalStreamUrl

                        val isM3u8 = cleanUrl.contains("m3u8", true) || cleanUrl.contains(".txt", true)
                        
                        if (isM3u8) {
                            try {
                                M3u8Helper.generateM3u8(
                                    source = name,
                                    streamUrl = cleanUrl,
                                    referer = fixedUrl,
                                    headers = mapOf("Origin" to mainUrl, "Referer" to fixedUrl),
                                    name = serverName
                                ).forEach { link ->
                                    callback.invoke(link)
                                    found = true
                                }
                            } catch (e: Exception) {
                                emitEmbedFallback(cleanUrl, serverName, fixedUrl, callback)
                                found = true
                            }
                        } else {
                            callback.invoke(
                                newExtractorLink(name, serverName, cleanUrl, ExtractorLinkType.VIDEO) {
                                    this.referer = fixedUrl
                                    this.headers = mapOf("Origin" to mainUrl, "Referer" to fixedUrl)
                                }
                            )
                            found = true
                        }
                    } else {
                        emitEmbedFallback(fixedUrl, serverName, data, callback)
                        found = true
                    }
                } catch (e: Exception) {
                    emitEmbedFallback(fixedUrl, serverName, data, callback)
                    found = true
                }
            } else {
                // External servers (Megaplay, Vidplay, etc.)
                var extracted = false
                try {
                    if (loadExtractor(fixedUrl, data, subtitleCallback, callback)) {
                        extracted = true
                        found = true
                    }
                } catch (e: Exception) {}
                
                if (!extracted) {
                    try {
                        val extHtml = app.get(fixedUrl, headers = mapOf("Referer" to data, "User-Agent" to USER_AGENT)).text
                        val extMatch = Regex("""https?://[^\s"'\\]+?\.(?:m3u8|txt|mp4)[^\s"'\\]*""").find(extHtml)?.value
                        if (extMatch != null) {
                            val isM3u8 = extMatch.contains("m3u8", true) || extMatch.contains(".txt", true)
                            callback.invoke(
                                newExtractorLink(name, serverName, extMatch, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                    this.referer = fixedUrl
                                }
                            )
                            found = true
                        } else {
                            emitEmbedFallback(fixedUrl, serverName, data, callback)
                            found = true
                        }
                    } catch (e: Exception) {
                        emitEmbedFallback(fixedUrl, serverName, data, callback)
                        found = true
                    }
                }
            }
        }

        return found
    }
}
