package com.anikage

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.json.JSONObject
import org.json.JSONArray
import android.util.Base64

class AniKageProvider : MainAPI() {
    override var mainUrl = "https://anikage.cc"
    override var name = "AniKage"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

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

        val videoHeaders = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        // Step 1: Get available servers for this episode
        val availableProviders = mutableListOf<String>()
        try {
            val serversResponse = app.get(
                "$mainUrl/api/media/anime/$slug/episodes/$ep/servers",
                headers = mapOf("Referer" to "$mainUrl/")
            ).text
            
            val serversJson = JSONObject(serversResponse)
            val serversArray = serversJson.optJSONArray("servers") ?: JSONArray()
            
            for (i in 0 until serversArray.length()) {
                val server = serversArray.getJSONObject(i)
                val providerId = server.optString("id", "")
                if (providerId.isNotBlank()) {
                    availableProviders.add(providerId)
                }
            }
        } catch (e: Exception) {
            // Fallback to hardcoded list if API fails
            availableProviders.addAll(listOf("dib", "vibeube", "vidtube", "megatube", "megaplay", "neko", "miko", "wave", "koto"))
        }

        // Step 2: Query sources for each available provider
        val langs = listOf("sub", "dub")

        for (provider in availableProviders) {
            for (lang in langs) {
                try {
                    val apiUrl = "$mainUrl/api/media/anime/$slug/episodes/$ep/sources?provider=$provider&lang=$lang"
                    val responseText = app.get(apiUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                    
                    val json = JSONObject(responseText)
                    val sourcesArray = json.optJSONArray("sources") ?: continue
                    
                    // Process direct sources
                    for (i in 0 until sourcesArray.length()) {
                        val source = sourcesArray.getJSONObject(i)
                        val url = source.optString("url", "")
                        val isM3U8 = source.optBoolean("isM3U8", false)
                        val quality = source.optString("quality", "Unknown")
                        
                        if (url.isNotBlank()) {
                            val displayName = provider.replaceFirstChar { it.uppercase() }
                            
                            callback(
                                newExtractorLink(
                                    source = "AniKage",
                                    name = "$displayName ($lang)",
                                    url = url,
                                    type = if (isM3U8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.headers = videoHeaders
                                }
                            )
                            found = true
                        }
                    }
                    
                    // Process embeds (often have working URLs)
                    val embedsArray = json.optJSONArray("embeds") ?: continue
                    for (i in 0 until embedsArray.length()) {
                        val embed = embedsArray.getJSONObject(i)
                        val embedUrl = embed.optString("url", "")
                        val status = embed.optString("status", "")
                        val type = embed.optString("type", "")
                        
                        // Skip blocked embeds
                        if (status == "blocked" || embedUrl.isBlank()) continue
                        
                        val displayName = provider.replaceFirstChar { it.uppercase() }
                        
                        callback(
                            newExtractorLink(
                                source = "AniKage",
                                name = "$displayName ($lang) - ${type.replaceFirstChar { it.uppercase() }}",
                                url = embedUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.headers = videoHeaders
                            }
                        )
                        found = true
                    }
                    
                    // Process subtitles
                    val subtitlesArray = json.optJSONArray("subtitles") ?: continue
                    for (i in 0 until subtitlesArray.length()) {
                        val subtitle = subtitlesArray.getJSONObject(i)
                        val file = subtitle.optString("file", "")
                        val label = subtitle.optString("label", "Unknown")
                        
                        if (file.isNotBlank()) {
                            subtitleCallback(SubtitleFile(label, file))
                        }
                    }
                    
                } catch (e: Exception) {
                    // Provider doesn't have this episode, continue
                }
            }
        }

        return found
    }
}