package com.animekhor

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class AnimeKhorProvider : MainAPI() {
    override var mainUrl = "https://animekhor.org"
    override var name = "AnimeKhor"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?status=&type=&order=update" to "Latest Release",
        "$mainUrl/anime/?status=&type=&order=popular" to "Popular",
        "$mainUrl/anime/?status=completed&type=&order=update" to "Completed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data.replace("?", "page/$page/?")
        val document = app.get(url).document
        
        val homeItems = document.select("article.bs, .listupd .bsx").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, homeItems)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("a") ?: return null
        val rawHref = fixUrlNull(linkEl.attr("href"))?.trimEnd('/') ?: return null
        
        val href = rawHref.replace(Regex("-(episode|ep)-\\d+.*$"), "")
            .let { if (it.contains("/anime/")) it else "$mainUrl/anime/${it.substringAfterLast("/")}/" }

        val title = linkEl.attr("title").ifBlank { this.selectFirst(".tt h2, .tt, .eggtitle")?.text() }?.trim() ?: return null

        val img = this.selectFirst("img")
        val rawPoster = img?.attr("data-lazy-src")?.ifBlank { img.attr("data-src") }?.ifBlank { img.attr("src") }
        val posterUrl = fixUrlNull(rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            this.dubStatus = if (this@toSearchResult.text().contains("Dub", ignoreCase = true)) {
                mutableSetOf(DubStatus.Dubbed)
            } else {
                mutableSetOf(DubStatus.Subbed)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        val targetLimit = 100
        var page = 1

        while (searchResults.size < targetLimit) {
            val url = if (page == 1) {
                "$mainUrl/?s=$query"
            } else {
                "$mainUrl/page/$page/?s=$query"
            }

            try {
                val document = app.get(url).document
                
                val items = document.select("article.bs, .listupd .bsx").mapNotNull { element ->
                    element.toSearchResult()
                }.distinctBy { it.url }

                if (items.isEmpty()) break

                searchResults.addAll(items)
                page++
            } catch (e: Exception) {
                break
            }
        }

        return searchResults.take(targetLimit)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".infolimit h2[itemprop=partOfSeries], .infolimit h2, .infox h1.entry-title")?.text()
            ?: document.selectFirst("h1.entry-title")?.text()?.replace(Regex("(?i)(episode|ep)\\s*\\d+.*"), "")?.trim()
            ?: "Unknown Series"

        val posterElement = document.selectFirst(".bigcontent .thumb img, .bixbox .thumb img, article .thumb img, .infox .imgbox img, .ts-post-image")
        val rawPoster = posterElement?.attr("data-lazy-src")?.ifBlank { posterElement.attr("data-src") }?.ifBlank { posterElement.attr("src") }
        var poster = fixUrlNull(rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://"))

        if (poster.isNullOrBlank()) {
            val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
            if (ogImage != null && !ogImage.contains("logo", true) && !ogImage.contains("banner", true)) {
                poster = fixUrlNull(ogImage)
            }
        }

        val plot = document.selectFirst(".entry-content, .synp .entry-content, #synopsis, .desc")?.text()?.trim()
        val tags = document.select("a[href*=/genres/], .genxed a").map { it.text() }

        val episodes = mutableListOf<Episode>()

        val sideBarEps = document.select(".episodelist ul li")
        for (ep in sideBarEps) {
            val aTag = ep.selectFirst("a") ?: continue
            val epUrl = fixUrlNull(aTag.attr("href")) ?: continue
            val epTitle = ep.selectFirst(".playinfo h3")?.text() ?: ""
            val epThumb = fixUrlNull(ep.selectFirst(".thumbnel img")?.attr("src"))

            val epNum = Regex("""(?i)episode\s+(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            episodes.add(newEpisode(epUrl) {
                this.name = epTitle
                this.posterUrl = epThumb
                this.episode = epNum
            })
        }

        val listEps = document.select(".eplister ul li")
        for (ep in listEps) {
            val aTag = ep.selectFirst("a") ?: continue
            val epUrl = fixUrlNull(aTag.attr("href")) ?: continue
            
            val epNumText = ep.selectFirst(".epl-num")?.text() ?: ""
            val epTitleText = ep.selectFirst(".epl-title")?.text() ?: ""
            
            val epNum = Regex("""\d+""").find(epNumText)?.value?.toIntOrNull() 
                ?: Regex("""\d+""").find(epTitleText)?.value?.toIntOrNull()
                
            val epName = if (epTitleText.isNotBlank()) epTitleText else if (epNumText.isNotBlank()) epNumText else "Episode $epNum"

            episodes.add(newEpisode(epUrl) {
                this.name = epName.trim()
                this.episode = epNum
            })
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = title
            })
        } else {
            episodes.reverse()
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.distinctBy { it.data }) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
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
        val embedLinks = mutableListOf<Pair<String, String>>()

        val siteHeaders = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        fun addEmbed(url: String?, name: String) {
            if (url.isNullOrBlank()) return
            var cleanUrl = url.trim().replace("\\/", "/")
            if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
            if (cleanUrl.startsWith("http")) {
                if (embedLinks.none { it.first == cleanUrl }) {
                    embedLinks.add(Pair(cleanUrl, name))
                }
            }
        }

        val mirrorOptions = document.select("select.mirror option")
        for (opt in mirrorOptions) {
            val encodedValue = opt.attr("value")
            val serverName = opt.text().trim()

            if (encodedValue.isNotBlank()) {
                if (encodedValue.startsWith("http") || encodedValue.startsWith("//")) {
                    addEmbed(encodedValue, serverName)
                } else {
                    try {
                        val decodedHtml = String(Base64.decode(encodedValue, Base64.DEFAULT), Charsets.UTF_8).trim()
                        val src = Jsoup.parse(decodedHtml).select("iframe").attr("src").ifBlank { decodedHtml }
                        val finalUrl = fixUrlNull(src) ?: Regex("""https?://[^\s"'<>]+""").find(decodedHtml)?.value
                        addEmbed(finalUrl, serverName)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        val iframes = document.select(".player-embed iframe, #embed_holder iframe, #pembed iframe")
        for (iframe in iframes) {
            val iframeSrc = fixUrlNull(iframe.attr("src"))
            addEmbed(iframeSrc, "Default Player")
        }

        for ((embedUrl, serverLabel) in embedLinks) {
            try {
                // 1. AbyssPlayer Handler
                if (embedUrl.contains("abyssplayer.com", ignoreCase = true) || embedUrl.contains("abyss", ignoreCase = true)) {
                    val html = app.get(embedUrl, headers = siteHeaders).text
                    val mp4Url = Regex("""<source[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                        ?: Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                        ?: Regex("""https?://[^\s"'<>]+?\.mp4(?:\?[^\s"'<>]*)?""").find(html)?.value

                    if (!mp4Url.isNullOrBlank()) {
                        callback(
                            ExtractorLink(
                                source = serverLabel.ifBlank { "AbyssPlayer" },
                                name = if (serverLabel.isNotBlank()) "$serverLabel (Abyss)" else "AbyssPlayer",
                                url = mp4Url,
                                referer = embedUrl,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.VIDEO,
                                headers = mapOf("Referer" to embedUrl),
                                extractorData = ""
                            )
                        )
                        found = true
                        continue
                    }
                }

                // 2. Native Extractors (ok.ru, Turbovid, StreamWish, Dailymotion, Rumble, etc.)
                val tempLinks = mutableListOf<ExtractorLink>()
                if (loadExtractor(embedUrl, data, subtitleCallback) { tempLinks.add(it) }) {
                    for (link in tempLinks) {
                        callback(
                            ExtractorLink(
                                source = if (serverLabel.isNotBlank() && !serverLabel.contains("Default")) serverLabel else link.source,
                                name = if (serverLabel.isNotBlank() && !serverLabel.contains("Default")) "$serverLabel (${link.name})" else link.name,
                                url = link.url,
                                referer = link.referer.ifBlank { embedUrl },
                                quality = link.quality,
                                type = link.type,
                                headers = siteHeaders,
                                extractorData = link.extractorData
                            )
                        )
                    }
                    found = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return found
    }
}
