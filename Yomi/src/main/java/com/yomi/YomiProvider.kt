package com.yomi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

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
        
        // We will test multiple known backend domains in case one is blocked by Cloudflare
        val apiDomains = listOf("api.flikhub.net", "api.yenime.net", "api.animanga.fun")
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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
                        
                        // Strategy 1: Check for a Server Redirect 
                        // (e.g. if api.flikhub.net redirects you straight to megaplay.buzz)
                        if (req.url != apiUrl && req.url.startsWith("http")) {
                            if (loadExtractor(req.url, "$mainUrl/", subtitleCallback, callback)) {
                                found = true
                                break // Stop checking domains, move to the next server
                            }
                        }

                        val apiRes = req.text

                        // Strategy 2: Check for standard JSON Response
                        if (apiRes.trim().startsWith("{")) {
                            val json = JSONObject(apiRes)
                            val sources = json.optJSONArray("sources")
                            
                            if (sources != null && sources.length() > 0) {
                                for (i in 0 until sources.length()) {
                                    val fileUrl = sources.getJSONObject(i).optString("file")
                                    if (fileUrl.isNotBlank()) {
                                        callback(
                                            newExtractorLink(
                                                source = name,
                                                name = "${server.replaceFirstChar { it.uppercase() }} ${type.uppercase()}",
                                                url = fileUrl,
                                                type = if (fileUrl.contains("m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) {
                                                this.referer = "https://${server}.buzz/"
                                                this.quality = Qualities.Unknown.value
                                                this.headers = mapOf(
                                                    "Referer" to "https://${server}.buzz/",
                                                    "User-Agent" to userAgent
                                                )
                                            }
                                        )
                                        found = true
                                    }
                                }
                            }

                            val subtitles = json.optJSONArray("subtitles")
                            if (subtitles != null) {
                                for (i in 0 until subtitles.length()) {
                                    val subFile = subtitles.getJSONObject(i).optString("file")
                                    val subLabel = subtitles.getJSONObject(i).optString("label", "English")
                                    if (subFile.isNotBlank()) subtitleCallback(newSubtitleFile(subLabel, subFile))
                                }
                            }
                            
                            if (found) break 
                        }

                        // Strategy 3: Check for an iFrame hiding inside HTML
                        val iframeRegex = Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""")
                        val iframeUrl = iframeRegex.find(apiRes)?.groupValues?.get(1)
                        if (iframeUrl != null) {
                            if (loadExtractor(iframeUrl, apiUrl, subtitleCallback, callback)) {
                                found = true
                                break
                            }
                        }

                        // Strategy 4: Raw Regex extraction for Proxy links and direct M3U8 files
                        val urlRegex = Regex("""https?://[^\s"'<>\\]+""")
                        val extractedUrls = urlRegex.findAll(apiRes).map { it.value.replace("\\/", "/") }.distinct().toList()

                        for (url in extractedUrls) {
                            if (url.contains("w3.org") || url.contains("googleapis") || url.endsWith(".js") || url.endsWith(".css")) continue

                            if (loadExtractor(url, apiUrl, subtitleCallback, callback)) {
                                found = true
                                continue
                            }

                            if (url.contains(".m3u8") || url.contains(".mp4") || url.contains("proxy")) {
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = "${server.replaceFirstChar { it.uppercase() }} ${type.uppercase()} (Raw)",
                                        url = url,
                                        type = if (url.contains("m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "https://${server}.buzz/"
                                        this.quality = Qualities.Unknown.value
                                        this.headers = mapOf(
                                            "Referer" to "https://${server}.buzz/",
                                            "User-Agent" to userAgent
                                        )
                                    }
                                )
                                found = true
                            }
                        }

                        // Strategy 5: Base64 Encoded URLs
                        val base64Regex = Regex("""["'](aHR0cHM6Ly[a-zA-Z0-9+/=]+)["']""")
                        for (b64 in base64Regex.findAll(apiRes)) {
                            try {
                                val decoded = String(android.util.Base64.decode(b64.groupValues[1], android.util.Base64.DEFAULT))
                                if (decoded.startsWith("http")) {
                                    if (decoded.contains(".m3u8") || decoded.contains(".mp4")) {
                                        callback(
                                            newExtractorLink(
                                                source = name,
                                                name = "${server.replaceFirstChar { it.uppercase() }} ${type.uppercase()} (Decoded)",
                                                url = decoded,
                                                type = if (decoded.contains("m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) {
                                                this.referer = "https://${server}.buzz/"
                                                this.quality = Qualities.Unknown.value
                                            }
                                        )
                                        found = true
                                    } else {
                                        if (loadExtractor(decoded, apiUrl, subtitleCallback, callback)) found = true
                                    }
                                }
                            } catch (e: Exception) {}
                        }

                        if (found) break 
                        
                    } catch (e: Exception) {
                        // The server threw a 403 or 404 block. Ignore it and let the loop try the next domain.
                    }
                }
            }
        }
        return found
    }
}
