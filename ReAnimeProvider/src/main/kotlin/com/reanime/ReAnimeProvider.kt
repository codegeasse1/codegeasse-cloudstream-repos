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
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val FLIX = "https://flixcloud.cc"
        // NOTE: the fetch host is sharded (fetch.flixcloud.cc, fetch7.flixcloud.cc,
        // etc.) — confirmed from two different real captures. Never hardcode a
        // single subdomain when assembling a master.m3u8 URL ourselves; only
        // trust a fetch*.flixcloud.cc URL when we find the FULL url already
        // present in a response (page text), since only then do we know which
        // shard actually owns that video id.
        private val FETCH_HOST_REGEX = Regex("""https://fetch\d*\.flixcloud\.cc""")

        /** set to true to surface diagnostic entries in the source list */
        private const val DEBUG = false

        private val UUID_REGEX =
            Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

        private val JWT_REGEX =
            Regex("""eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}""")

        private val M3U8_REGEX =
            Regex("""https?://[^\s"'\\<>()\[\]]+\.m3u8[^\s"'\\<>()\[\]]*""")

        private val MP4_REGEX =
            Regex("""https?://[^\s"'\\<>()\[\]]+\.mp4[^\s"'\\<>()\[\]]*""")

        private val SUB_REGEX =
            Regex("""https?://[^\s"'\\<>()\[\]]+\.(?:vtt|srt|ass)[^\s"'\\<>()\[\]]*""")

        private val EMBED_ID_REGEX =
            Regex("""flixcloud\.cc/(?:embed|e|v|watch|player)/([A-Za-z0-9_-]{8,})""")

        private val KNOWN_HOSTS = listOf(
            "vidhide", "streamwish", "filemoon", "dood", "voe",
            "ok.ru", "vk.com", "mixdrop", "mp4upload", "megaup", "anicore"
        )
    }

    /** unescape the various json/js escapings the site uses */
    private fun String.unesc(): String = this
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")

    // ---------------------------------------------------------------------
    //  MAIN PAGE / SEARCH / LOAD  (unchanged logic, kept intact)
    // ---------------------------------------------------------------------

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
        if (title.isNullOrBlank()) title = document.selectFirst("h1")?.text()?.trim()
            ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

        var poster = Regex("""["']?extra_large["']?\s*:\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
        if (poster.isNullOrBlank()) poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        poster = poster.unesc()

        var plot = Regex("""["']?description["']?\s*:\s*["']((?:[^"'\\]|\\.)*)["']""")
            .find(html)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\u003C", "<")
        plot = if (plot.isNullOrBlank()) {
            document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        } else {
            Jsoup.parse(plot).text()
        }

        var anilistId = Regex("""bx(\d+)""").find(poster)?.groupValues?.get(1)
        if (anilistId.isNullOrBlank()) {
            anilistId = Regex(""""anilist(?:_id)?"\s*:\s*(\d+)""").find(html)?.groupValues?.get(1) ?: ""
        }
        val malId = Regex(""""mal(?:_id)?"\s*:\s*(\d+)""").find(html)?.groupValues?.get(1) ?: ""

        val episodes = mutableListOf<Episode>()

        try {
            val epsRes = app.get(
                "$mainUrl/api/v1/anime/$slug/episodes?limit=2000",
                headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")
            ).text

            val jsonArray = if (epsRes.trim().startsWith("[")) {
                JSONArray(epsRes)
            } else {
                val o = JSONObject(epsRes)
                o.optJSONArray("data") ?: o.optJSONArray("episodes") ?: JSONArray()
            }

            for (i in 0 until jsonArray.length()) {
                val epObj = jsonArray.getJSONObject(i)
                val epNum = epObj.optInt("episode_number", epObj.optInt("number", -1))
                if (epNum == -1) continue

                val epTitle = epObj.optString("title", "").ifBlank { "Episode $epNum" }
                val thumbnail = epObj.optString("thumbnail", "")

                // stash the flixcloud id if the episode list already exposes it
                val inlineId = UUID_REGEX.find(epObj.toString())?.value ?: ""

                episodes.add(
                    newEpisode("$mainUrl/watch/$slug?ep=$epNum&ani=$anilistId&mal=$malId&vid=$inlineId") {
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
                val epNum = Regex("""[?&]ep=(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: a.attr("data-episode").toIntOrNull() ?: 1
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

    // ---------------------------------------------------------------------
    //  LINK RESOLUTION
    // ---------------------------------------------------------------------

    private suspend fun emit(
        callback: (ExtractorLink) -> Unit,
        label: String,
        url: String,
        isM3u8: Boolean,
        referer: String,
        origin: String
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = url,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to UA,
                    "Origin" to origin,
                    "Referer" to referer,
                    "Accept" to "*/*"
                )
            }
        )
    }

    private suspend fun debugNote(callback: (ExtractorLink) -> Unit, msg: String) {
        if (!DEBUG) return
        callback(
            newExtractorLink(
                source = name,
                name = "DEBUG: $msg",
                url = "https://127.0.0.1/none.m3u8",
                type = ExtractorLinkType.M3U8
            ) { this.quality = Qualities.Unknown.value }
        )
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
        val aniId = Regex("""[?&]ani=(\d+)""").find(cleanData)?.groupValues?.get(1) ?: ""
        val malId = Regex("""[?&]mal=(\d+)""").find(cleanData)?.groupValues?.get(1) ?: ""
        val presetVid = Regex("""[?&]vid=([A-Za-z0-9-]+)""").find(cleanData)?.groupValues?.get(1) ?: ""

        val siteHeaders = mapOf(
            "User-Agent" to UA,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest"
        )

        val flixHeaders = mapOf(
            "User-Agent" to UA,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "*/*"
        )

        val seenLinks = hashSetOf<String>()
        val seenSubs  = hashSetOf<String>()
        val videoIds  = linkedSetOf<String>()
        if (presetVid.isNotBlank()) videoIds.add(presetVid)

        // -- helper: push a playable url -----------------------------------
        suspend fun pushStream(rawUrl: String, label: String, fromFlix: Boolean): Boolean {
            val url = rawUrl.unesc().trim().trimEnd(',', ';', ')')
            if (url.isBlank() || url.startsWith("blob:")) return false
            if (!seenLinks.add(url)) return false
            val isM3u8 = url.contains(".m3u8", true)
            emit(
                callback,
                label,
                url,
                isM3u8,
                referer = if (fromFlix) "$FLIX/" else "$mainUrl/",
                origin  = if (fromFlix) FLIX else mainUrl
            )
            return true
        }

        // -- helper: pull subtitles, ignoring the artplayer metadata track --
        fun pushSubs(text: String) {
            SUB_REGEX.findAll(text).forEach { m ->
                val u = m.value.unesc()
                val low = u.lowercase()
                if (low.contains("thumbnail") || low.contains("storyboard") ||
                    low.contains("sprite") || low.contains("preview")) return@forEach
                if (!seenSubs.add(u)) return@forEach
                val langName = when {
                    low.contains("eng") -> "English"
                    low.contains("spa") -> "Spanish"
                    low.contains("por") -> "Portuguese"
                    low.contains("ara") -> "Arabic"
                    low.contains("fre") || low.contains("fra") -> "French"
                    low.contains("ger") || low.contains("deu") -> "German"
                    else -> "Subtitle"
                }
                subtitleCallback(SubtitleFile(langName, u))
            }
        }

        // ==================================================================
        // STEP 1 — gather pages that may contain the flixcloud video id
        // ==================================================================
        val pages = mutableListOf<String>()

        runCatching { pages += app.get(cleanData, headers = siteHeaders).text }
        runCatching { pages += app.get("$mainUrl/watch/$slug?ep=$epNum", headers = siteHeaders).text }

        // >>> ADJUST THESE AFTER CHECKING DEVTOOLS -> Network -> Fetch/XHR <
        val apiCandidates = listOf(
            "$mainUrl/api/v1/anime/$slug/episodes/$epNum/servers",
            "$mainUrl/api/v1/anime/$slug/episodes/$epNum/sources",
            "$mainUrl/api/v1/anime/$slug/episodes/$epNum",
            "$mainUrl/api/v1/watch/$slug?ep=$epNum",
            "$mainUrl/api/v1/episode/$slug-episode-$epNum/servers"
        )
        for (u in apiCandidates) {
            runCatching { pages += app.get(u, headers = siteHeaders).text }
        }

        if (aniId.isNotBlank()) {
            runCatching {
                pages += app.get(
                    "$mainUrl/api/v1/downloads/check?anilist_id=$aniId&mal_id=$malId&episode=$epNum",
                    headers = siteHeaders
                ).text
            }
        }

        runCatching {
            pages += app.get(
                "$mainUrl/api/v1/anime/$slug/episodes?limit=2000",
                headers = siteHeaders
            ).text
        }

        // ==================================================================
        // STEP 2 — scan them
        // ==================================================================
        for (raw in pages) {
            val page = raw.unesc()

            // already-signed flixcloud playlist sitting in the payload —
            // this also naturally handles fetch.flixcloud.cc,
            // fetch7.flixcloud.cc, or any other numbered shard, since we
            // trust whatever full URL is actually present rather than
            // constructing one ourselves.
            M3U8_REGEX.findAll(page).forEach { m ->
                val u = m.value
                val flix = u.contains("flixcloud", true)
                if (pushStream(u, if (flix) "FlixCloud" else "Direct", flix)) found = true
            }

            // plain mp4 fallbacks
            MP4_REGEX.findAll(page).forEach { m ->
                val u = m.value
                if (u.contains("/ads", true)) return@forEach
                val flix = u.contains("flixcloud", true)
                if (pushStream(u, if (flix) "FlixCloud MP4" else "Direct MP4", flix)) found = true
            }

            pushSubs(page)

            // remember every candidate id
            EMBED_ID_REGEX.findAll(page).forEach { videoIds += it.groupValues[1] }
            if (page.contains("flixcloud", true) || page.contains("\"video_id\"")) {
                // Exclude UUIDs that only ever appear inside a blob: URL —
                // those are browser-internal object references (created via
                // URL.createObjectURL()) and can never be fetched
                // server-side, so minting a token against them always fails.
                UUID_REGEX.findAll(page).forEach { match ->
                    val surroundingStart = maxOf(0, match.range.first - 10)
                    val context = page.substring(surroundingStart, match.range.first)
                    if (!context.contains("blob:")) {
                        videoIds += match.value
                    }
                }
            }
            Regex(""""(?:video_id|videoId|file_id|hash|uid)"\s*:\s*"([A-Za-z0-9-]{8,})"""")
                .findAll(page).forEach { videoIds += it.groupValues[1] }

            // third-party mirrors
            Regex("""https?://[^\s"'<>\\]+""").findAll(page).forEach { m ->
                val u = m.value
                if (KNOWN_HOSTS.any { u.contains(it, true) } && seenLinks.add(u)) {
                    runCatching {
                        if (loadExtractor(u, cleanData, subtitleCallback, callback)) found = true
                    }
                }
            }
        }

        if (found) return true

        if (videoIds.isEmpty()) {
            debugNote(callback, "no video id found in ${pages.size} pages")
            return false
        }
        debugNote(callback, "ids=${videoIds.joinToString(",").take(60)}")

        // ==================================================================
        // STEP 3 — ask FlixCloud to mint a token for each candidate id
        // NOTE: this whole step is only reached if Step 2 found nothing
        // usable in any already-fetched page. Since real captures show
        // reanime.to's own backend hands back a ready-made, already-signed
        // fetch*.flixcloud.cc URL (not just a bare video id), this step is
        // likely unreachable in practice once the real endpoint from
        // apiCandidates is confirmed and added above — it exists purely as
        // a last-resort fallback while that endpoint is still unconfirmed.
        // ==================================================================
        for (id in videoIds) {
            // >>> ADJUST THESE TOO IF DEVTOOLS SHOWS A DIFFERENT PATH <
            val flixEndpoints = listOf(
                "$FLIX/embed/$id",
                "$FLIX/e/$id",
                "$FLIX/api/v1/videos/$id",
                "$FLIX/api/v1/videos/$id/stream",
                "$FLIX/api/v1/video/$id/token",
                "$FLIX/api/video/$id"
            )

            for (ep in flixEndpoints) {
                val body = runCatching { app.get(ep, headers = flixHeaders).text }.getOrNull()
                    ?: continue
                val page = body.unesc()

                // a) a complete signed url is in the response
                M3U8_REGEX.findAll(page).forEach { m ->
                    if (pushStream(m.value, "FlixCloud", true)) found = true
                }

                // b) only the JWT is present -> we don't know which fetch*
                // shard owns this id, so we can't safely assemble the url
                // ourselves anymore (confirmed the host varies: fetch vs
                // fetch7, likely more). Without a full URL, skip rather
                // than guess a shard that will 404.
                pushSubs(page)

                // c) the embed page may point at yet another script holding
                // a complete signed url or the token
                if (!found) {
                    Regex("""<script[^>]+src=["']([^"']+)["']""").findAll(page)
                        .map { it.groupValues[1] }
                        .filter { it.contains("player") || it.contains("main") || it.contains("chunk") }
                        .take(3)
                        .forEach { s ->
                            val js = runCatching {
                                app.get(
                                    if (s.startsWith("http")) s else "$FLIX${if (s.startsWith("/")) "" else "/"}$s",
                                    headers = flixHeaders
                                ).text
                            }.getOrNull()?.unesc() ?: return@forEach

                            M3U8_REGEX.findAll(js).forEach { m ->
                                if (pushStream(m.value, "FlixCloud", true)) found = true
                            }
                        }
                }

                if (found) break
            }
            if (found) break
        }

        if (!found) debugNote(callback, "ids ok but no token returned")
        return found
    }
}
