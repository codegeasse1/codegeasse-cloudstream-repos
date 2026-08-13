package com.anikage

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

class AniKageProvider : MainAPI() {
    override var mainUrl = "https://anikage.cc"
    override var name = "AniKage"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // Anikage proxies all real stream URLs through this host - it decodes the
    // opaque path returned by the API and re-serves the m3u8/segments with the
    // right headers, so the whole playlist (incl. variants) stays on one host.
    // Mirrors the site's own PUBLIC_PROXY_URL.
    private val proxyUrl = "https://gg.akage.lol"

    private val apiHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    private val videoHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // Exact same rule the site uses (its `Le`/url-builder): absolute URLs are
    // used as-is, leading-slash paths are prefixed with the proxy root, and
    // anything else becomes $proxy/$type/$path.
    private fun buildProxyUrl(path: String, type: String = "stream"): String {
        return when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("/m3u8/") || path.startsWith("/stream/") || path.startsWith("/hls/") -> "$proxyUrl$path"
            path.startsWith("m3u8/") || path.startsWith("stream/") || path.startsWith("hls/") -> "$proxyUrl/$path"
            else -> "$proxyUrl/$type/$path"
        }
    }

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
            
            val parts = responseText.split(Regex("""\"slug\"\s*:\s*\"\"\""))
            for (i in 1 until parts.size) {
                val part = parts[i]
                val slug = part.substringBefore("\"").replace("\\", "")
                if (slug.isBlank() || slug.length > 200) continue

                val window = part.take(2000)

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
                    
                    val window = part.take(1500)
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

        val episodes = mutableListOf<Episode>()

        // Authoritative episode list - the site's own API returns only the
        // RELEASED episodes (ongoing shows stay ahead of the aired count),
        // each with its title. Fall back to the page heuristics if it fails.
        try {
            val responseText = app.get("$mainUrl/api/media/anime/$slug/episodes", headers = apiHeaders).text
            val array = JSONArray(responseText)
            for (i in 0 until array.length()) {
                val epObj = array.getJSONObject(i)
                val number = epObj.optInt("number", 0)
                if (number <= 0) continue
                val epTitle = epObj.optString("title", "").ifBlank { null }
                episodes.add(newEpisode("$mainUrl/anime/watch/$slug?ep=$number") {
                    this.name = epTitle ?: "Episode $number"
                    this.episode = number
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (episodes.isEmpty()) {
            // Fallback: derive the count from the page's own data.
            val currentEps = scriptData.substringAfter("currentEpisode:", "").substringBefore(",").toIntOrNull() ?: 0
            val totalEps = scriptData.substringAfter("totalEpisodes:", "").substringBefore(",").toIntOrNull() ?: 0
            val availableEpisodes = if (currentEps > 0) currentEps else totalEps

            val limit = if (availableEpisodes > 0) availableEpisodes else 1
            for (i in 1..limit) {
                episodes.add(newEpisode("$mainUrl/anime/watch/$slug?ep=$i") {
                    this.name = "Episode $i"
                    this.episode = i
                })
            }
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
        val ep = Regex("""[?&]ep=(\d+)""").find(cleanData)?.groupValues?.get(1) ?: "1"

        val seenUrls = mutableSetOf<String>()
        val seenSubs = mutableSetOf<String>()

        // 1. The real server list for this episode (id -> supported subTypes).
        //    This replaces the old hardcoded guess list, which queried providers
        //    that don't exist and therefore almost always came back empty.
        val servers = mutableListOf<Pair<String, List<String>>>()
        try {
            val serversJson = JSONObject(
                app.get("$mainUrl/api/media/anime/$slug/episodes/$ep/servers", headers = apiHeaders).text
            )
            val serversArr = serversJson.optJSONArray("servers")
            if (serversArr != null) {
                for (i in 0 until serversArr.length()) {
                    val s = serversArr.getJSONObject(i)
                    val pid = s.optString("providerId")
                    if (pid.isBlank()) continue
                    val types = mutableListOf<String>()
                    val typesArr = s.optJSONArray("subTypes")
                    if (typesArr != null) {
                        for (j in 0 until typesArr.length()) {
                            val t = typesArr.optString(j)
                            if (t.isNotBlank()) types.add(t)
                        }
                    }
                    servers.add(pid to types)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Default server first, then the rest.
        val orderedServers = servers.sortedByDescending { it.first == "koto" }

        for ((pid, types) in orderedServers) {
            val langs = if (types.isEmpty()) listOf("sub", "dub") else types

            for (lang in langs) {
                try {
                    val srcJson = JSONObject(
                        app.get(
                            "$mainUrl/api/media/anime/$slug/episodes/$ep/sources?provider=$pid&lang=$lang",
                            headers = apiHeaders
                        ).text
                    )

                    val sources = srcJson.optJSONArray("sources")
                    if (sources != null) {
                        for (i in 0 until sources.length()) {
                            val s = sources.getJSONObject(i)
                            val path = s.optString("url")
                            if (path.isBlank() || s.optString("url").contains("blob:")) continue
                            if (s.optString("embedUrl").isNotBlank() && path == s.optString("embedUrl")) continue

                            val isM3u8 = s.optBoolean("isM3U8", true)
                            val quality = s.optString("quality").ifBlank { "auto" }
                            val linkUrl = buildProxyUrl(path, if (isM3u8) "m3u8" else "stream")
                            if (!seenUrls.add(linkUrl)) continue

                            callback(
                                newExtractorLink(
                                    source = "AniKage",
                                    name = "${pid.replaceFirstChar { it.uppercase() }} ($lang) $quality",
                                    url = linkUrl,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.headers = videoHeaders
                                }
                            )
                            found = true
                        }
                    }

                    val subs = srcJson.optJSONArray("subtitles")
                    if (subs != null) {
                        for (i in 0 until subs.length()) {
                            val t = subs.getJSONObject(i)
                            val file = t.optString("file")
                            if (file.isBlank()) continue
                            val label = t.optString("label", "English")
                            val subUrl = buildProxyUrl(file, "stream")
                            if (!seenSubs.add(subUrl)) continue
                            subtitleCallback(
                                newSubtitleFile(label, subUrl) {
                                    this.headers = videoHeaders
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    // one provider/lang failing shouldn't kill the others
                }
            }
        }

        return found
    }
}
