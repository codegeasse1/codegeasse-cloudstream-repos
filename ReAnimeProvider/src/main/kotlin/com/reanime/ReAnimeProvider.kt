package com.reanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject

class ReAnimeProvider : MainAPI() {
    override var mainUrl = "https://reanime.to"
    override var name = "Re:Anime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val KNOWN_HOSTS = listOf("vidhide", "streamwish", "filemoon", "dood", "voe", "ok.ru", "vk.com", "mixdrop", "mp4upload", "megaup", "anicore")
    }

    private fun String.unesc(): String = this
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")

    private fun extractSectionArray(html: String, key: String): List<SearchResponse> {
        val match = Regex("""["']?$key["']?\s*:\s*\[""").find(html) ?: return emptyList()
        val startIdx = html.indexOf('[', match.range.first)
        if (startIdx == -1) return emptyList()

        var bracketCount = 0
        var endIdx = -1
        for (i in startIdx until html.length) {
            if (html[i] == '[') bracketCount++
            else if (html[i] == ']') bracketCount--
            if (bracketCount == 0) {
                endIdx = i + 1
                break
            }
        }
        if (endIdx == -1) return emptyList()

        val arrayStr = html.substring(startIdx, endIdx)
        val items = mutableListOf<SearchResponse>()
        val parts = arrayStr.split(Regex("""["']?anime_id["']?\s*:\s*["']"""))

        for (i in 1 until parts.size) {
            val part = parts[i]
            val animeId = part.substringBefore("\"").substringBefore("'")
            if (animeId.isBlank() || animeId.length > 200) continue

            val window = part.take(2000).unesc()

            var title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?user_preferred["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?romaji["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?native["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = animeId.replace("-", " ").replaceFirstChar { it.uppercase() }

            var poster = Regex("""["']?extra_large["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""["']?large["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""["']?medium["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""

            val url = "$mainUrl/anime/$animeId"
            if (items.none { it.url == url }) {
                items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster.unesc()
                })
            }
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get("$mainUrl/home", headers = mapOf("User-Agent" to UA)).text
        val homeItems = mutableListOf<HomePageList>()

        val sections = listOf(
            "latest_aired" to "Latest Episodes",
            "new_on_site" to "New on Site",
            "trending" to "Trending",
            "upcoming" to "Upcoming"
        )

        for ((key, title) in sections) {
            val items = extractSectionArray(html, key)
            if (items.isNotEmpty()) homeItems.add(HomePageList(title, items))
        }

        return newHomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.get("$mainUrl/search?q=$query", headers = mapOf("User-Agent" to UA)).text
        val items = mutableListOf<SearchResponse>()
        val parts = html.split(Regex("""["']?anime_id["']?\s*:\s*["']"""))

        for (i in 1 until parts.size) {
            val part = parts[i]
            val animeId = part.substringBefore("\"").substringBefore("'")
            if (animeId.isBlank() || animeId.length > 200) continue

            val window = part.take(2000).unesc()

            var title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?user_preferred["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?romaji["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = animeId.replace("-", " ").replaceFirstChar { it.uppercase() }

            var poster = Regex("""["']?extra_large["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""["']?large["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""

            val url = "$mainUrl/anime/$animeId"
            if (items.none { it.url == url }) {
                items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster.unesc()
                })
            }
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = mapOf("User-Agent" to UA)).text
        val document = Jsoup.parse(html)
        val slug = url.substringAfter("/anime/").substringAfter("/watch/").substringBefore("?")

        var title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
        if (title.isNullOrBlank()) title = Regex("""["']?user_preferred["']?\s*:\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
        if (title.isNullOrBlank()) title = document.selectFirst("h1")?.text()?.trim() ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

        var poster = Regex("""["']?extra_large["']?\s*:\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
        if (poster.isNullOrBlank()) poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        poster = poster.unesc()

        var plot = Regex("""["']?description["']?\s*:\s*["']((?:[^"'\\]|\\.)*)["']""").find(html)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\u003C", "<")
        plot = if (plot.isNullOrBlank()) document.selectFirst("meta[property=og:description]")?.attr("content") ?: "" else Jsoup.parse(plot).text()

        var anilistId = Regex("""bx(\d+)""").find(poster)?.groupValues?.get(1)
        if (anilistId.isNullOrBlank()) anilistId = Regex(""""anilist(?:_id)?"\s*:\s*(\d+)""").find(html)?.groupValues?.get(1) ?: ""
        
        val malId = Regex(""""mal(?:_id)?"\s*:\s*(\d+)""").find(html)?.groupValues?.get(1) ?: ""

        val episodes = mutableListOf<Episode>()

        try {
            val epsRes = app.get("$mainUrl/api/v1/anime/$slug/episodes?limit=2000", headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")).text
            val jsonArray = if (epsRes.trim().startsWith("[")) JSONArray(epsRes) else {
                val o = JSONObject(epsRes)
                o.optJSONArray("data") ?: o.optJSONArray("episodes") ?: JSONArray()
            }

            for (i in 0 until jsonArray.length()) {
                val epObj = jsonArray.getJSONObject(i)
                val epNum = epObj.optInt("episode_number", epObj.optInt("number", -1))
                if (epNum == -1) continue

                val epTitle = epObj.optString("title", "").ifBlank { "Episode $epNum" }
                val thumbnail = epObj.optString("thumbnail", "")

                episodes.add(
                    newEpisode("$mainUrl/watch/$slug?ep=$epNum&ani=$anilistId&mal=$malId") {
                        name = epTitle
                        episode = epNum
                        posterUrl = thumbnail.ifBlank { poster }
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (episodes.isEmpty()) {
            for (a in document.select("a[href*=/watch/]")) {
                val epHref = fixUrlNull(a.attr("href")) ?: continue
                val epNum = Regex("""[?&]ep=(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: a.attr("data-episode").toIntOrNull() ?: 1
                val epName = a.selectFirst("span")?.text() ?: "Episode $epNum"

                episodes.add(
                    newEpisode("$epHref&ani=$anilistId&mal=$malId") {
                        name = epName
                        episode = epNum
                        posterUrl = poster
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.episode })
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
        val slug  = cleanData.substringAfter("/watch/").substringBefore("?")
        val epNum = Regex("""[?&]ep=(\d+)""").find(cleanData)?.groupValues?.get(1) ?: "1"

        val siteHeaders = mapOf(
            "User-Agent" to UA,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest"
        )

        // Step 1: Aggressively harvest all internal API endpoints that contain the streaming URLs
        val endpoints = listOf(
            cleanData,
            "$mainUrl/api/v1/anime/$slug/episodes/$epNum",
            "$mainUrl/api/v1/anime/$slug/episodes/$epNum/servers",
            "$mainUrl/api/v1/episode/$slug-episode-$epNum/servers",
            "$mainUrl/api/v1/watch/$slug?ep=$epNum"
        )

        val candidates = mutableSetOf<String>()
        val serverIds = mutableSetOf<String>()

        for (endpoint in endpoints) {
            try {
                val response = app.get(endpoint, headers = siteHeaders).text
                
                // Pull out any direct URLs (m3u8, mp4, embedded iframes)
                Regex("""https?://[^\s"'<>\\]+""").findAll(response).forEach {
                    candidates.add(it.value.replace("\\/", "/"))
                }
                
                // Pull out any proprietary server_id tokens that require secondary resolution
                Regex("""["'](?:server_id|id)["']\s*:\s*["']([A-Za-z0-9_-]+)["']""").findAll(response).forEach {
                    val id = it.groupValues[1]
                    if (id.length > 4 && !id.contains("episode", true)) serverIds.add(id)
                }
            } catch (e: Exception) {}
        }

        // Step 2: Resolve secondary server tokens
        for (id in serverIds) {
            try {
                val serverRes = app.get("$mainUrl/api/v1/servers/$id/watch", headers = siteHeaders).text
                Regex("""https?://[^\s"'<>\\]+""").findAll(serverRes).forEach {
                    candidates.add(it.value.replace("\\/", "/"))
                }
            } catch (e: Exception) {}
        }

        // Filters to strip out useless UI links
        val exclusions = listOf("jquery", "fonts", "anilist", "thetvdb", "jsdelivr", "w3.org", "png", "jpg", "jpeg", "webp")

        // Step 3: Iterate through harvested candidates and aggressively bypass encryption
        for (rawUrl in candidates) {
            val url = rawUrl.trimEnd('.', ',', '"', '\'')
            if (exclusions.any { url.contains(it, true) }) continue

            val isM3u8 = url.contains(".m3u8")
            val isMp4 = url.contains(".mp4")

            if (isM3u8 || isMp4) {
                val streamName = if (url.contains("flixcloud", true)) "FlixCloud Direct" else "Direct Stream"
                callback(
                    newExtractorLink(
                        source = streamName,
                        name = streamName,
                        url = url,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = siteHeaders
                    }
                )
                found = true
            } else if (url.contains("flixcloud", true)) {
                // DOMAIN SPOOFING BYPASS: FlixCloud recently upgraded to RabbitStream's obfuscation payload logic.
                // We spoof the URL in-memory to Megacloud and Rabbitstream domains so Cloudstream's native internal decryptors crack it automatically.
                val megacloudUrl = url.replace(Regex("""https?://[^/]+"""), "https://megacloud.tv")
                val rabbitUrl = url.replace(Regex("""https?://[^/]+"""), "https://rabbitstream.net")
                val vidplayUrl = url.replace(Regex("""https?://[^/]+"""), "https://vidplay.site")
                
                if (loadExtractor(url, cleanData, subtitleCallback, callback)) found = true
                if (!found && loadExtractor(megacloudUrl, cleanData, subtitleCallback, callback)) found = true
                if (!found && loadExtractor(rabbitUrl, cleanData, subtitleCallback, callback)) found = true
                if (!found && loadExtractor(vidplayUrl, cleanData, subtitleCallback, callback)) found = true
            } else if (KNOWN_HOSTS.any { url.contains(it, true) }) {
                if (loadExtractor(url, cleanData, subtitleCallback, callback)) found = true
            }
        }

        // Step 4: Harvest VTT/SRT subtitle tracks
        try {
            val watchHtml = app.get(cleanData, headers = siteHeaders).text
            Regex("""https?://[^\s"'<>\\]+\.(?:vtt|srt|ass)[^\s"'<>\\]*""").findAll(watchHtml).forEach {
                val subUrl = it.value.replace("\\/", "/")
                if (!subUrl.contains("thumbnail", true) && !subUrl.contains("sprite", true)) {
                    subtitleCallback(newSubtitleFile("English", subUrl))
                }
            }
        } catch (e: Exception) {}

        return found
    }
}
