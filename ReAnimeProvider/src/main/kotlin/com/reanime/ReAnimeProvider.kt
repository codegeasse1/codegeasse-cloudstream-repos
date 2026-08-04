package com.reanime

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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

        /**
         * PASTE YOUR REANIME.TO TOKEN HERE.
         * Browser (logged in) -> DevTools Console ->
         * copy(JSON.parse(localStorage.getItem('project_r_auth')).token)
         * Without a token the site returns no servers (can_watch=false).
         */
        private const val BEARER_TOKEN = ""

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

        private val FETCH_HOST_REGEX =
            Regex("""https?://fetch\d*\.flixcloud\.cc""")

        private val KNOWN_HOSTS = listOf(
            "vidhide", "streamwish", "filemoon", "dood", "voe",
            "ok.ru", "vk.com", "mixdrop", "mp4upload", "megaup", "anicore"
        )
    }

    private fun bearerToken(): String? =
        BEARER_TOKEN.takeIf { it.isNotBlank() } ?: storedCredentials?.takeIf { it.isNotBlank() }

    private fun String.unesc(): String = this
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")

    private fun decryptFlixCloudResponse(encryptedBase64: String, keyBase64: String, ivBase64: String): String {
        val encrypted = Base64.decode(encryptedBase64, Base64.DEFAULT)
        val key = Base64.decode(keyBase64, Base64.DEFAULT)
        val iv = Base64.decode(ivBase64, Base64.DEFAULT)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }

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
        // Use the site's own search API when possible
        try {
            val res = app.get("$mainUrl/api/v1/search?limit=30&q=${query}", headers = mapOf("User-Agent" to UA)).text
            val json = JSONObject(res)
            val arr = json.optJSONArray("results") ?: json.optJSONArray("hits") ?: JSONArray()
            if (arr.length() > 0) {
                val items = mutableListOf<SearchResponse>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val slug = o.optString("anime_id")
                    if (slug.isBlank()) continue
                    val t = o.optJSONObject("title")
                    val title = t?.optString("english")?.takeIf { it.isNotBlank() }
                        ?: t?.optString("romaji")?.takeIf { it.isNotBlank() } ?: slug
                    val poster = o.optJSONObject("cover_image")?.optString("large") ?: ""
                    items.add(newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                        this.posterUrl = poster
                    })
                }
                if (items.isNotEmpty()) return items
            }
        } catch (e: Exception) { }

        val html = app.get("$mainUrl/search?q=$query", headers = mapOf("User-Agent" to UA)).text
        return extractSectionArray(html, "results")
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
        val presetVid = Regex("""[?&]vid=([A-Za-z0-9-]+)""").find(cleanData)?.groupValues?.get(1) ?: ""

        val token = bearerToken()

        // Authenticated headers (the site only serves video servers to logged-in users)
        val siteHeaders = mutableMapOf(
            "User-Agent" to UA,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest"
        )
        if (token != null) siteHeaders["Authorization"] = "Bearer $token"

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

        suspend fun pushSubs(text: String) {
            for (m in SUB_REGEX.findAll(text)) {
                val u = m.value.unesc()
                val low = u.lowercase()
                if (low.contains("thumbnail") || low.contains("storyboard") ||
                    low.contains("sprite") || low.contains("preview")) continue
                if (!seenSubs.add(u)) continue
                val langName = when {
                    low.contains("eng") -> "English"
                    low.contains("spa") -> "Spanish"
                    low.contains("por") -> "Portuguese"
                    low.contains("ara") -> "Arabic"
                    low.contains("fre") || low.contains("fra") -> "French"
                    low.contains("ger") || low.contains("deu") -> "German"
                    else -> "Subtitle"
                }
                subtitleCallback(newSubtitleFile(langName, u))
            }
        }

        val pages = mutableListOf<String>()
        var canWatch = token != null // assume ok when logged in

        // 1) The authenticated servers endpoint (401 without token)
        try { pages += app.get("$mainUrl/api/v1/anime/$slug/episodes/$epNum/servers", headers = siteHeaders).text } catch (e: Exception) { }
        try { pages += app.get("$mainUrl/api/v1/anime/$slug/episodes/$epNum/sources", headers = siteHeaders).text } catch (e: Exception) { }

        // 2) Watch sync endpoint (returns can_watch + server info when logged in)
        try {
            val res = app.get("$mainUrl/api/v1/watch/$slug?ep=$epNum&sync=true", headers = siteHeaders).text
            pages += res
            val j = JSONObject(res)
            val animeObj = j.optJSONObject("anime")
            if (animeObj != null) canWatch = animeObj.optBoolean("can_watch", canWatch)
        } catch (e: Exception) { }

        try { pages += app.get(cleanData, headers = siteHeaders).text } catch (e: Exception) { }
        try { pages += app.get("$mainUrl/watch/$slug?ep=$epNum", headers = siteHeaders).text } catch (e: Exception) { }

        for (raw in pages) {
            val page = raw.unesc()

            for (m in M3U8_REGEX.findAll(page)) {
                val u = m.value
                if (pushStream(u, if (u.contains("flixcloud", true)) "FlixCloud" else "Direct", u.contains("flixcloud", true))) found = true
            }

            for (m in MP4_REGEX.findAll(page)) {
                val u = m.value
                if (u.contains("/ads", true)) continue
                if (pushStream(u, if (u.contains("flixcloud", true)) "FlixCloud MP4" else "Direct MP4", u.contains("flixcloud", true))) found = true
            }

            pushSubs(page)

            for (m in EMBED_ID_REGEX.findAll(page)) { videoIds += m.groupValues[1] }
            if (page.contains("flixcloud", true) || page.contains("\"video_id\"") || page.contains("\"server\"")) {
                for (m in UUID_REGEX.findAll(page)) { videoIds += m.value }
            }

            val idRegex = Regex(""""(?:video_id|videoId|file_id|hash|uid|embed_id)"\s*:\s*"([A-Za-z0-9-]{8,})"""")
            for (m in idRegex.findAll(page)) { videoIds += m.groupValues[1] }

            val urlRegex = Regex("""https?://[^\s"'<>\\]+""")
            for (m in urlRegex.findAll(page)) {
                val u = m.value
                if (KNOWN_HOSTS.any { u.contains(it, true) } && seenLinks.add(u)) {
                    try {
                        if (loadExtractor(u, cleanData, subtitleCallback, callback)) found = true
                    } catch (e: Exception) { }
                }
            }
        }

        if (found) return true

        if (videoIds.isEmpty()) {
            // Clear diagnostic instead of silent "No Links Found"
            if (token == null && !canWatch) {
                emit(callback, "LOGIN REQUIRED: paste token in BEARER_TOKEN", "https://127.0.0.1/none.m3u8", true, mainUrl, mainUrl)
            }
            return false
        }

        // 3) Resolve FlixCloud ids
        for (id in videoIds) {
            val embedUrl = "$FLIX/embed/$id"
            val html = try { app.get(embedUrl, headers = flixHeaders).text } catch (e: Exception) { null } ?: continue

            val keyB64 = Regex("""["']kf_\w+["']\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            val ivB64 = Regex("""["']ivf_\w+["']\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

            if (keyB64 != null && ivB64 != null) {
                val encryptedRes = try { app.get("$FLIX/api/m3u8/$id", headers = flixHeaders).text } catch (e: Exception) { null }

                if (encryptedRes != null && encryptedRes.trim().startsWith("{")) {
                    val json = JSONObject(encryptedRes)
                    var encryptedData = ""

                    for (k in json.keys()) {
                        val v = json.optString(k)
                        if (v.length > encryptedData.length) encryptedData = v
                    }

                    if (encryptedData.isNotBlank()) {
                        try {
                            val decryptedJson = JSONObject(decryptFlixCloudResponse(encryptedData, keyB64, ivB64))

                            if (decryptedJson.optBoolean("success", false)) {
                                val sources = decryptedJson.optJSONArray("sources")
                                if (sources != null) {
                                    for (i in 0 until sources.length()) {
                                        val src = sources.getJSONObject(i)
                                        val url = src.optString("file", "")
                                        if (url.isNotBlank() && (url.contains(".m3u8", true) || src.optString("type", "").lowercase() == "hls")) {
                                            if (pushStream(url, "FlixCloud", true)) found = true
                                        }
                                    }
                                }
                                val tracks = decryptedJson.optJSONArray("tracks")
                                if (tracks != null) {
                                    for (i in 0 until tracks.length()) {
                                        val track = tracks.getJSONObject(i)
                                        val subUrl = track.optString("file", "")
                                        if (subUrl.isNotBlank()) subtitleCallback(newSubtitleFile(track.optString("label", "Subtitle"), subUrl))
                                    }
                                }
                            }
                        } catch (e: Exception) { }
                    }
                }
            }

            val page = html.unesc()

            for (m in M3U8_REGEX.findAll(page)) {
                if (pushStream(m.value, "FlixCloud", true)) found = true
            }

            if (!found) {
                val tok = JWT_REGEX.find(page)?.value
                if (tok != null) {
                    val fetchHost = FETCH_HOST_REGEX.find(page)?.value ?: "https://fetch7.flixcloud.cc"
                    if (pushStream("$fetchHost/_v7/$id/master.m3u8?token=$tok", "FlixCloud Master", true)) found = true
                    if (!found && pushStream("$fetchHost/_v7/$id/video.m3u8?token=$tok", "FlixCloud Video", true)) found = true
                    if (!found && pushStream("$fetchHost/_v7/$id/audio/audio.m3u8?token=$tok", "FlixCloud Audio", true)) found = true
                }
            }

            pushSubs(page)

            if (!found) {
                val scriptRegex = Regex("""<script[^>]+src=["']([^"']+)["']""")
                val scripts = mutableListOf<String>()
                for (m in scriptRegex.findAll(page)) {
                    val s = m.groupValues[1]
                    if (s.contains("player") || s.contains("main") || s.contains("chunk") || s.contains("_v7") || s.contains("token")) scripts.add(s)
                }

                for (s in scripts.take(3)) {
                    val js = try {
                        app.get(if (s.startsWith("http")) s else "$FLIX${if (s.startsWith("/")) "" else "/"}$s", headers = flixHeaders).text
                    } catch (e: Exception) { null }?.unesc() ?: continue

                    val tok = JWT_REGEX.find(js)?.value
                    if (tok != null) {
                        val fetchHost = FETCH_HOST_REGEX.find(js)?.value ?: "https://fetch7.flixcloud.cc"
                        if (pushStream("$fetchHost/_v7/$id/master.m3u8?token=$tok", "FlixCloud Master", true)) found = true
                        if (!found && pushStream("$fetchHost/_v7/$id/video.m3u8?token=$tok", "FlixCloud Video", true)) found = true
                    }
                    if (found) break
                }
            }

            if (found) break
        }

        return found
    }
}