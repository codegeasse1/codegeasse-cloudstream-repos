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

        /** true = prepend a diagnostic report to the anime description */
        private const val DEBUG = true

        private val UUID_REGEX =
            Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
        private val M3U8_REGEX =
            Regex("""https?://[^\s"'\\<>()\[\]]+\.m3u8[^\s"'\\<>()\[\]]*""")
        private val SUB_REGEX =
            Regex("""https?://[^\s"'\\<>()\[\]]+\.(?:vtt|srt|ass)[^\s"'\\<>()\[\]]*""")

        // finds "/api/..." string literals inside JS bundles
        private val API_PATH_REGEX = Regex("[\"'`](/api/[A-Za-z0-9_./-]+)")
    }

    private fun String.unesc(): String = this
        .replace("\\/", "/")
        .replace("\\u002F", "/").replace("\\u002f", "/")
        .replace("\\u0026", "&").replace("&amp;", "&")

    // =====================================================================
    //  SHARED : discover api paths from the site's own JS
    // =====================================================================

    private suspend fun jsBundles(pageHtml: String): List<String> {
        val srcs = Jsoup.parse(pageHtml).select("script[src]")
            .map { it.attr("src") }
            .map { if (it.startsWith("http")) it else mainUrl + (if (it.startsWith("/")) "" else "/") + it }
            .distinct()
        return srcs.take(8)
    }

    private suspend fun discoverApi(pageHtml: String): Pair<Set<String>, List<String>> {
        val paths = linkedSetOf<String>()
        val flixHits = mutableListOf<String>()
        for (u in jsBundles(pageHtml)) {
            val js = runCatching {
                app.get(u, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")).text
            }.getOrNull() ?: continue
            API_PATH_REGEX.findAll(js).forEach { paths += it.groupValues[1] }
            var i = js.indexOf("flixcloud")
            var n = 0
            while (i >= 0 && n < 4) {
                flixHits += js.substring(maxOf(0, i - 45), minOf(js.length, i + 55))
                    .replace("\n", " ")
                i = js.indexOf("flixcloud", i + 1); n++
            }
        }
        return paths to flixHits
    }

    // =====================================================================
    //  MAIN PAGE / SEARCH
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

    // =====================================================================
    //  DIAGNOSTIC REPORT  (shown in the description)
    // =====================================================================

    private suspend fun buildReport(slug: String, epNum: String): String {
        val r = StringBuilder()
        fun l(s: String) { r.append(s).append("\n") }
        l("===== ReAnime DEBUG =====")
        l("slug=$slug ep=$epNum")

        // episode json keys
        runCatching {
            val txt = app.get("$mainUrl/api/v1/anime/$slug/episodes?limit=50",
                headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")).text
            val arr = if (txt.trimStart().startsWith("[")) JSONArray(txt) else {
                val o = JSONObject(txt)
                o.optJSONArray("data") ?: o.optJSONArray("episodes") ?: JSONArray()
            }
            if (arr.length() > 0) {
                val o = arr.getJSONObject(0)
                l("EPKEYS: " + o.keys().asSequence().joinToString(","))
                l("EPJSON: " + o.toString().take(260))
            } else l("EPKEYS: empty array")
        }.onFailure { l("EPKEYS: ERR ${it.message?.take(50)}") }

        // watch page
        val watchUrl = "$mainUrl/watch/$slug?ep=$epNum"
        val html = runCatching {
            app.get(watchUrl, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/"))
                .text.unesc()
        }.getOrNull() ?: ""
        l("HTML len=${html.length} flix=${html.contains("flixcloud")} m3u8=${html.contains(".m3u8")}")

        val ifr = Jsoup.parse(html).select("iframe")
            .map { it.attr("src").ifBlank { it.attr("data-src") } }.filter { it.isNotBlank() }
        l("IFRAMES: " + ifr.joinToString(" | ").take(180).ifBlank { "none" })

        val uu = UUID_REGEX.findAll(html).map { it.value }.distinct().take(4).toList()
        l("UUIDS: " + uu.joinToString(",").ifBlank { "none" })

        // js bundle mining
        val (paths, flixHits) = discoverApi(html)
        l("SCRIPTS: " + jsBundles(html).size)
        val interesting = paths.filter {
            listOf("episode", "watch", "stream", "source", "server", "video", "player", "anime")
                .any { k -> it.contains(k, true) }
        }
        l("APIPATHS(" + paths.size + "): " + interesting.joinToString(" ").take(500))
        if (flixHits.isNotEmpty()) l("FLIXJS: " + flixHits.joinToString(" ~~ ").take(400))

        // probe discovered + guessed endpoints
        val cand = linkedSetOf<String>()
        interesting.forEach { p ->
            val b = mainUrl + p.trimEnd('/')
            cand += "$b/$slug"
            cand += "$b/$slug/$epNum"
            cand += "$b/$slug/episodes/$epNum"
        }
        cand += "$mainUrl/api/v1/anime/$slug/episodes/$epNum"
        cand += "$mainUrl/api/v1/anime/$slug/episodes/$epNum/servers"
        cand += "$mainUrl/api/v1/watch/$slug?ep=$epNum"

        var probed = 0
        for (c in cand) {
            if (probed++ >= 14) break
            val res = runCatching {
                app.get(c, headers = mapOf("User-Agent" to UA, "Referer" to watchUrl,
                    "Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest"))
            }.getOrNull()
            if (res == null) { l("P ${c.removePrefix(mainUrl)} ERR"); continue }
            if (res.code == 404) continue
            l("P ${c.removePrefix(mainUrl)} ${res.code} ${res.text.take(90).replace("\n"," ")}")
        }
        l("===== END DEBUG =====")
        return r.toString()
    }

    // =====================================================================
    //  LOAD
    // =====================================================================

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

        // ---- DEBUG REPORT INTO THE DESCRIPTION ----
        val finalPlot = if (DEBUG) {
            val ep1 = episodes.minByOrNull { it.episode ?: 1 }?.episode?.toString() ?: "1"
            val rep = runCatching { buildReport(slug, ep1) }
                .getOrElse { "REPORT CRASHED: ${it::class.simpleName} ${it.message}" }
            rep + "\n\n" + plot
        } else plot

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = finalPlot
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.episode })
        }
    }

    // =====================================================================
    //  LINKS
    // =====================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val clean = data.substringBefore("#")
        val slug  = clean.substringAfter("/watch/").substringBefore("?")
        val epNum = Regex("""[?&]ep=(\d+)""").find(clean)?.groupValues?.get(1) ?: "1"
        val watchUrl = "$mainUrl/watch/$slug?ep=$epNum"
        val seen = hashSetOf<String>()

        suspend fun push(rawUrl: String): Boolean {
            val u = rawUrl.unesc().trim().trimEnd(',', ';', ')', '"', '\'')
            if (u.isBlank() || u.startsWith("blob:") || !seen.add(u)) return false
            val combos = listOf(
                mapOf("User-Agent" to UA, "Referer" to "$FLIX/", "Origin" to FLIX),
                mapOf("User-Agent" to UA, "Referer" to "$mainUrl/", "Origin" to mainUrl),
                mapOf("User-Agent" to UA)
            )
            for (h in combos) {
                val b = runCatching { app.get(u, headers = h).text }.getOrNull() ?: continue
                if (!b.trimStart().startsWith("#EXTM3U")) continue
                callback(newExtractorLink(name, "FlixCloud", u, ExtractorLinkType.M3U8) {
                    this.referer = h["Referer"] ?: ""
                    this.quality = Qualities.Unknown.value
                    this.headers = h
                })
                return true
            }
            return false
        }

        val pageHtml = runCatching {
            app.get(watchUrl, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/"))
                .text.unesc()
        }.getOrNull() ?: ""

        M3U8_REGEX.findAll(pageHtml).forEach { if (push(it.value)) found = true }
        SUB_REGEX.findAll(pageHtml).forEach {
            val l = it.value.lowercase()
            if (!l.contains("thumbnail") && !l.contains("storyboard") && !l.contains("sprite"))
                subtitleCallback(SubtitleFile("English", it.value))
        }
        if (found) return true

        // try endpoints mined from the site's own JS
        val (paths, _) = discoverApi(pageHtml)
        val cand = linkedSetOf<String>()
        paths.filter {
            listOf("episode", "watch", "stream", "source", "server", "video").any { k -> it.contains(k, true) }
        }.forEach { p ->
            val b = mainUrl + p.trimEnd('/')
            cand += "$b/$slug/$epNum"; cand += "$b/$slug/episodes/$epNum"; cand += "$b/$slug"
        }
        var n = 0
        for (c in cand) {
            if (n++ >= 12) break
            val body = runCatching {
                app.get(c, headers = mapOf("User-Agent" to UA, "Referer" to watchUrl,
                    "Accept" to "application/json")).text.unesc()
            }.getOrNull() ?: continue
            M3U8_REGEX.findAll(body).forEach { if (push(it.value)) found = true }
            if (found) return true
        }

        // webview fallback
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
        val hit = runCatching {
            val pat = Regex("""\.m3u8|/_v7/""")
            app.get(watchUrl, headers = mapOf("User-Agent" to UA), referer = "$mainUrl/",
                interceptor = WebViewResolver(interceptUrl = pat, additionalUrls = listOf(pat),
                    useOkhttp = false, script = js, timeout = 30_000L)).url
        }.getOrNull()
        if (hit != null && (hit.contains(".m3u8") || hit.contains("/_v7/"))) {
            if (push(hit)) return true
        }

        return found
    }
}
