package com.reanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
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

        /** true = show a red diagnostic dialog instead of silently failing */
        private const val DEBUG = true

        private val UUID_REGEX =
            Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
        private val M3U8_REGEX =
            Regex("""https?://[^\s"'\\<>()\[\]]+\.m3u8[^\s"'\\<>()\[\]]*""")
        private val SUB_REGEX =
            Regex("""https?://[^\s"'\\<>()\[\]]+\.(?:vtt|srt|ass)[^\s"'\\<>()\[\]]*""")
    }

    private fun String.unesc(): String = this
        .replace("\\/", "/")
        .replace("\\u002F", "/").replace("\\u002f", "/")
        .replace("\\u0026", "&").replace("&amp;", "&")

    // =====================================================================
    //  MAIN PAGE / SEARCH / LOAD   (unchanged - these already work)
    // =====================================================================

    private fun extractSectionArray(html: String, key: String): List<SearchResponse> {
        val match = Regex("""["']?$key["']?\s*:\s*\[""").find(html) ?: return emptyList()
        val startIdx = html.indexOf('[', match.range.first)
        if (startIdx == -1) return emptyList()

        var depth = 0
        var endIdx = -1
        for (i in startIdx until html.length) {
            if (html[i] == '[') depth++ else if (html[i] == ']') depth--
            if (depth == 0) { endIdx = i + 1; break }
        }
        if (endIdx == -1) return emptyList()

        val items = mutableListOf<SearchResponse>()
        val parts = html.substring(startIdx, endIdx)
            .split(Regex("""["']?anime_id["']?\s*:\s*["']"""))

        for (i in 1 until parts.size) {
            val animeId = parts[i].substringBefore("\"").substringBefore("'")
            if (animeId.isBlank() || animeId.length > 200) continue
            val w = parts[i].take(2000).unesc()

            var title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(w)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?user_preferred["']?\s*:\s*["']([^"']+)""").find(w)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?romaji["']?\s*:\s*["']([^"']+)""").find(w)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = animeId.replace("-", " ").replaceFirstChar { it.uppercase() }

            var poster = Regex("""["']?extra_large["']?\s*:\s*["']([^"']+)""").find(w)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""["']?large["']?\s*:\s*["']([^"']+)""").find(w)?.groupValues?.get(1) ?: ""

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
        val lists = mutableListOf<HomePageList>()
        listOf(
            "latest_aired" to "Latest Episodes",
            "new_on_site" to "New on Site",
            "trending" to "Trending",
            "upcoming" to "Upcoming"
        ).forEach { (k, t) ->
            val items = extractSectionArray(html, k)
            if (items.isNotEmpty()) lists.add(HomePageList(t, items))
        }
        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.get("$mainUrl/search?q=$query", headers = mapOf("User-Agent" to UA)).text
        val items = mutableListOf<SearchResponse>()
        val parts = html.split(Regex("""["']?anime_id["']?\s*:\s*["']"""))
        for (i in 1 until parts.size) {
            val animeId = parts[i].substringBefore("\"").substringBefore("'")
            if (animeId.isBlank() || animeId.length > 200) continue
            val w = parts[i].take(2000).unesc()

            var title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(w)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?user_preferred["']?\s*:\s*["']([^"']+)""").find(w)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?romaji["']?\s*:\s*["']([^"']+)""").find(w)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = animeId.replace("-", " ").replaceFirstChar { it.uppercase() }

            var poster = Regex("""["']?extra_large["']?\s*:\s*["']([^"']+)""").find(w)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""["']?large["']?\s*:\s*["']([^"']+)""").find(w)?.groupValues?.get(1) ?: ""

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
        val doc = Jsoup.parse(html)
        val slug = url.substringAfter("/anime/").substringAfter("/watch/").substringBefore("?")

        var title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
        if (title.isNullOrBlank()) title = Regex("""["']?user_preferred["']?\s*:\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
        if (title.isNullOrBlank()) title = doc.selectFirst("h1")?.text()?.trim()
            ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

        var poster = Regex("""["']?extra_large["']?\s*:\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
        if (poster.isNullOrBlank()) poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        poster = poster.unesc()

        var plot = Regex("""["']?description["']?\s*:\s*["']((?:[^"'\\]|\\.)*)["']""")
            .find(html)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\u003C", "<")
        plot = if (plot.isNullOrBlank())
            doc.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        else Jsoup.parse(plot).text()

        var anilistId = Regex("""bx(\d+)""").find(poster)?.groupValues?.get(1)
        if (anilistId.isNullOrBlank())
            anilistId = Regex(""""anilist(?:_id)?"\s*:\s*(\d+)""").find(html)?.groupValues?.get(1) ?: ""
        val malId = Regex(""""mal(?:_id)?"\s*:\s*(\d+)""").find(html)?.groupValues?.get(1) ?: ""

        val episodes = mutableListOf<Episode>()
        try {
            val epsRes = app.get(
                "$mainUrl/api/v1/anime/$slug/episodes?limit=2000",
                headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")
            ).text
            val arr = if (epsRes.trim().startsWith("[")) JSONArray(epsRes) else {
                val o = JSONObject(epsRes)
                o.optJSONArray("data") ?: o.optJSONArray("episodes") ?: JSONArray()
            }
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val n = e.optInt("episode_number", e.optInt("number", -1))
                if (n == -1) continue
                val thumb = e.optString("thumbnail", "")
                val inlineId = UUID_REGEX.find(e.toString())?.value ?: ""
                episodes.add(
                    newEpisode("$mainUrl/watch/$slug?ep=$n&ani=$anilistId&mal=$malId&vid=$inlineId") {
                        name = e.optString("title", "").ifBlank { "Episode $n" }
                        episode = n
                        posterUrl = thumb.ifBlank { poster }
                    }
                )
            }
        } catch (e: Exception) { e.printStackTrace() }

        if (episodes.isEmpty()) {
            for (a in doc.select("a[href*=/watch/]")) {
                val href = fixUrlNull(a.attr("href")) ?: continue
                val n = Regex("""[?&]ep=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                episodes.add(newEpisode("$href&ani=$anilistId&mal=$malId") {
                    name = a.selectFirst("span")?.text() ?: "Episode $n"
                    episode = n
                    posterUrl = poster
                })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.episode })
        }
    }

    // =====================================================================
    //  LINKS  +  ON-SCREEN DIAGNOSTICS
    // =====================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rep = StringBuilder()
        fun log(s: String) { rep.append(s).append('\n') }

        var found = false
        val clean = data.substringBefore("#")
        val slug  = clean.substringAfter("/watch/").substringBefore("?")
        val epNum = Regex("""[?&]ep=(\d+)""").find(clean)?.groupValues?.get(1) ?: "1"
        val watchUrl = "$mainUrl/watch/$slug?ep=$epNum"
        val seen = hashSetOf<String>()

        suspend fun push(rawUrl: String, label: String): Boolean {
            val u = rawUrl.unesc().trim().trimEnd(',', ';', ')', '"', '\'')
            if (u.isBlank() || u.startsWith("blob:") || !seen.add(u)) return false

            val combos = listOf(
                mapOf("User-Agent" to UA, "Referer" to "$FLIX/", "Origin" to FLIX),
                mapOf("User-Agent" to UA, "Referer" to "$mainUrl/", "Origin" to mainUrl),
                mapOf("User-Agent" to UA)
            )
            for (h in combos) {
                val body = runCatching { app.get(u, headers = h).text }.getOrNull() ?: continue
                if (!body.trimStart().startsWith("#EXTM3U")) continue
                callback(
                    newExtractorLink(name, label, u, ExtractorLinkType.M3U8) {
                        this.referer = h["Referer"] ?: ""
                        this.quality = Qualities.Unknown.value
                        this.headers = h
                    }
                )
                return true
            }
            log("REJECTED ${u.take(70)}")
            return false
        }

        // ---------- A. the episodes API : dump the real field names ----------
        var epJsonKeys = "-"
        var epJsonSample = "-"
        runCatching {
            val txt = app.get(
                "$mainUrl/api/v1/anime/$slug/episodes?limit=2000",
                headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")
            ).text
            val arr = if (txt.trimStart().startsWith("[")) JSONArray(txt) else {
                val o = JSONObject(txt)
                o.optJSONArray("data") ?: o.optJSONArray("episodes") ?: JSONArray()
            }
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optInt("episode_number", o.optInt("number", -1)).toString() == epNum) {
                    epJsonKeys = o.keys().asSequence().joinToString(",")
                    epJsonSample = o.toString().take(300)
                    break
                }
            }
            if (epJsonKeys == "-" && arr.length() > 0) {
                val o = arr.getJSONObject(0)
                epJsonKeys = o.keys().asSequence().joinToString(",")
                epJsonSample = o.toString().take(300)
            }
        }
        log("EPKEYS: $epJsonKeys")

        // ---------- B. the watch page ----------
        val pageHtml = runCatching {
            app.get(watchUrl, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/"))
                .text.unesc()
        }.getOrNull() ?: ""
        log("HTML len=${pageHtml.length} flix=${pageHtml.contains("flixcloud")} " +
            "m3u8=${pageHtml.contains(".m3u8")} next=${pageHtml.contains("__NEXT_DATA__")}")

        M3U8_REGEX.findAll(pageHtml).forEach { if (push(it.value, "FlixCloud")) found = true }
        SUB_REGEX.findAll(pageHtml).forEach {
            val l = it.value.lowercase()
            if (!l.contains("thumbnail") && !l.contains("storyboard") && !l.contains("sprite"))
                subtitleCallback(SubtitleFile("English", it.value))
        }
        if (found) return true

        val iframes = Jsoup.parse(pageHtml).select("iframe")
            .map { it.attr("src").ifBlank { it.attr("data-src") } }
            .filter { it.isNotBlank() }
        log("IFRAMES: ${iframes.joinToString(" | ").take(200).ifBlank { "none" }}")

        val ids = linkedSetOf<String>()
        Regex("""[?&]vid=([A-Za-z0-9-]{8,})""").find(clean)?.let { ids += it.groupValues[1] }
        UUID_REGEX.findAll(pageHtml).take(6).forEach { ids += it.value }
        log("UUIDS: ${ids.joinToString(",").take(160).ifBlank { "none" }}")

        // ---------- C. probe candidate API endpoints ----------
        val probes = listOf(
            "$mainUrl/api/v1/anime/$slug/episodes/$epNum",
            "$mainUrl/api/v1/anime/$slug/episodes/$epNum/servers",
            "$mainUrl/api/v1/anime/$slug/episodes/$epNum/sources",
            "$mainUrl/api/v1/anime/$slug/episodes/$epNum/stream",
            "$mainUrl/api/v1/watch/$slug?ep=$epNum",
            "$mainUrl/api/v1/stream/$slug/$epNum"
        )
        for (p in probes) {
            val r = runCatching {
                app.get(p, headers = mapOf(
                    "User-Agent" to UA, "Referer" to watchUrl,
                    "Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest"))
            }.getOrNull()
            if (r == null) { log("P ${p.removePrefix(mainUrl)} ERR"); continue }
            val body = r.text.unesc()
            log("P ${p.removePrefix(mainUrl)} ${r.code} ${body.take(70).replace("\n", " ")}")
            if (r.code == 200) {
                M3U8_REGEX.findAll(body).forEach { if (push(it.value, "FlixCloud")) found = true }
                UUID_REGEX.findAll(body).take(3).forEach { ids += it.value }
            }
            if (found) return true
        }

        // ---------- D. one WebView attempt ----------
        val js = """
            (function(){var n=0;var t=setInterval(function(){
              if(++n>25){clearInterval(t);return;}
              var v=document.getElementsByTagName('video');
              for(var i=0;i<v.length;i++){try{v[i].muted=true;v[i].play();}catch(e){}}
              var s=['.art-poster','.art-state','.vjs-big-play-button','[class*=play]','button'];
              for(var k=0;k<s.length;k++){var b=document.querySelector(s[k]);
                if(b){try{b.click();}catch(e){}}}
            },600);})();
        """.trimIndent()

        suspend fun grab(target: String, pat: Regex): String? {
            val r = runCatching {
                WebViewResolver(interceptUrl = pat, additionalUrls = listOf(pat),
                    useOkhttp = false, script = js, timeout = 30_000L)
            }.getOrNull() ?: return null
            return runCatching {
                app.get(target, headers = mapOf("User-Agent" to UA),
                    referer = "$mainUrl/", interceptor = r).url
            }.getOrNull()
        }

        val wv = grab(watchUrl, Regex("""\.m3u8|/_v7/"""))
        log("WV1: ${wv?.take(110) ?: "null"}")
        if (wv != null && (wv.contains(".m3u8") || wv.contains("/_v7/"))) {
            if (push(wv, "FlixCloud")) return true
        }

        val wv2 = grab(watchUrl, Regex("""flixcloud\.cc"""))
        log("WV2: ${wv2?.take(110) ?: "null"}")

        if (found) return true

        if (DEBUG) throw ErrorLoadingException(rep.toString().take(1400))
        return false
    }
}
