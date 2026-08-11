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
        
        // Safety check: Throw a readable error if Anilist rejects the query
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

        if (maxEp == 0) maxEp = 1 // Fallback in case of Anilist 0-episode ghosting

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

        if (malId == "0") {
            println("YOMI ERROR: malId is 0. Cannot fetch links without a valid MAL ID.")
            return false
        }

        var found = false
        val types = listOf("sub", "dub")
        val servers = listOf("megaplay", "filemoon", "streamwish", "vidhide", "mp4upload")

        for (type in types) {
            for (server in servers) {
                try {
                    // ADDED: Strict User-Agent to bypass server blocks
                    val apiRes = app.get(
                        "https://api.flikhub.net/$server?mal=$malId&ep=$epNum&type=$type",
                        headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "Origin" to mainUrl,
                            "Accept" to "application/json",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"
                        )
                    ).text

                    // If the API blocks us (returns HTML instead of JSON), skip to the next server
                    if (!apiRes.trim().startsWith("{")) continue

                    val json = JSONObject(apiRes)
                    val sources = json.optJSONArray("sources")
                    
                    if (sources != null && sources.length() > 0) {
                        var fileUrl = sources.getJSONObject(0).optString("file")
                        
                        if (fileUrl.isNotBlank()) {
                            // ADDED: If the API returns a proxy wrapper, extract the direct m3u8 link
                            if (fileUrl.contains("url=")) {
                                val extracted = fileUrl.substringAfter("url=").substringBefore("&")
                                fileUrl = java.net.URLDecoder.decode(extracted, "UTF-8")
                            }

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
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"
                                    )
                                }
                            )
                            found = true
                        }
                    }

                    if (server == servers.first()) {
                        val subtitles = json.optJSONArray("subtitles")
                        if (subtitles != null) {
                            for (i in 0 until subtitles.length()) {
                                val sub = subtitles.getJSONObject(i)
                                var subFile = sub.optString("file")
                                val subLabel = sub.optString("label", "English")
                                
                                if (subFile.isNotBlank()) {
                                    // Extract direct subtitle URL if proxied
                                    if (subFile.contains("url=")) {
                                        val extractedSub = subFile.substringAfter("url=").substringBefore("&")
                                        subFile = java.net.URLDecoder.decode(extractedSub, "UTF-8")
                                    }
                                    subtitleCallback(newSubtitleFile(subLabel, subFile))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("YOMI SERVER ERROR ($server): ${e.message}")
                }
            }
        }
        return found
    }
)
