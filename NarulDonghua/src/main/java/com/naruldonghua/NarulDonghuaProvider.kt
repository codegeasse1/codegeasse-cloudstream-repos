package com.naruldonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64

class NarulDonghuaProvider : MainAPI() {
    override var mainUrl = "https://naruldonghua.com"
    override var name = "Narul Donghua"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/ongoing/" to "Ongoing Series",
        "$mainUrl/completed/" to "Completed Series",
        "$mainUrl/upcoming/" to "Upcoming Series",
        "$mainUrl/comic-series/" to "Comic Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1 && request.data.endsWith("/")) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        val homeItems = mutableListOf<HomePageList>()

        val articles = document.select("article.bs, .swiper-slide.item")
        val cards = articles.mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        if (cards.isNotEmpty()) {
            homeItems.add(HomePageList(request.name, cards))
        }

        return newHomePageResponse(homeItems, hasNext = document.select(".hpage .r, .nav-previous").isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a") ?: return null
        val href = fixUrl(anchor.attr("href"))
        if (href.isBlank() || href.contains("/author/") || href.contains("/genres/")) return null

        val title = this.selectFirst(".tt, h2, .entry-title")?.text()?.trim() 
            ?: anchor.attr("title")
            ?: return null

        val imgEl = this.selectFirst("img")
        val poster = imgEl?.attr("data-src")?.ifBlank { imgEl.attr("src") }
            ?: this.selectFirst(".backdrop")?.attr("style")?.let { style ->
                Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)?.groupValues?.get(1)
            }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article.bs, .listupd .bs").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, .title-section h1")?.text()?.trim() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("–")?.trim() 
            ?: "Unknown Title"

        val poster = document.selectFirst(".thumb img, .mvelement img, meta[property=og:image]")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }.ifBlank { it.attr("content") }
        }

        val plot = document.selectFirst(".desc.mindes, .entry-content p")?.text()?.trim()

        val episodes = mutableListOf<Episode>()

        // 1. Scrape episode containers — added ".eplister ul li", which is the
        // confirmed-working selector on this same WordPress anime theme family
        // (same theme as ChikiAnimation). The previous selector list was
        // missing this exact class, which is why every title fell through
        // to the single-episode fallback below.
        val epElements = document.select(
            ".eplister ul li, .episodelist ul li, .lister ul li, .naveps a, div.ep_list ul li"
        )
        epElements.forEach { el ->
            val a = el.selectFirst("a") ?: if (el.tagName() == "a") el else return@forEach
            val epHref = fixUrl(a.attr("href"))
            if (epHref.isBlank() || epHref == mainUrl) return@forEach

            val epTitle = a.selectFirst("h3, h4, .epl-title")?.text()?.trim() ?: a.text().trim()
            val epNum = Regex("""(?:episode|ep\.?)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: el.selectFirst(".epl-num")?.text()?.trim()?.toIntOrNull()

            episodes.add(newEpisode(epHref) {
                this.name = epTitle
                this.episode = epNum
            })
        }

        // 2. Fallback: If no sidebar list exists, parse current page as a single/main episode
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = title
                this.episode = 1
            })
        }

        // Sort episodes reliably ascending by episode number to prevent backward playback order
        val sortedEpisodes = episodes.sortedBy { it.episode ?: 0 }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            addEpisodes(DubStatus.Subbed, sortedEpisodes.distinctBy { it.data })
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

        suspend fun handleEmbed(src: String): Boolean {
            var linkFound = false

            // ------------------------------------------------------------
            // narulplex.p2pstream.vip custom player
            // ------------------------------------------------------------
            if (src.contains("narulplex.p2pstream.vip")) {
                val id = Regex("""[?&]id=([\w-]+)""").find(src)?.groupValues?.get(1)
                    ?: Regex("""/e/([\w-]+)""").find(src)?.groupValues?.get(1)
                    ?: src.substringAfterLast("#").ifBlank { null }
                    ?: src.trimEnd('/').substringAfterLast("/")

                if (id.isNotBlank()) {
                    try {
                        val videoApiUrl = "https://narulplex.p2pstream.vip/api/v1/video?id=$id&w=1280&h=800&r=naruldonghua.com"
                        val res = app.get(
                            videoApiUrl,
                            headers = mapOf("Referer" to src, "User-Agent" to "Mozilla/5.0")
                        ).text

                        val streamMatch = Regex(""""(?:url|file|src|m3u8)"\s*:\s*"([^"]+\.(?:m3u8|txt)[^"]*)"""")
                            .find(res)?.groupValues?.get(1)
                            ?: Regex("""https?://[^\s"'<>]+\.(?:m3u8|txt)[^\s"'<>]*""").find(res)?.value

                        if (!streamMatch.isNullOrBlank()) {
                            val cleanUrl = streamMatch.replace("\\/", "/")
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "Narul P2P",
                                    url = cleanUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = src
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf("Referer" to src, "Origin" to "https://naruldonghua.com")
                                }
                            )
                            linkFound = true
                        }
                    } catch (_: Exception) { }
                }
            }

            // ------------------------------------------------------------
            // Dailymotion
            // ------------------------------------------------------------
            if (!linkFound && src.contains("dailymotion")) {
                val dmId = Regex("""(?:video/|video=|embed/|/e/)([a-zA-Z0-9]+)""").find(src)?.groupValues?.get(1)
                if (dmId != null) {
                    try {
                        if (loadExtractor("https://www.dailymotion.com/video/$dmId", data, subtitleCallback, callback)) {
                            linkFound = true
                        }
                    } catch (_: Exception) { }
                }
            }

            // ------------------------------------------------------------
            // Rumble — confirmed final playable URL shape:
            //   rumble.com/hls-vod/{id}/playlist.m3u8?u=0&b=0
            // The actual iframe src for this site's Rumble embeds hasn't
            // been captured directly, so we try loadExtractor first (in
            // case CloudStream's built-in Rumble support already handles
            // it), then fall back to guessing the id from common Rumble
            // embed URL shapes (embed/{id}, embed/v{id}) and building the
            // hls-vod URL manually.
            // ------------------------------------------------------------
            if (!linkFound && src.contains("rumble.com")) {
                try {
                    if (loadExtractor(src, data, subtitleCallback, callback)) {
                        linkFound = true
                    }
                } catch (_: Exception) { }

                if (!linkFound) {
                    val rumbleId = Regex("""rumble\.com/embed/v?([\w-]+)""").find(src)?.groupValues?.get(1)
                        ?: Regex("""[?&]video=([\w-]+)""").find(src)?.groupValues?.get(1)

                    if (rumbleId != null) {
                        try {
                            val playlistUrl = "https://rumble.com/hls-vod/$rumbleId/playlist.m3u8?u=0&b=0"
                            val playlistText = app.get(
                                playlistUrl,
                                headers = mapOf("Referer" to src, "User-Agent" to "Mozilla/5.0")
                            ).text

                            if (playlistText.contains("#EXTM3U")) {
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = "Rumble",
                                        url = playlistUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = src
                                        this.quality = Qualities.Unknown.value
                                        this.headers = mapOf("Referer" to src)
                                    }
                                )
                                linkFound = true
                            }
                        } catch (_: Exception) { }
                    }
                }
            }

            // Generic fallback — native CloudStream extractor for anything else
            if (!linkFound) {
                try {
                    if (loadExtractor(src, data, subtitleCallback, callback)) {
                        linkFound = true
                    }
                } catch (_: Exception) { }
            }

            return linkFound
        }

        // Parse direct iframe payloads
        document.select(".player-embed iframe, iframe[src]").forEach { iframe ->
            val src = fixUrl(iframe.attr("src").ifBlank { iframe.attr("data-litespeed-src") })
            if (src.isNotBlank() && !src.contains("about:blank")) {
                if (handleEmbed(src)) found = true
            }
        }

        // Parse base64 dropdown servers (Mirror selector)
        document.select("select.mirror option").forEach { option ->
            val base64Val = option.attr("value")
            if (base64Val.isNotBlank()) {
                runCatching {
                    val decodedBytes = Base64.getDecoder().decode(base64Val)
                    val decodedHtml = String(decodedBytes)
                    
                    val iframeSrc = Regex("src=[\"'](.*?)[\"']", RegexOption.IGNORE_CASE).find(decodedHtml)?.groupValues?.get(1) 
                        ?: Regex("src=([^\\s>]+)", RegexOption.IGNORE_CASE).find(decodedHtml)?.groupValues?.get(1)

                    if (!iframeSrc.isNullOrBlank()) {
                        val fixedSrc = fixUrl(iframeSrc.trim('"', '\'', ' '))
                        var mirrorFound = handleEmbed(fixedSrc)

                        if (!mirrorFound) {
                            // Deep extraction for custom embed domains not caught above
                            val iframeText = app.get(fixedSrc, headers = mapOf("Referer" to data, "User-Agent" to "Mozilla/5.0")).text
                            val m3u8Match = Regex("""(?:file|src|url)["']?\s*:\s*["']([^"']+(?:m3u8|txt|mp4)[^"']*)["']""").find(iframeText)
                            
                            m3u8Match?.groupValues?.get(1)?.let { streamUrl ->
                                val cleanUrl = streamUrl.replace("\\/", "/")
                                val finalUrl = if (cleanUrl.startsWith("//")) "https:$cleanUrl" else cleanUrl
                                val safeUrl = if (finalUrl.contains("txt") && !finalUrl.contains(".m3u8")) "$finalUrl#.m3u8" else finalUrl
                                
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "Narul Stream",
                                        url = safeUrl,
                                        type = if (safeUrl.contains("m3u8") || safeUrl.contains("txt")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = fixedSrc
                                        this.quality = Qualities.Unknown.value
                                        this.headers = mapOf("Referer" to fixedSrc, "Origin" to "https://naruldonghua.com")
                                    }
                                )
                                mirrorFound = true
                            }
                        }

                        if (mirrorFound) found = true
                    }
                }
            }
        }

        return found
    }
}