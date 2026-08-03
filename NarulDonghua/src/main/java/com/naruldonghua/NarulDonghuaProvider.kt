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
            ?: this.selectFirst(".backdrop")?.style()?.let { style ->
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

        // Scrape sidebar or pagination episode lists
        val epElements = document.select(".episodelist ul li, .naveps a, .lister ul li")
        if (epElements.isNotEmpty()) {
            epElements.forEach { el ->
                val a = el.selectFirst("a") ?: return@forEach
                val epHref = fixUrl(a.attr("href"))
                val epTitle = a.selectFirst("h3")?.text()?.trim() ?: a.text().trim()
                val epNum = Regex("""(?:episode|ep\.?)\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                episodes.add(newEpisode(epHref) {
                    this.name = epTitle
                    this.episode = epNum
                })
            }
        } else {
            // If it's a standalone episode page without sidebar list, add itself
            episodes.add(newEpisode(url) {
                this.name = title
                this.episode = 1
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

        // 1. Extract direct iframes in player-embed
        document.select(".player-embed iframe, iframe[src]").forEach { iframe ->
            val src = fixUrl(iframe.attr("src").ifBlank { iframe.attr("data-litespeed-src") })
            if (src.isNotBlank() && !src.contains("about:blank")) {
                runCatching {
                    if (loadExtractor(src, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }
        }

        // 2. Extract mirrors from the <select class="mirror"> element options
        document.select("select.mirror option").forEach { option ->
            val base64Val = option.attr("value")
            if (base64Val.isNotBlank()) {
                runCatching {
                    // Decode base64 HTML string stored in option value
                    val decodedHtml = String(Base64.getDecoder().decode(base64Val))
                    val iframeSrc = Regex("src=[\"'](.*?)[\"']").find(decodedHtml)?.groupValues?.get(1) 
                        ?: Regex("src=(.*?)(\\s|>)").find(decodedHtml)?.groupValues?.get(1)

                    if (!iframeSrc.isNullOrBlank()) {
                        val fixedSrc = fixUrl(iframeSrc.trim('"', '\''))
                        if (loadExtractor(fixedSrc, data, subtitleCallback, callback)) {
                            found = true
                        } else if (fixedSrc.contains("p2pstream.vip") || fixedSrc.contains("abyssplayer")) {
                            // Handle custom streaming domains via direct scraping
                            val iframeText = app.get(fixedSrc, headers = mapOf("Referer" to data)).text
                            val m3u8Match = Regex("""(?:file|src|url)["']?\s*:\s*["']([^"']+(?:m3u8|txt|mp4)[^"']*)["']""").find(iframeText)
                            
                            m3u8Match?.groupValues?.get(1)?.let { streamUrl ->
                                val cleanUrl = streamUrl.replace("\\/", "/")
                                val safeUrl = if (cleanUrl.contains("txt") && !cleanUrl.contains(".m3u8")) "$cleanUrl#.m3u8" else cleanUrl
                                
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "Narul Stream",
                                        url = safeUrl,
                                        type = if (safeUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = fixedSrc
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                found = true
                            }
                        }
                    }
                }
            }
        }

        return found
    }
}
