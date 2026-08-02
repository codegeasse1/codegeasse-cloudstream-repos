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
        .replace("\\u003A", ":")
        .replace("\\u003a", ":")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("\\\"", "\"")

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

            val itemUrl = "$mainUrl/anime/$animeId"
            if (items.none { it.url == itemUrl }) {
                items.add(newAnimeSearchResponse(title, itemUrl, TvType.Anime) {
                    this.posterUrl = poster.unesc()
                })
            }
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = mapOf("User-Agent" to UA)).text
        val document = Jsoup.parse(html)
        val slug = url.substringAfter("/anime/").substringAfter("/watch/").substringBefore("?").substringBefore("/")

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

                val epDataUrl = "$mainUrl/watch/$slug?ep=$epNum&ani=$anilistId&mal=$malId"
                episodes.add(
                    newEpisode(epDataUrl) {
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

                val fullEpHref = fixUrl(epHref)
                val epDataUrl = if (fullEpHref.contains("?")) "$fullEpHref&ani=$anilistId&mal=$malId" else "$fullEpHref?ep=$epNum&ani=$anilistId&mal=$malId"

                episodes.add(
                    newEpisode(epDataUrl) {
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
        val slug = cleanData.substringAfter("/watch/").substringAfter("/anime/").substringBefore("?").substringBefore("/")
        val epNum = Regex("""[?&]ep=(\d+)""").find(cleanData)?.groupValues?.get(1)
            ?: Regex("""/watch/[^/]+/(\d+)""").find(cleanData)?.groupValues?.get(1)
            ?: "1"

        val siteHeaders = mapOf(
            "User-Agent" to UA,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest"
        )

        // Exhaustive collection of all potential API endpoints holding the source
        val endpoints = listOf(
            cleanData,
            "$mainUrl/api/v1/servers/$slug/$epNum",
            "$mainUrl/servers/$slug/$epNum",
            "$mainUrl/api/v1/anime/$slug/episodes/$epNum/servers",
            "$mainUrl/api/v1/servers?slug=$slug&ep=$epNum",
            "$mainUrl/api/v1/episode/$slug-episode-$epNum/servers",
            "$mainUrl/api/v1/episode/$slug/$epNum/servers",
            "$mainUrl/watch/$slug?ep=$epNum",
            "$mainUrl/api/v1/watch/$slug?ep=$epNum"
        )

        val pages = mutableListOf<String>()
        val serverIds = mutableSetOf<String>()

        for (ep in endpoints) {
            try {
                val res = app.get(ep, headers = siteHeaders).text
                if (res.isNotBlank()) {
                    val unescaped = res.unesc()
                    pages.add(unescaped)
                    
                    Regex("""["'](?:server_id|id)["']\s*:\s*["']([A-Za-z0-9_-]+)["']""").findAll(unescaped).forEach { m ->
                        val id = m.groupValues[1]
                        if (id.length > 4 && !id.contains("episode", true)) serverIds.add(id)
                    }
                }
            } catch (e: Exception) {}
        }

        for (id in serverIds) {
            val serverEndpoints = listOf(
                "$mainUrl/api/v1/servers/$id/watch",
                "$mainUrl/api/v1/watch?server_id=$id",
                "$mainUrl/api/v1/server/$id"
            )
            for (sep in serverEndpoints) {
                try {
                    val res = app.get(sep, headers = siteHeaders).text
                    if (res.isNotBlank()) pages.add(res.unesc())
                } catch (e: Exception) {}
            }
        }

        val extractedUrls = mutableSetOf<String>()

        for (page in pages) {
            // THE ULTIMATE REGEX: Extracts M3U8 and MP4 links purely, completely ignoring any JSON/HTML syntax.
            // This guarantees no trailing commas or quotes cause the "bad http" crash.
            val mediaRegex = Regex("""https?://[^"'\s\\]+?\.(?:m3u8|mp4)(?:[?&][^"'\s\\]*)?""")
            mediaRegex.findAll(page).forEach { m ->
                var rawUrl = m.value
                
                // Surgical Sanitization: Rip out any trailing artifacts immediately
                rawUrl = rawUrl.substringBefore("\"").substringBefore("'").substringBefore("\\")
                rawUrl = rawUrl.substringBefore("<").substringBefore(">")
                rawUrl = rawUrl.substringBefore(",").substringBefore("}").substringBefore("]")
                
                if (rawUrl.isNotBlank()) {
                    extractedUrls.add(rawUrl)
                }
            }

            // Extract Subtitles
            Regex("""https?://[^"'\s\\]+?\.(?:vtt|srt|ass)(?:[?&][^"'\s\\]*)?""").findAll(page).forEach { m ->
                var subUrl = m.value
                subUrl = subUrl.substringBefore("\"").substringBefore("'").substringBefore("\\").substringBefore(",").substringBefore("}")
                
                val low = subUrl.lowercase()
                if (!low.contains("thumbnail") && !low.contains("sprite") && !low.contains("preview")) {
                    val lang = when {
                        low.contains("eng") -> "English"
                        low.contains("spa") -> "Spanish"
                        low.contains("fre") || low.contains("fra") -> "French"
                        low.contains("ger") || low.contains("deu") -> "German"
                        else -> "Subtitle"
                    }
                    subtitleCallback(newSubtitleFile(lang, subUrl))
                }
            }
        }

        val seenLinks = mutableSetOf<String>()

        for (u in extractedUrls) {
            if (!seenLinks.add(u)) continue

            val isFlix = u.contains("flixcloud", true) || u.contains("fetch", true)

            // Direct Playlists (M3U8)
            if (u.contains(".m3u8", ignoreCase = true)) {
                val streamName = if (isFlix) "FlixCloud Server" else "Re:Anime Direct"
                
                // FLIXCLOUD FIREWALL BYPASS: Force the Origin and Referer exactly to FlixCloud.
                // ReAnime's referer will cause FlixCloud's CDN to instantly throw a 400 Bad Request.
                val streamHeaders = if (isFlix) {
                    mapOf(
                        "Origin" to "https://flixcloud.cc",
                        "Referer" to "https://flixcloud.cc/",
                        "User-Agent" to UA,
                        "Accept" to "*/*"
                    )
                } else {
                    mapOf(
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/",
                        "User-Agent" to UA,
                        "Accept" to "*/*"
                    )
                }

                callback(
                    newExtractorLink(
                        source = streamName,
                        name = streamName,
                        url = u,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = streamHeaders
                    }
                )
                found = true
                continue
            }

            // Direct Video Files (MP4)
            if (u.contains(".mp4", ignoreCase = true) && !u.contains("/ads", true)) {
                callback(
                    newExtractorLink(
                        source = "Re:Anime MP4",
                        name = "Direct MP4",
                        url = u,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = siteHeaders
                    }
                )
                found = true
                continue
            }

            // Third-Party Mirrors (Vidhide, Streamwish, Filemoon, etc.)
            if (KNOWN_HOSTS.any { u.contains(it, true) }) {
                try {
                    if (loadExtractor(u, cleanData, subtitleCallback, callback)) {
                        found = true
                    }
                } catch (e: Exception) {}
            }
        }

        return found
    }
}
