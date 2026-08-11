package com.anikage

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class AniKageProvider : MainAPI() {
    override var mainUrl = "https://anikage.cc"
    override var name = "AniKage"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // Intercepts and cleans JSON-escaped URLs (e.g., https:\/\/prox.anicore.tv\/...)
    private fun String.unesc(): String = this
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\\"", "\"")

    private fun extractSectionArray(scriptData: String, key: String): List<SearchResponse> {
        val startStr = "$key:["
        val startIndex = scriptData.indexOf(startStr)
        if (startIndex == -1) return emptyList()
        
        var bracketCount = 1
        var endIndex = -1
        
        for (i in (startIndex + startStr.length) until scriptData.length) {
            if (scriptData[i] == '[') bracketCount++
            else if (scriptData[i] == ']') bracketCount--
            
            if (bracketCount == 0) {
                endIndex = i
                break
            }
        }
        
        if (endIndex == -1) return emptyList()
        
        val arrayStr = scriptData.substring(startIndex, endIndex)
        val parts = arrayStr.split("slug:\"")
        val items = mutableListOf<SearchResponse>()
        
        for (i in 1 until parts.size) {
            val part = parts[i]
            val slug = part.substringBefore("\"")
            if (slug.isBlank() || slug.length > 200) continue
            
            val titleBlock = part.substringAfter("title:{", "").substringBefore("}")
            var title = titleBlock.substringAfter("english:\"", "").substringBefore("\"")
            if (title.isBlank() || title == titleBlock) title = titleBlock.substringAfter("userPreferred:\"", "").substringBefore("\"")
            if (title.isBlank() || title == titleBlock) title = titleBlock.substringAfter("romaji:\"", "").substringBefore("\"")
            if (title.isBlank() || title == titleBlock) title = slug
            
            val coverBlock = part.substringAfter("coverImage:{", "").substringBefore("}")
            var poster = coverBlock.substringAfter("extraLarge:\"", "").substringBefore("\"")
            if (poster.isBlank() || poster == coverBlock) poster = coverBlock.substringAfter("large:\"", "").substringBefore("\"")
            if (poster == coverBlock) poster = ""
            
            val url = "$mainUrl/anime/info/$slug"
            if (items.none { it.url == url }) {
                items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get(mainUrl).text
        val scriptData = Jsoup.parse(html).select("script").html()
        
        val homeItems = mutableListOf<HomePageList>()
        val sections = listOf(
            "trendinganime" to "Trending",
            "seasonalanime" to "Popular This Season",
            "top10anime" to "Top 10 Anime",
            "popularmovies" to "Popular Movies",
            "upcominganime" to "Coming Soon"
        )
        
        for ((key, title) in sections) {
            val items = extractSectionArray(scriptData, key)
            if (items.isNotEmpty()) {
                homeItems.add(HomePageList(title, items))
            }
        }
        
        return newHomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val apiUrl = "$mainUrl/api/media/anime/search?q=$query&limit=20&adult=true"
        val items = mutableListOf<SearchResponse>()

        try {
            val responseText = app.get(apiUrl, headers = mapOf("Referer" to "$mainUrl/")).text
            
            val parts = responseText.split(Regex(""""slug"\s*:\s*""""))
            for (i in 1 until parts.size) {
                val part = parts[i]
                val slug = part.substringBefore("\"").replace("\\", "")
                if (slug.isBlank() || slug.length > 200) continue

                val window = part.take(2000).unesc()

                var title = ""
                val titleBlock = window.substringAfter("\"title\":", "").substringBefore("}")
                if (titleBlock.contains("\"english\":")) {
                    title = titleBlock.substringAfter("\"english\":\"", "").substringBefore("\"")
                }
                if (title.isBlank() || title == titleBlock) {
                    title = titleBlock.substringAfter("\"userPreferred\":\"", "").substringBefore("\"")
                }
                if (title.isBlank() || title == titleBlock) {
                    title = titleBlock.substringAfter("\"romaji\":\"", "").substringBefore("\"")
                }
                if (title.isBlank() || title == titleBlock) {
                    title = window.substringAfter("\"title\":\"", "").substringBefore("\"")
                }
                if (title.isBlank() || title.contains("{")) {
                    title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
                }

                title = title.replace("\\\"", "\"").replace("\\/", "/")

                var poster = ""
                val coverBlock = window.substringAfter("\"coverImage\":", "").substringBefore("}")
                if (coverBlock.contains("\"extraLarge\":")) {
                    poster = coverBlock.substringAfter("\"extraLarge\":\"", "").substringBefore("\"")
                }
                if (poster.isBlank() || poster == coverBlock) {
                    poster = coverBlock.substringAfter("\"large\":\"", "").substringBefore("\"")
                }
                if (poster.isBlank() || poster == coverBlock) {
                    poster = window.substringAfter("\"poster\":\"", "").substringBefore("\"")
                }
                if (poster == coverBlock) poster = ""
                poster = poster.replace("\\/", "/")

                val url = "$mainUrl/anime/info/$slug"
                if (items.none { it.url == url }) {
                    items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                        this.posterUrl = poster
                    })
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (items.isEmpty()) {
            try {
                val html = app.get("$mainUrl/browse?search=$query").text
                val scriptData = Jsoup.parse(html).select("script").html()
                val parts = scriptData.split("slug:\"")
                
                for (i in 1 until parts.size) {
                    val part = parts[i]
                    val slug = part.substringBefore("\"")
                    if (slug.isBlank() || slug.length > 200) continue
                    
                    val window = part.take(1500).unesc()
                    val titleBlock = window.substringAfter("title:{", "").substringBefore("}")
                    var title = titleBlock.substringAfter("english:\"", "").substringBefore("\"")
                    if (title.isBlank() || title == titleBlock) title = titleBlock.substringAfter("userPreferred:\"", "").substringBefore("\"")
                    if (title.isBlank() || title == titleBlock) title = titleBlock.substringAfter("romaji:\"", "").substringBefore("\"")
                    if (title.isBlank() || title == titleBlock) title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
                    
                    val coverBlock = window.substringAfter("coverImage:{", "").substringBefore("}")
                    var poster = coverBlock.substringAfter("extraLarge:\"", "").substringBefore("\"")
                    if (poster.isBlank() || poster == coverBlock) poster = coverBlock.substringAfter("large:\"", "").substringBefore("\"")
                    if (poster == coverBlock) poster = ""
                    
                    val url = "$mainUrl/anime/info/$slug"
                    if (items.none { it.url == url }) {
                        items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                            this.posterUrl = poster
                        })
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast("/")
        val html = app.get(url).text
        val document = Jsoup.parse(html)
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("img[src*='anilistcdn']")?.attr("src") 
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        
        val scriptData = document.select("script").html()
        var plot = scriptData.substringAfter("description:\"", "").substringBefore("\",")
            .replace("\\u003C", "<").replace("\\n", "\n")
        
        if (plot.isBlank() || plot.length > 5000) {
            plot = document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        } else {
            plot = Jsoup.parse(plot).text()
        }

        val currentEps = scriptData.substringAfter("currentEpisode:", "").substringBefore(",").toIntOrNull() ?: 0
        val totalEps = scriptData.substringAfter("totalEpisodes:", "").substringBefore(",").toIntOrNull() ?: 0
        val availableEpisodes = if (currentEps > 0) currentEps else totalEps

        val episodes = mutableListOf<Episode>()
        
        val limit = if (availableEpisodes > 0) availableEpisodes else 1
        for (i in 1..limit) {
            episodes.add(newEpisode("$mainUrl/anime/watch/$slug?ep=$i") {
                this.name = "Episode $i"
                this.episode = i
            })
        }
        
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
        var found = false
        val cleanData = data.substringBefore("#")
        val slug = cleanData.substringAfter("/watch/").substringBefore("?").substringBefore("/")
        val ep = Regex("""[?&]ep=(\d+)""").find(cleanData)?.groupValues?.get(1)
            ?: Regex("""/(\d+)""").find(cleanData)?.groupValues?.get(1)
            ?: "1"

        val siteHeaders = mapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        val htmlRaw = app.get(cleanData, headers = siteHeaders).text
        val cleanHtml = htmlRaw.unesc()

        // 1. Direct Scrape for Embedded Proxies (Dib) & Subtitles
        try {
            val proxyMatches = Regex("""https?://(?:prox\.anicore\.tv|prox\.anikage\.cc|morning-credit-[^\s"'<>\\]+\.workers\.dev)/[^\s"'<>\\]+""").findAll(cleanHtml)
            for (m in proxyMatches) {
                val url = m.value
                if (url.contains("/stream/")) {
                    subtitleCallback(SubtitleFile("English", url))
                } else {
                    callback(
                        newExtractorLink(
                            source = "Dib Proxy",
                            name = "Dib Direct Stream",
                            url = url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = siteHeaders
                        }
                    )
                    found = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Dynamic Provider Harvesting
        val activeProviders = mutableSetOf<String>()
        val doc = Jsoup.parse(htmlRaw)
        
        doc.select("[data-provider], [provider], .server-item, button, div[class*='server'], li").forEach { el ->
            listOf(el.attr("data-provider"), el.attr("provider"), el.attr("data-server"), el.text()).forEach { attr ->
                val cleanAttr = attr.trim().lowercase()
                if (cleanAttr.isNotBlank() && cleanAttr.length < 15 && !cleanAttr.contains("server") && !cleanAttr.contains("sub") && !cleanAttr.contains("dub")) {
                    activeProviders.add(cleanAttr)
                }
            }
        }

        val fallbackProviders = listOf("dib", "vidtube", "vibeube", "megaplay", "megatube", "koto", "wave", "miko", "neko", "ken")
        fallbackProviders.forEach { activeProviders.add(it) }

        val langs = mutableListOf("sub")
        if (cleanHtml.contains("\"dub\"", ignoreCase = true) || cleanHtml.contains("lang=dub", ignoreCase = true)) {
            langs.add("dub")
        }

        val exclusions = listOf("jquery", "fonts", "anilist", "thetvdb", "jsdelivr", "w3.org")

        // 3. Main API Loop
        for (lang in langs) {
            for (provider in activeProviders) {
                val apiUrl = "$mainUrl/api/media/anime/$slug/episodes/$ep/sources?provider=$provider&lang=$lang"

                try {
                    val responseText = app.get(apiUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                    if (responseText.isBlank() || responseText.length < 10 || responseText.contains("error", true)) continue

                    // CRITICAL: Unescape the JSON slashes to allow the regex to capture prox.anicore URLs
                    val unescapedRes = responseText.unesc()
                    val candidates = mutableSetOf<String>()

                    Regex("""https?://[^\s"'<>\\]+""").findAll(unescapedRes).forEach { candidates.add(it.value) }

                    for (rawUrl in candidates) {
                        if (exclusions.any { rawUrl.contains(it, true) }) continue

                        // Automatically route subtitle files
                        if (rawUrl.contains("/stream/")) {
                            subtitleCallback(SubtitleFile("English", rawUrl))
                            continue
                        }

                        val isProxy = rawUrl.contains("prox.") || rawUrl.contains("workers.dev")
                        val isDirectM3u8 = rawUrl.contains(".m3u8") || rawUrl.contains("/m3u8/") || isProxy
                        val isDirectMp4 = rawUrl.contains(".mp4")

                        if (isDirectM3u8 || isDirectMp4) {
                            val displayName = provider.replaceFirstChar { it.uppercase() }
                            callback(
                                newExtractorLink(
                                    source = displayName,
                                    name = "$displayName ($lang)",
                                    url = rawUrl,
                                    type = if (isDirectM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.headers = siteHeaders
                                }
                            )
                            found = true
                        } else if (rawUrl.startsWith("http")) {
                            val extractedLinks = mutableListOf<ExtractorLink>()
                            
                            if (loadExtractor(rawUrl, cleanData, subtitleCallback) { link ->
                                extractedLinks.add(link)
                            }) {
                                found = true
                            }
                            
                            for (link in extractedLinks) {
                                val displayName = provider.replaceFirstChar { it.uppercase() }
                                callback(
                                    newExtractorLink(
                                        source = displayName,
                                        name = link.name,
                                        url = link.url,
                                        type = if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = link.quality
                                        this.headers = siteHeaders
                                        this.extractorData = link.extractorData
                                        this.referer = link.referer
                                    }
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Suppress API miss
                }
            }
        }

        return found
    }
}