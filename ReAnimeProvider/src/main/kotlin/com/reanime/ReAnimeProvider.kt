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

        private val UUID_REGEX =
            Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

        private val JWT_REGEX =
            Regex("""eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}""")

        private val M3U8_REGEX =
            Regex("""https?://[^\s"'\\<>()\[\]]+\.m3u8[^\s"'\\<>()\[\]]*""")

        private val SUB_REGEX =
            Regex("""https?://[^\s"'\\<>()\[\]]+\.(?:vtt|srt|ass)[^\s"'\\<>()\[\]]*""")

        private val EMBED_ID_REGEX =
            Regex("""flixcloud\.cc/(?:embed|e|v|watch|player)/([A-Za-z0-9_-]{6,})""")

        private val FETCH_HOST_REGEX =
            Regex("""https?://fetch\d*\.flixcloud\.cc""")
    }

    private fun String.unesc(): String = this
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")

    private fun decryptFlix(encryptedBase64: String, keyBase64: String, ivBase64: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(Base64.decode(keyBase64, Base64.DEFAULT), "AES"),
            IvParameterSpec(Base64.decode(ivBase64, Base64.DEFAULT))
        )
        return String(cipher.doFinal(Base64.decode(encryptedBase64, Base64.DEFAULT)), Charsets.UTF_8)
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
            if (bracketCount == 0) { endIdx = i + 1; break }
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
        try {
            val res = app.get("$mainUrl/api/v1/search?limit=30&q=$query", headers = mapOf("User-Agent" to UA)).text
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
        if (title.isNullOrBlank()) title = document.selectFirst("h1")?.text()?.trim()
            ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

        var poster = Regex("""["']?extra_large["']?\s*:\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
        if (poster.isNullOrBlank()) poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        poster = poster.unesc()

        val plot = Jsoup.parse(
            Regex("""["']?description["']?\s*:\s*["']((?:[^"'\\]|\\.)*)["']""")
                .find(html)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\u003C", "<") ?: ""
        ).text().ifBlank { document.selectFirst("meta[property=og:description]")?.attr("content") ?: "" }

        val episodes = mutableListOf<Episode>()
        try {
            val epsRes = app.get(
                "$mainUrl/api/v1/anime/$slug/episodes?limit=2000",
                headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")
            ).text

            val jsonArray = if (epsRes.trim().startsWith("[")) JSONArray(epsRes)
            else JSONObject(epsRes).optJSONArray("data") ?: JSONArray()

            for (i in 0 until jsonArray.length()) {
                val epObj = jsonArray.getJSONObject(i)
                val epNum = epObj.optInt("episode_number", epObj.optInt("number", -1))
                if (epNum == -1) continue

                episodes.add(
                    newEpisode("$mainUrl/watch/$slug?ep=$epNum") {
                        name = epObj.optString("title", "").ifBlank { "Episode $epNum" }
                        episode = epNum
                        posterUrl = epObj.optString("thumbnail", "").ifBlank { poster }
                    }
                )
            }
        } catch (e: Exception) { }

        if (episodes.isEmpty()) {
            for (a in document.select("a[href*=/watch/]")) {
                val epHref = fixUrlNull(a.attr("href")) ?: continue
                val epNum = Regex("""[?&]ep=(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                episodes.add(newEpisode(epHref) { name = "Episode $epNum"; episode = epNum; posterUrl = poster })
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
        val slug = cleanData.substringAfter("/watch/").substringBefore("?")
        val epNum = Regex("""[?&]ep=(\d+)""").find(cleanData)?.groupValues?.get(1) ?: "1"
        val wantDub = cleanData.contains("lang=dub")

        val apiHeaders = mapOf(
            "User-Agent" to UA,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "application/json, text/plain, */*"
        )

        val seenLinks = hashSetOf<String>()

        suspend fun push(url: String, label: String, isM3u8: Boolean, referer: String): Boolean {
            val u = url.unesc().trim().trimEnd(',', ';', ')')
            if (u.isBlank() || u.startsWith("blob:") || !seenLinks.add(u)) return false
            callback(
                newExtractorLink(source = name, name = label, url = u,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to UA, "Referer" to referer, "Origin" to FLIX, "Accept" to "*/*")
                }
            )
            return true
        }

        // ---------------------------------------------------------------
        // 1) Get AniList / TMDB ids (public watch API, no auth needed)
        // ---------------------------------------------------------------
        var anilistId = ""
        var tmdbId = ""
        try {
            val watchRes = app.get(
                "$mainUrl/api/v1/watch/$slug?ep=$epNum&tz=UTC",
                headers = apiHeaders
            ).text
            anilistId = Regex("""/bx(\d+)-""").find(watchRes)?.groupValues?.get(1) ?: ""
            tmdbId = Regex(""""themoviedb_id"\s*:\s*(\d+)""").find(watchRes)?.groupValues?.get(1) ?: ""
        } catch (e: Exception) { }

        if (anilistId.isBlank()) {
            anilistId = Regex("""/bx(\d+)-""").find(
                app.get(cleanData, headers = apiHeaders).text
            )?.groupValues?.get(1) ?: ""
        }

        // ---------------------------------------------------------------
        // 2) Public server list endpoint (works WITHOUT login)
        // ---------------------------------------------------------------
        val flixApiUrl = if (anilistId.isNotBlank())
            "$mainUrl/api/flix/$anilistId/$epNum"
        else
            "$mainUrl/api/flix/0/$epNum?tmdb=$tmdbId&season=1"

        val servers = mutableListOf<Pair<String, String>>() // name -> dataLink
        try {
            val res = app.get(flixApiUrl, headers = apiHeaders).text.unesc()
            val arr = if (res.trim().startsWith("[")) JSONArray(res.trim())
            else JSONObject(res).optJSONArray("servers")

            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val link = o.optString("dataLink", "")
                    val type = o.optString("dataType", "sub")
                    val name = o.optString("serverName", "HD")
                    if (link.isBlank()) continue
                    if (wantDub && !type.contains("dub")) continue
                    if (!wantDub && type.contains("dub")) continue
                    servers.add(name to link)
                }
                // fallback: take everything if filter emptied the list
                if (servers.isEmpty()) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val link = o.optString("dataLink", "")
                        if (link.isNotBlank()) servers.add(o.optString("serverName", "HD") to link)
                    }
                }
            }
        } catch (e: Exception) { }

        // ---------------------------------------------------------------
        // 3) Resolve each FlixCloud embed
        // ---------------------------------------------------------------
        for ((serverName, rawLink) in servers) {
            var link = rawLink
            if (wantDub) link = if (link.contains("?")) "$link&a=1" else "$link?a=1"

            // a) let installed extractors try first
            try {
                if (loadExtractor(link, cleanData, subtitleCallback, callback)) { found = true; continue }
            } catch (e: Exception) { }

            val embedId = EMBED_ID_REGEX.find(link)?.groupValues?.get(1) ?: continue

            // b) fetch the embed page and scan it
            val page = try {
                app.get(link, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/", "Accept" to "*/*")).text
            } catch (e: Exception) { "" }.unesc()

            for (m in M3U8_REGEX.findAll(page)) {
                if (push(m.value, serverName, true, "$FLIX/")) found = true
            }

            val jwt = JWT_REGEX.find(page)?.value
            if (!found && jwt != null) {
                val host = FETCH_HOST_REGEX.find(page)?.value ?: "https://fetch7.flixcloud.cc"
                val vid = UUID_REGEX.find(page)?.value ?: embedId
                if (push("$host/_v7/$vid/master.m3u8?token=$jwt", serverName, true, "$FLIX/")) found = true
                if (!found && push("$host/_v7/$vid/video.m3u8?token=$jwt", serverName, true, "$FLIX/")) found = true
            }

            // c) m3u8 API (token handshake)
            if (!found) {
                for (m3u8Url in listOf("$FLIX/api/m3u8/$embedId", "$FLIX/api/m3u8/$embedId?v=2")) {
                    try {
                        val r = app.get(m3u8Url, headers = mapOf(
                            "User-Agent" to UA, "Referer" to link, "X-Requested-With" to "XMLHttpRequest"
                        )).text
                        if (r.trim().startsWith("{")) {
                            val j = JSONObject(r)
                            val src = j.optJSONArray("sources")
                            if (src != null) {
                                for (i in 0 until src.length()) {
                                    val u = src.getJSONObject(i).optString("file", "")
                                    if (u.contains(".m3u8")) { if (push(u, serverName, true, "$FLIX/")) found = true }
                                }
                            }
                            val single = j.optString("url", "").ifBlank { j.optString("source", "") }
                            if (!found && single.contains(".m3u8")) {
                                if (push(single, serverName, true, "$FLIX/")) found = true
                            }
                        }
                    } catch (e: Exception) { }
                }
            }

            // d) AES-encrypted sources fallback
            if (!found) {
                val keyB64 = Regex("""["']kf_\w+["']\s*:\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
                val ivB64 = Regex("""["']ivf_\w+["']\s*:\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
                if (keyB64 != null && ivB64 != null) {
                    try {
                        val r = app.get("$FLIX/api/m3u8/$embedId", headers = mapOf(
                            "User-Agent" to UA, "Referer" to link
                        )).text
                        if (r.trim().startsWith("{")) {
                            val j = JSONObject(r)
                            var blob = ""
                            for (k in j.keys()) {
                                val v = j.optString(k)
                                if (v.length > blob.length) blob = v
                            }
                            if (blob.isNotBlank()) {
                                val dec = JSONObject(decryptFlix(blob, keyB64, ivB64))
                                val src = dec.optJSONArray("sources")
                                if (src != null) {
                                    for (i in 0 until src.length()) {
                                        val u = src.getJSONObject(i).optString("file", "")
                                        if (u.isNotBlank()) { if (push(u, serverName, u.contains(".m3u8"), "$FLIX/")) found = true }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) { }
                }
            }

            // e) subtitles from embed page
            for (m in SUB_REGEX.findAll(page)) {
                subtitleCallback(newSubtitleFile("Subtitles", m.value.unesc()))
            }

            if (found) break
        }

        return found
    }
}