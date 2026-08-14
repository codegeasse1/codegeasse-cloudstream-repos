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
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Movie, TvType.OVA)

    private val anilistApi = "https://graphql.anilist.co"

    // yomi's embed servers:
    // server 4 = VidNest  (directly resolvable JSON API)
    // server 5 = Yenime   (flikHub API - returns the same megaplay library behind a proxy)
    private val vidnestApi = "https://animex.animanga.fun"
    private val vidnestUrl = "https://vidnest.fun"
    private val flikhubApi = "https://api.flikhub.net"
    private val yenimeUrl = "https://api.yenime.net"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

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
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "User-Agent" to userAgent
            ),
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
                        coverImage { extraLarge large }
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
            val cover = media.optJSONObject("coverImage")
            val poster = cover?.optString("extraLarge")?.ifBlank { cover.optString("large") } ?: ""

            items.add(newAnimeSearchResponse(title, "$mainUrl/anime/${media.getInt("id")}", TvType.Anime) {
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
                        coverImage { extraLarge large }
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
            val cover = media.optJSONObject("coverImage")
            val poster = cover?.optString("extraLarge")?.ifBlank { cover.optString("large") } ?: ""

            items.add(newAnimeSearchResponse(title, "$mainUrl/anime/${media.getInt("id")}", TvType.Anime) {
                this.posterUrl = poster
            })
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        // url is like https://yomi.to/anime/{anilistId} - the id IS the AniList id
        val animeId = url.substringAfterLast("/").toIntOrNull() ?: throw ErrorLoadingException("Invalid ID")

        val query = """
            query(${'$'}id: Int) {
                Media(id: ${'$'}id) {
                    idMal
                    title { romaji english userPreferred }
                    description
                    coverImage { extraLarge large }
                    episodes
                    nextAiringEpisode { episode }
                    format
                    genres
                }
            }
        """.trimIndent()

        val variables = mapOf("id" to animeId)
        val response = queryAnilist(query, variables).getJSONObject("Media")

        val titleObj = response.getJSONObject("title")
        val title = titleObj.optString("english").ifBlank { titleObj.optString("romaji") }.ifBlank { titleObj.optString("userPreferred") }
        val cover = response.optJSONObject("coverImage")
        val poster = cover?.optString("extraLarge")?.ifBlank { cover.optString("large") } ?: ""
        val plot = response.optString("description")

        val genres = mutableListOf<String>()
        val genresArr = response.optJSONArray("genres")
        if (genresArr != null) {
            for (i in 0 until genresArr.length()) genres.add(genresArr.getString(i))
        }

        val idMal = response.optInt("idMal", 0)
        val malParam = if (idMal > 0) "&mal=$idMal" else ""

        // Use the released episode count: for ongoing shows AniList's `episodes` field is the
        // planned TOTAL, while nextAiringEpisode.episode is the number of the NEXT unreleased
        // episode, so released = next - 1. Fall back to the total (or 12 for unknowns).
        val nextAiring = response.optJSONObject("nextAiringEpisode")?.optInt("episode")
        val maxEp = (nextAiring?.minus(1) ?: response.optInt("episodes", 12)).coerceAtLeast(1)

        val type = when (response.optString("format")) {
            "MOVIE" -> TvType.Movie
            "OVA", "ONA" -> TvType.OVA
            else -> TvType.Anime
        }

        if (type == TvType.Movie) {
            return newMovieLoadResponse(title, url, type, "$mainUrl/watch/$animeId/1?audio=sub$malParam") {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
            }
        }

        // Episode data = watch URL (yomi's own URL format). CloudStream's URL-fixer leaves
        // absolute URLs untouched, and loadLinks() parses the animeId / episode / audio / mal out of it.
        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()
        for (i in 1..maxEp) {
            subEps.add(newEpisode("$mainUrl/watch/$animeId/$i?audio=sub$malParam") {
                this.name = "Episode $i"
                this.episode = i
            })
            dubEps.add(newEpisode("$mainUrl/watch/$animeId/$i?audio=dub$malParam") {
                this.name = "Episode $i"
                this.episode = i
            })
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            addEpisodes(DubStatus.Subbed, subEps)
            addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchMatch = Regex("""/watch/(\d+)/(\d+)""").find(data) ?: return false
        val animeId = watchMatch.groupValues[1].toIntOrNull() ?: return false
        val episode = watchMatch.groupValues[2].toIntOrNull() ?: return false
        val requestedAudio = Regex("""audio=(\w+)""").find(data)?.groupValues?.get(1) ?: "sub"
        val malId = Regex("""mal=(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull()

        // try the requested audio first, then the other one as a fallback
        val audioOrder = listOf(requestedAudio) + listOf(if (requestedAudio == "dub") "sub" else "dub")

        var emitted = false

        for (audio in audioOrder) {
            // ---- server 4: VidNest ----
            try {
                val res = app.get(
                    "$vidnestApi/anime/$animeId/$episode/$audio",
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Accept" to "application/json",
                        "Referer" to vidnestUrl,
                        "Origin" to vidnestUrl
                    )
                )
                if (!res.isSuccessful) continue

                val json = JSONObject(res.text)
                val streams = json.optJSONArray("streams") ?: continue
                val stream = streams.optJSONObject(0) ?: continue
                val streamUrl = stream.optString("url")
                if (streamUrl.isBlank() || streamUrl.startsWith("blob:")) continue

                // subtitles (WebVTT tracks)
                val tracks = json.optJSONArray("tracks")
                if (tracks != null) {
                    for (i in 0 until tracks.length()) {
                        val track = tracks.optJSONObject(i) ?: continue
                        val subUrl = track.optString("file")
                        if (subUrl.isBlank() || subUrl.startsWith("blob:")) continue
                        val label = track.optString("label", "English")
                        subtitleCallback(
                            newSubtitleFile(label, subUrl.replace("\\/", "/")) {
                                this.headers = mapOf(
                                    "Referer" to vidnestUrl,
                                    "User-Agent" to userAgent
                                )
                            }
                        )
                    }
                }

                callback(
                    newExtractorLink(
                        source = name,
                        name = "VidNest ${audio.uppercase()}",
                        url = streamUrl.replace("\\/", "/"),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = vidnestUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Referer" to vidnestUrl,
                            "User-Agent" to userAgent
                        )
                    }
                )
                emitted = true
            } catch (e: Exception) {
                // try the next source / audio
            }

            // ---- server 5: Yenime (flikHub - megaplay library via their proxy) ----
            if (malId != null) {
                try {
                    val res = app.get(
                        "$flikhubApi/megaplay?mal=$malId&ep=$episode&type=$audio",
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Accept" to "application/json",
                            "Referer" to yenimeUrl
                        )
                    )
                    if (res.isSuccessful) {
                        val json = JSONObject(res.text)
                        val streamUrl = json.optString("proxiedUrl").ifBlank { json.optString("m3u8") }
                        if (streamUrl.isNotBlank() && !streamUrl.startsWith("blob:")) {
                            // subtitles
                            val tracks = json.optJSONArray("tracks")
                            if (tracks != null) {
                                for (i in 0 until tracks.length()) {
                                    val track = tracks.optJSONObject(i) ?: continue
                                    val subUrl = track.optString("file")
                                    if (subUrl.isBlank() || subUrl.startsWith("blob:")) continue
                                    val label = track.optString("label", "English")
                                    subtitleCallback(
                                        newSubtitleFile(label, subUrl.replace("\\/", "/")) {
                                            this.headers = mapOf(
                                                "Referer" to "https://megaplay.buzz/",
                                                "User-Agent" to userAgent
                                            )
                                        }
                                    )
                                }
                            }

                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "Yenime ${audio.uppercase()}",
                                    url = streamUrl.replace("\\/", "/"),
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = yenimeUrl
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf(
                                        "Referer" to yenimeUrl,
                                        "User-Agent" to userAgent
                                    )
                                }
                            )
                            emitted = true
                        }
                    }
                } catch (e: Exception) {
                    // try the next source / audio
                }
            }

            if (emitted) break
        }
        return emitted
    }
}
