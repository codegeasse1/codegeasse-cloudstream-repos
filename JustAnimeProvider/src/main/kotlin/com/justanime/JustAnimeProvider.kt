package com.justanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class JustAnimeProvider : MainAPI() {
    override var mainUrl = "https://justanime.to"
    override var name = "JustAnime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Movie, TvType.TvSeries)

    companion object {
        private const val API = "https://core.justanime.to/api"
        private const val PROXY = "https://neko.justanime.to/m3u8-proxy"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    // The API only serves browsers: it requires an Origin/Referer of justanime.to
    // (plus a real browser UA to get past Cloudflare). The video CDN is hotlink-
    // protected, so streams are proxied through the site's own m3u8-proxy, which
    // fetches with the right upstream headers and rewrites every segment URL.
    private val apiHeaders = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/trending" to "Trending",
        "$mainUrl/popular" to "Popular",
        "$mainUrl/latest" to "Latest Episodes",
        "$mainUrl/airing" to "Airing Now",
        "$mainUrl/upcoming" to "Upcoming",
        "$mainUrl/favourites" to "Favourites",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList<HomePageList>())
        val json = fetchApi("/home") ?: return newHomePageResponse(emptyList<HomePageList>())
        val section = when {
            request.data.contains("trending", true) -> "trending"
            request.data.contains("popular", true) -> "popular"
            request.data.contains("latest", true) -> "latestEpisode"
            request.data.contains("airing", true) -> "airing"
            request.data.contains("upcoming", true) -> "upcoming"
            request.data.contains("favourite", true) -> "favourite"
            else -> "trending"
        }
        val arr = json.optJSONArray(section) ?: return newHomePageResponse(emptyList<HomePageList>())
        val list = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.toSearchResult()?.let { list.add(it) }
        }
        val title = when (section) {
            "trending" -> "Trending"
            "popular" -> "Popular"
            "latestEpisode" -> "Latest Episodes"
            "airing" -> "Airing Now"
            "upcoming" -> "Upcoming"
            else -> "Favourites"
        }
        return newHomePageResponse(listOf(HomePageList(title, list)))
    }

    private fun JSONObject.toSearchResult(): SearchResponse? {
        val id = optLong("id", 0L)
        if (id == 0L) return null
        val titleObj = optJSONObject("title")
        val title = titleObj?.optString("english").orEmpty()
            .ifBlank { titleObj?.optString("romaji").orEmpty() }
            .ifBlank { optString("title") }
            .trim()
        if (title.isBlank()) return null
        val poster = optString("cover").ifBlank { optString("bannerImage") }.ifBlank { null }
        val type = when (optString("type").ifBlank { optString("format") }) {
            "MOVIE" -> TvType.AnimeMovie
            else -> TvType.Anime
        }
        val year = optInt("year", 0).takeIf { it > 0 }
        return newMovieSearchResponse(title, id.toString(), type, fix = false) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = fetchApi("/search", mapOf("query" to query, "page" to "1")) ?: return emptyList()
        val arr = json.optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.toSearchResult()?.let { out.add(it) }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.trim().substringBefore("|").substringAfterLast("/")
        if (id.isBlank() || !id.all { it.isDigit() }) throw RuntimeException("Invalid JustAnime url")
        val json = fetchApi("/anime/$id") ?: throw RuntimeException("JustAnime API error")
        val data = json.optJSONObject("data") ?: json
        val titleObj = data.optJSONObject("title")
        val title = titleObj?.optString("english").orEmpty()
            .ifBlank { titleObj?.optString("romaji").orEmpty() }
            .ifBlank { "Unknown" }
        val poster = data.optJSONObject("coverImage")?.optString("extraLarge").orEmpty()
            .ifBlank { data.optString("bannerImage") }
            .ifBlank { null }
        val plot = data.optString("description")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { null }
        val genres = data.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val year = data.optInt("seasonYear", 0).takeIf { it > 0 }
        val format = data.optString("format")

        if (format == "MOVIE") {
            return newMovieLoadResponse(title, id, TvType.AnimeMovie, id) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
            }
        }

        val episodes = mutableListOf<Episode>()
        var page = 1
        while (page <= 30) {
            val epPage = fetchApi("/anime/$id/episodes", mapOf("page" to page.toString())) ?: break
            val arr = epPage.optJSONArray("episodes") ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.toEpisode(id)?.let { episodes.add(it) }
            }
            if (!epPage.optBoolean("hasNextPage", false)) break
            page++
        }
        return newAnimeLoadResponse(title, id, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun JSONObject.toEpisode(id: String): Episode? {
        val num = optInt("number", 0)
        if (num <= 0) return null
        val name = optString("title").trim().ifBlank { "Episode $num" }
        return newEpisode("$id|$num") {
            this.name = name
            this.episode = num
            this.posterUrl = optString("image").ifBlank { null }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val id = parts.getOrNull(0)?.trim().orEmpty()
        val episode = parts.getOrNull(1)?.trim().orEmpty()
        if (id.isBlank() || episode.isBlank()) return false
        var found = false

        for ((langKey, langLabel) in listOf("sub" to "Sub", "dub" to "Dub")) {
            val json = fetchApi("/watch/$id/episode/$episode/anineko/$langKey/hd1") ?: continue
            val headers = json.optJSONObject("headers")?.let { h ->
                mapOf(
                    "Referer" to h.optString("Referer"),
                    "Origin" to h.optString("Origin"),
                ).filterValues { it.isNotBlank() }
            } ?: emptyMap()
            val headerJson = JSONObject(headers).toString()

            val sources = json.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val s = sources.optJSONObject(i) ?: continue
                    val url = s.optString("url").trim()
                    if (url.isBlank()) continue
                    val isM3u8 = s.optBoolean("isM3U8", url.contains(".m3u8", true))
                    val qualityLabel = s.optString("quality").ifBlank { "Auto" }
                    val quality = qualityLabel.filter { it.isDigit() }.toIntOrNull()
                        ?: Qualities.Unknown.value
                    try {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$langLabel $qualityLabel",
                                url = proxyUrl(url, headerJson),
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = quality
                                this.headers = mapOf(
                                    "User-Agent" to UA,
                                    "Referer" to "$mainUrl/",
                                    "Origin" to mainUrl,
                                )
                            }
                        )
                        found = true
                    } catch (e: Exception) { }
                }
            }

            val subs = json.optJSONArray("subtitles")
            if (subs != null) {
                for (i in 0 until subs.length()) {
                    val s = subs.optJSONObject(i) ?: continue
                    val su = s.optString("url").trim()
                    if (su.isBlank()) continue
                    try {
                        subtitleCallback(
                            SubtitleFile(
                                s.optString("lang").ifBlank { "Subtitle" },
                                proxyUrl(su, headerJson)
                            )
                        )
                    } catch (e: Exception) { }
                }
            }
        }
        return found
    }

    private fun proxyUrl(url: String, headerJson: String): String {
        return "$PROXY?url=${URLEncoder.encode(url, "UTF-8")}&headers=${URLEncoder.encode(headerJson, "UTF-8")}"
    }

    private suspend fun fetchApi(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        return try {
            val query = if (params.isEmpty()) "" else "?" + params.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            val res = app.get("$API$path$query", headers = apiHeaders)
            if (res.text.isBlank()) null else JSONObject(res.text)
        } catch (e: Exception) {
            null
        }
    }
}
