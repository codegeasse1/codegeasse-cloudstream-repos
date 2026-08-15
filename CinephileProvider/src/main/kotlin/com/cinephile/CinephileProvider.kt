package com.cinephile

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class CinephileProvider : MainAPI() {
    override var mainUrl = "https://cinephile.live"
    override var name = "Cinephile"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie)

    companion object {
        private const val API = "https://api.cinephile.live/api/cinephile"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/#/home" to "Home",
        "$mainUrl/#/anime" to "Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList<HomePageList>())
        val params = if (request.data.contains("anime", true)) "action=tabcontent&tabId=8" else "action=home"
        val json = fetchCine(params) ?: return newHomePageResponse(emptyList<HomePageList>())
        val sections = json.optJSONArray("sections") ?: return newHomePageResponse(emptyList<HomePageList>())
        val rows = mutableListOf<HomePageList>()
        for (i in 0 until sections.length()) {
            val sec = sections.optJSONObject(i) ?: continue
            val secName = sec.optString("section").ifBlank { "Row ${i + 1}" }
            val items = sec.optJSONArray("movies") ?: continue
            val list = mutableListOf<SearchResponse>()
            for (j in 0 until items.length()) {
                items.optJSONObject(j)?.toSearchResult()?.let { list.add(it) }
            }
            if (list.isNotEmpty()) rows.add(HomePageList(secName, list))
        }
        return newHomePageResponse(rows)
    }

    private fun JSONObject.toSearchResult(): SearchResponse? {
        val subjectId = optString("subjectId").ifBlank { return null }
        val title = optString("name").ifBlank { optString("title") }.trim().ifBlank { return null }
        val poster = optString("poster_url").ifBlank { optString("cover") }.ifBlank { null }
        val subjectType = optInt("subjectType", 0)
        val genres = (optJSONArray("genres") ?: JSONArray()).let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        }
        val type = when {
            subjectType == 1 -> TvType.Movie
            genres.any { it.equals("anime", true) } || title.contains("anime", true) -> TvType.Anime
            else -> TvType.TvSeries
        }
        val year = optString("year").take(4).toIntOrNull()
        return newMovieSearchResponse(title, subjectId, type, fix = false) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val seen = LinkedHashMap<String, SearchResponse>()
        val encoded = URLEncoder.encode(query, "UTF-8")
        for (type in listOf("tv", "movie")) {
            val json = fetchCine("action=search&q=$encoded&type=$type") ?: continue
            val arr = json.optJSONArray("data") ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (!obj.optBoolean("hasResource", false)) continue
                val r = obj.toSearchResult() ?: continue
                seen[r.url] = r
            }
        }
        return seen.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val subjectId = url.trim()
        val json = fetchCine("action=detail&subjectId=$subjectId")
            ?: throw RuntimeException("Cinephile API error")
        val title = json.optString("title").trim().ifBlank { "Unknown" }
        val poster = json.optString("poster_url").ifBlank { null }
        val plot = json.optString("description").ifBlank { null }
        val genres = (json.optJSONArray("genres") ?: JSONArray()).let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }
        val seasons = json.optJSONArray("seasons")
        val isSeries = json.optInt("subjectType", 0) == 2 || (seasons != null && seasons.length() > 0)
        val isAnime = genres.any { it.equals("anime", true) } || title.contains("anime", true)
        val year = json.optString("release_date").take(4).toIntOrNull()

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            if (seasons != null) {
                for (i in 0 until seasons.length()) {
                    val s = seasons.optJSONObject(i) ?: continue
                    val sn = s.optInt("se", 1)
                    val maxEp = s.optInt("maxEp", 0)
                    for (ep in 1..maxEp) {
                        episodes.add(
                            newEpisode("$mainUrl/#/ep?sid=$subjectId&s=$sn&e=$ep") {
                                this.name = "Episode $ep"
                                this.episode = ep
                                this.season = sn
                            }
                        )
                    }
                }
            }
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            return newAnimeLoadResponse(title, subjectId, type) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }

        return newMovieLoadResponse(title, subjectId, if (isAnime) TvType.AnimeMovie else TvType.Movie, subjectId) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sidMatch = Regex("sid=(\\d+)").find(data)
        val subjectId: String
        val se: Int
        val ep: Int
        if (sidMatch != null) {
            subjectId = sidMatch.groupValues[1]
            se = Regex("s=(\\d+)").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            ep = Regex("e=(\\d+)").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        } else {
            val parts = data.split("|")
            subjectId = parts.getOrNull(0)?.trim().orEmpty()
            se = parts.getOrNull(1)?.toIntOrNull() ?: 0
            ep = parts.getOrNull(2)?.toIntOrNull() ?: 0
        }
        if (subjectId.isBlank()) return false
        var found = false

        // 1) Per-episode resources: direct CDN links (via the cinephile proxy) or KissKH.
        val resJson = fetchCine("action=resources&id=$subjectId&se=$se&ep=$ep")
        if (resJson != null) {
            val arr = resJson.optJSONArray("data")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    val sourceType = r.optString("sourceType")
                    val sourceEpId = r.optString("sourceEpisodeId")
                    val link = r.optString("resourceLink")
                    val quality = qualityFromSize(r.optString("size").toLongOrNull() ?: 0L)
                    try {
                        if (sourceType.equals("kisskh", true) && sourceEpId.isNotBlank()) {
                            val m3u8 = kisskhStream(sourceEpId)
                            if (m3u8 != null) {
                                val links = M3u8Helper.generateM3u8("$name - KissKH", m3u8, mainUrl)
                                if (links.isEmpty()) {
                                    callback(
                                        newExtractorLink(source = name, name = "KissKH", url = m3u8, type = ExtractorLinkType.M3U8) {
                                            this.referer = mainUrl
                                        }
                                    )
                                } else {
                                    for (l in links) callback(l)
                                }
                                found = true
                            }
                        } else if (link.isNotBlank()) {
                            callback(
                                newExtractorLink(source = name, name = "Stream $quality", url = link, type = ExtractorLinkType.VIDEO) {
                                    this.referer = mainUrl
                                    this.quality = quality
                                }
                            )
                            found = true
                        }
                    } catch (e: Exception) { }
                    val caps = r.optJSONArray("extCaptions")
                    if (caps != null) {
                        for (j in 0 until caps.length()) {
                            val c = caps.optJSONObject(j) ?: continue
                            val u = fixStreamUrl(c.optString("url"))
                            if (u != null) {
                                try {
                                    subtitleCallback(SubtitleFile(c.optString("lan").ifBlank { c.optString("name").ifBlank { "Subtitle" } }, u))
                                } catch (e: Exception) { }
                            }
                        }
                    }
                }
            }
        }

        // 2) VidBinge-style playinfo streams (HLS / DASH / MP4) as a fallback.
        val piJson = fetchCine("action=playinfo&id=$subjectId&se=$se&ep=$ep")
        if (piJson != null) {
            val streams = piJson.optJSONArray("streams")
            if (streams != null) {
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    val u = fixStreamUrl(s.optString("url")) ?: continue
                    val format = s.optString("format").uppercase()
                    val res = s.optString("resolutions")
                    val label = if (res.isNotBlank()) "${res}p" else "Stream"
                    val quality = res.toIntOrNull() ?: Qualities.Unknown.value
                    try {
                        if (format == "HLS" || u.contains(".m3u8", true)) {
                            val links = M3u8Helper.generateM3u8("$name - $label", u, mainUrl)
                            if (links.isEmpty()) {
                                callback(
                                    newExtractorLink(source = name, name = label, url = u, type = ExtractorLinkType.M3U8) {
                                        this.referer = mainUrl
                                        this.quality = quality
                                    }
                                )
                            } else {
                                for (l in links) callback(l)
                            }
                        } else {
                            callback(
                                newExtractorLink(source = name, name = label, url = u, type = ExtractorLinkType.VIDEO) {
                                    this.referer = mainUrl
                                    this.quality = quality
                                }
                            )
                        }
                        found = true
                    } catch (e: Exception) { }
                }
            }
            val caps = piJson.optJSONArray("captions")
            if (caps != null) {
                for (i in 0 until caps.length()) {
                    val c = caps.optJSONObject(i) ?: continue
                    val u = fixStreamUrl(c.optString("url")) ?: continue
                    try {
                        subtitleCallback(SubtitleFile(c.optString("lan").ifBlank { c.optString("name").ifBlank { "Subtitle" } }, u))
                    } catch (e: Exception) { }
                }
            }
        }

        return found
    }

    private suspend fun fetchCine(params: String): JSONObject? {
        return try {
            val res = app.get("$API?$params", headers = mapOf(
                "User-Agent" to UA,
                "Accept" to "application/json, text/plain, */*",
                "Referer" to mainUrl,
            ))
            if (res.text.isBlank()) null else JSONObject(res.text)
        } catch (e: Exception) {
            null
        }
    }

    // KissKH anime source: resolves an episode id to a playable stream URL.
    private suspend fun kisskhStream(episodeId: String): String? {
        for (base in listOf("https://kisskh.do", "https://kisskh.co", "https://kisskh.me")) {
            try {
                val res = app.get("$base/api/DramaList/GetEpisodeStream?id=$episodeId&captionType=1", headers = mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to "$base/",
                    "Origin" to base,
                    "User-Agent" to UA,
                ))
                if (res.text.isBlank()) continue
                val obj = JSONObject(res.text)
                val v = obj.optString("value").ifBlank { obj.optString("stream").ifBlank { obj.optString("url") } }
                if (v.isNotBlank()) return v
            } catch (e: Exception) { }
        }
        return null
    }

    private fun fixStreamUrl(url: String): String? {
        val u = url.trim()
        if (u.isBlank()) return null
        if (u.startsWith("/api/cinephile")) return API + u.substringAfter("/api/cinephile")
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return "https:$u"
        if (u.startsWith("/")) return "$mainUrl$u"
        return "$mainUrl/$u"
    }

    private fun qualityFromSize(size: Long): Int {
        return when {
            size > 4_831_838_208L -> 1080
            size > 1_932_735_283L -> 720
            size > 629_145_600L -> 480
            else -> 720
        }
    }
}
