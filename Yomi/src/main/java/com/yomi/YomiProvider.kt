package com.yomi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLDecoder

class YomiProvider : MainAPI() {
    override var mainUrl = "https://yomi.to"
    override var name = "Yomi"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val anilistApi = "https://graphql.anilist.co"

    override val mainPage = mainPageOf(
        "TRENDING_DESC" to "Trending Now",
        "POPULARITY_DESC" to "All Time Popular",
        "SCORE_DESC" to "Top Rated",
        "START_DATE_DESC" to "New Releases"
    )

    private suspend fun queryAnilist(query: String, variables: Map<String, Any> = emptyMap()): JSONObject {
        val payload = mapOf(
            "query" to query,
            "variables" to variables
        )

        val req = app.post(
            anilistApi,
            headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            json = payload
        )

        val jsonResponse = JSONObject(req.text)

        if (jsonResponse.has("errors")) {
            val errorMessage = jsonResponse.optJSONArray("errors")?.optJSONObject(0)?.optString("message") ?: "Unknown GraphQL Error"
            throw ErrorLoadingException("Anilist Error: $errorMessage")
        }

        return jsonResponse.getJSONObject("data")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val query = """
            query(${'$'}sort: [MediaSort]) {
                Page(page: $page, perPage: 20) {
                    media(sort: ${'$'}sort, type: ANIME, isAdult: false) {
                        id
                        title { romaji english userPreferred }
                        coverImage { extraLarge }
                    }
                }
            }
        """.trimIndent()

        val variables = mapOf("sort" to listOf(request.data))
        val response = queryAnilist(query, variables)
        val mediaList = response.getJSONObject("Page").getJSONArray("media")

        val items = mutableListOf<SearchResponse>()
        for (i in 0 until mediaList.length()) {
            val media = mediaList.getJSONObject(i)
            val titleObj = media.getJSONObject("title")
            val title = titleObj.optString("english").ifBlank { titleObj.optString("romaji") }.ifBlank { titleObj.optString("userPreferred") }
            val poster = media.getJSONObject("coverImage").optString("extraLarge")
            val id = media.getInt("id")

            items.add(newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
                this.posterUrl = poster
            })
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val gql = """
            query(${'$'}search: String) {
                Page(page: 1, perPage: 20) {
                    media(search: ${'$'}search, type: ANIME, isAdult: false) {
                        id
                        title { romaji english userPreferred }
                        coverImage { extraLarge }
                    }
                }
            }
        """.trimIndent()

        val variables = mapOf("search" to query)
        val response = queryAnilist(gql, variables)
        val mediaList = response.getJSONObject("Page").getJSONArray("media")

        val items = mutableListOf<SearchResponse>()
        for (i in 0 until mediaList.length()) {
            val media = mediaList.getJSONObject(i)
            val titleObj = media.getJSONObject("title")
            val title = titleObj.optString("english").ifBlank { titleObj.optString("romaji") }.ifBlank { titleObj.optString("userPreferred") }
            val poster = media.getJSONObject("coverImage").optString("extraLarge")
            val id = media.getInt("id")

            items.add(newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
                this.posterUrl = poster
            })
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast("/").toIntOrNull() ?: throw ErrorLoadingException("Invalid ID")

        val query = """
            query(${'$'}id: Int) {
                Media(id: ${'$'}id) {
                    idMal
                    title { romaji english userPreferred }
                    description
                    coverImage { extraLarge }
                    episodes
                    nextAiringEpisode { episode }
                    genres
                }
            }
        """.trimIndent()

        val variables = mapOf("id" to id)
        val response = queryAnilist(query, variables).getJSONObject("Media")

        val titleObj = response.getJSONObject("title")
        val title = titleObj.optString("english").ifBlank { titleObj.optString("romaji") }.ifBlank { titleObj.optString("userPreferred") }
        val poster = response.getJSONObject("coverImage").optString("extraLarge")
        val plot = response.optString("description")

        val genres = mutableListOf<String>()
        val genresArr = response.optJSONArray("genres")
        if (genresArr != null) {
            for (i in 0 until genresArr.length()) genres.add(genresArr.getString(i))
        }

        val malId = response.optInt("idMal", 0)

        var maxEp = try {
            if (!response.isNull("nextAiringEpisode")) {
                response.getJSONObject("nextAiringEpisode").getInt("episode") - 1
            } else {
                response.optInt("episodes", 0)
            }
        } catch (e: Exception) { 0 }

        if (maxEp == 0) maxEp = 1

        val episodes = mutableListOf<Episode>()
        for (i in 1..maxEp) {
            episodes.add(newEpisode("$malId|$i") {
                this.name = "Episode $i"
                this.episode = i
            })
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun extractRefererFromProxyUrl(url: String, fallback: String): String {
        val headersParam = Regex("""[?&]headers=([^&]+)""").find(url)?.groupValues?.get(1) ?: return fallback
        return try {
            val decoded = URLDecoder.decode(headersParam, "UTF-8")
            val json = JSONObject(decoded)
            json.optString("Referer", json.optString("referer", fallback))
        } catch (e: Exception) {
            fallback
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val splitData = data.split("|")
        if (splitData.size < 2) return false

        val malId = splitData[0]
        val epNum = splitData[1]

        if (malId == "0") return false

        var found = false
        val types = listOf("sub", "dub")
        val servers = listOf("megaplay", "filemoon", "streamwish", "vidhide", "mp4upload", "vidplay")
        // Added api.yomi.to as a fallback
        val apiDomains = listOf("api.flikhub.net", "api.yenime.net", "api.animanga.fun", "upcloud.animanga.fun", "api.yomi.to")
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        suspend fun addLink(fileUrl: String, label: String, fallbackReferer: String, quality: Int = Qualities.Unknown.value) {
            if (fileUrl.isBlank() || fileUrl.startsWith("blob:")) return
            val cleanUrl = fileUrl.replace("\\/", "/")
            val realReferer = extractRefererFromProxyUrl(cleanUrl, fallbackReferer)
            
            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = cleanUrl,
                    type = if (cleanUrl.contains("m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = realReferer
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to realReferer,
                        "User-Agent" to userAgent
                    )
                }
            )
            found = true
        }

        suspend fun parseM3U8Text(m3u8Text: String, serverName: String, typeName: String, fallbackReferer: String) {
            if (!m3u8Text.contains("#EXTM3U")) return
            
            val lines = m3u8Text.lines()
            var currentQuality = Qualities.Unknown.value
            var currentLabel = "Unknown"
            
            for (line in lines) {
                val trimmedLine = line.trim()
                if (trimmedLine.startsWith("#EXT-X-STREAM-INF:")) {
                    val resolution = Regex("""RESOLUTION=\d+x(\d+)""").find(trimmedLine)?.groupValues?.get(1)?.toIntOrNull()
                    val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(trimmedLine)?.groupValues?.get(1)?.toIntOrNull()
                    val name = Regex("""NAME="([^"]+)"""").find(trimmedLine)?.groupValues?.get(1)
                    
                    if (resolution != null) {
                        currentQuality = when {
                            resolution >= 1080 -> Qualities.P1080.value
                            resolution >= 720 -> Qualities.P720.value
                            resolution >= 480 -> Qualities.P480.value
                            resolution >= 360 -> Qualities.P360.value
                            else -> Qualities.P240.value
                        }
                        currentLabel = name ?: "${resolution}p"
                    } else if (bandwidth != null) {
                        currentQuality = when {
                            bandwidth > 4000000 -> Qualities.P1080.value
                            bandwidth > 2000000 -> Qualities.P720.value
                            bandwidth > 1000000 -> Qualities.P480.value
                            else -> Qualities.P360.value
                        }
                        currentLabel = name ?: "${bandwidth / 1000}kbps"
                    }
                } else if (trimmedLine.startsWith("http") && !trimmedLine.startsWith("blob:")) {
                    addLink(
                        trimmedLine,
                        "$serverName ${typeName.uppercase()} - $currentLabel",
                        fallbackReferer,
                        currentQuality
                    )
                }
            }
        }

        for (type in types) {
            for (server in servers) {
                for (domain in apiDomains) {
                    try {
                        val apiUrl = "https://$domain/$server?mal=$malId&ep=$epNum&type=$type"
                        val req = app.get(
                            apiUrl,
                            headers = mapOf(
                                "Referer" to "$mainUrl/",
                                "Origin" to mainUrl,
                                "User-Agent" to userAgent
                            )
                        )

                        if (req.url != apiUrl && req.url.startsWith("http") && !req.url.startsWith("blob:")) {
                            if (loadExtractor(req.url, "$mainUrl/", subtitleCallback, callback)) {
                                found = true
                                break
                            }
                        }

                        val apiRes = req.text

                        // Strategy 1: JSON Parsing
                        if (apiRes.trim().startsWith("{")) {
                            try {
                                val json = JSONObject(apiRes)
                                val sources = json.optJSONArray("sources")
                                if (sources != null && sources.length() > 0) {
                                    for (i in 0 until sources.length()) {
                                        val fileUrl = sources.getJSONObject(i).optString("file")
                                        addLink(fileUrl, "${server.replaceFirstChar { it.uppercase() }} ${type.uppercase()}", "https://${server}.buzz/")
                                    }
                                }
                                
                                val subtitles = json.optJSONArray("subtitles")
                                if (subtitles != null) {
                                    for (i in 0 until subtitles.length()) {
                                        val subFile = subtitles.getJSONObject(i).optString("file")
                                        val subLabel = subtitles.getJSONObject(i).optString("label", "English")
                                        if (subFile.isNotBlank() && !subFile.startsWith("blob:")) {
                                            subtitleCallback(newSubtitleFile(subLabel, subFile.replace("\\/", "/")))
                                        }
                                    }
                                }
                                if (found) break
                            } catch (e: Exception) {}
                        }

                        // Strategy 2: Raw M3U8 Text Parsing
                        if (apiRes.contains("#EXTM3U")) {
                            parseM3U8Text(apiRes, server.replaceFirstChar { it.uppercase() }, type, "https://${server}.buzz/")
                            if (found) break
                        }

                        // Strategy 3: iFrame Extraction
                        val iframeRegex = Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""")
                        val iframeUrl = iframeRegex.find(apiRes)?.groupValues?.get(1)
                        if (iframeUrl != null && !iframeUrl.startsWith("blob:")) {
                            if (loadExtractor(iframeUrl, apiUrl, subtitleCallback, callback)) {
                                found = true
                                break
                            }
                        }

                        // Strategy 4: Regex URL Extraction (Fixed to handle escaped slashes like https:\/\/)
                        val urlRegex = Regex("""https?:\\?/\\?[^\s"'<>]+""")
                        val extractedUrls = urlRegex.findAll(apiRes).map { it.value.replace("\\/", "/") }.distinct().toList()

                        for (url in extractedUrls) {
                            if (url.contains("w3.org") || url.contains("googleapis") || url.endsWith(".js") || url.endsWith(".css") || url.startsWith("blob:")) continue
                            
                            if (loadExtractor(url, apiUrl, subtitleCallback, callback)) {
                                found = true
                                continue
                            }

                            if (url.contains(".m3u8") || url.contains(".mp4") || url.contains("proxy") || url.contains("m3u8-proxy") || url.contains("/fetch")) {
                                addLink(url, "${server.replaceFirstChar { it.uppercase() }} ${type.uppercase()} (Raw)", "https://${server}.buzz/")
                            }
                        }
                        
                        // Strategy 5: Raw Subtitle Extraction (.vtt)
                        val subRegex = Regex("""https?:\\?/\\?[^\s"'<>]+\.vtt[^\s"'<>]*""")
                        val extractedSubs = subRegex.findAll(apiRes).map { it.value.replace("\\/", "/") }.distinct().toList()
                        for (subUrl in extractedSubs) {
                            if (subUrl.startsWith("blob:")) continue
                            subtitleCallback(newSubtitleFile("English", subUrl))
                        }

                        if (found) break

                    } catch (e: Exception) {
                        // Ignore and try next domain/server
                    }
                }
                if (found) break
            }
            if (found) break
        }
        return found
    }
}