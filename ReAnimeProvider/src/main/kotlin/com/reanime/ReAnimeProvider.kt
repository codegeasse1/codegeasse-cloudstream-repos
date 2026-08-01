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

        /** turn on to get diagnostic entries in the source list */
        private const val DEBUG = true

        private val UUID_REGEX =
            Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
        private val M3U8_REGEX =
            Regex("""https?://[^\s"'\\<>()\[\]]+\.m3u8[^\s"'\\<>()\[\]]*""")
        private val FLIX_URL_REGEX =
            Regex("""https?://[\w.-]*flixcloud\.cc/[^\s"'\\<>()\[\]]*""")
        private val SUB_REGEX =
            Regex("""https?://[^\s"'\\<>()\[\]]+\.(?:vtt|srt|ass)[^\s"'\\<>()\[\]]*""")
    }

    private fun String.unesc(): String = this
        .replace("\\/", "/")
        .replace("\\u002F", "/").replace("\\u002f", "/")
        .replace("\\u0026", "&").replace("&amp;", "&")

    // =====================================================================
    //  MAIN PAGE / SEARCH / LOAD
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
    //  LINKS
    // =====================================================================

    // NOTE: must be `suspend` — newExtractorLink is a suspend function
    private suspend fun note(callback: (ExtractorLink) -> Unit, msg: String) {
        if (!DEBUG) return
        callback(
            newExtractorLink(
                source = name,
                name = "DBG ${msg.take(90)}",
                url = "https://127.0.0.1/none.m3u8",
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
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
        val clean = data.substringBefore("#")
        val slug  = clean.substringAfter("/watch/").substringBefore("?")
        val epNum = Regex("""[?&]ep=(\d+)""").find(clean)?.groupValues?.get(1) ?: "1"
        val watchUrl = "$mainUrl/watch/$slug?ep=$epNum"

        val seen = hashSetOf<String>()

        // NOTE: must be `suspend` — newExtractorLink is a suspend function
        suspend fun push(rawUrl: String, label: String): Boolean {
            val u = rawUrl.unesc().trim().trimEnd(',', ';', ')', '"', '\'')
            if (u.isBlank() || u.startsWith("blob:") || !seen.add(u)) return false
            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = u,
                    type = if (u.contains(".m3u8", true))
                        ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$FLIX/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to UA,
                        "Origin"     to FLIX,
                        "Referer"    to "$FLIX/",
                        "Accept"     to "*/*"
                    )
                }
            )
            return true
        }

        fun pushSubs(text: String) {
            SUB_REGEX.findAll(text).forEach {
                val u = it.value.unesc(); val l = u.lowercase()
                if (l.contains("thumbnail") || l.contains("storyboard") ||
                    l.contains("sprite") || l.contains("preview")) return@forEach
                if (seen.add("sub:$u")) subtitleCallback(SubtitleFile("English", u))
            }
        }

        // -----------------------------------------------------------------
        // PASS 1 : cheap scrape (works only if the site ever inlines the url)
        // -----------------------------------------------------------------
        val embeds = linkedSetOf<String>()
        val pageHtml = runCatching {
            app.get(watchUrl, headers = mapOf(
                "User-Agent" to UA, "Referer" to "$mainUrl/"
            )).text.unesc()
        }.getOrNull() ?: ""

        if (pageHtml.isNotEmpty()) {
            M3U8_REGEX.findAll(pageHtml).forEach { if (push(it.value, "FlixCloud")) found = true }
            pushSubs(pageHtml)
            FLIX_URL_REGEX.findAll(pageHtml).forEach { m ->
                val u = m.value
                if (!u.contains(".m3u8") && !u.contains(".css") &&
                    !u.contains(".js") && !u.contains(".png") && !u.contains(".jpg")) embeds += u
            }
            Jsoup.parse(pageHtml).select("iframe").forEach { f ->
                val s = f.attr("src").ifBlank { f.attr("data-src") }
                if (s.contains("flixcloud")) embeds += fixUrl(s)
            }
        }
        if (found) return true

        // -----------------------------------------------------------------
        // PASS 2 : WebView on the watch page — let the real player load
        // -----------------------------------------------------------------
        val playScript = """
            (function(){
              var n = 0;
              var t = setInterval(function(){
                if (++n > 20) { clearInterval(t); return; }
                document.querySelectorAll('video').forEach(function(v){
                  try { v.muted = true; v.play(); } catch(e){}
                });
                var b = document.querySelector(
                  '.art-state, .art-poster, .play-button, .vjs-big-play-button, [class*=play]');
                if (b) { try { b.click(); } catch(e){} }
              }, 700);
            })();
        """.trimIndent()

        suspend fun webviewGrab(target: String, label: String): Boolean {
            val resolver = runCatching {
                WebViewResolver(
                    interceptUrl = Regex("""\.m3u8"""),
                    additionalUrls = listOf(Regex("""\.m3u8""")),
                    useOkhttp = false,
                    script = playScript,
                    timeout = 25_000L
                )
            }.getOrNull() ?: return false

            val res = runCatching {
                app.get(target, headers = mapOf("User-Agent" to UA),
                    referer = "$mainUrl/", interceptor = resolver)
            }.getOrNull() ?: return false

            val hit = res.url
            if (hit.contains(".m3u8", true)) return push(hit, label)
            return false
        }

        if (webviewGrab(watchUrl, "FlixCloud")) return true
        note(callback, "webview(watch) got nothing; embeds=${embeds.size}")

        // -----------------------------------------------------------------
        // PASS 3 : WebView directly on the flixcloud iframe
        // -----------------------------------------------------------------
        for (e in embeds.take(4)) {
            note(callback, "embed=$e")
            if (webviewGrab(e, "FlixCloud")) return true
        }

        // -----------------------------------------------------------------
        // PASS 4 : last resort — build embed url from a bare uuid
        // -----------------------------------------------------------------
        val ids = linkedSetOf<String>()
        Regex("""[?&]vid=([A-Za-z0-9-]{8,})""").find(clean)?.let { ids += it.groupValues[1] }
        if (pageHtml.contains("flixcloud", true))
            UUID_REGEX.findAll(pageHtml).forEach { ids += it.value }

        for (id in ids.take(3)) {
            for (p in listOf("$FLIX/embed/$id", "$FLIX/e/$id", "$FLIX/v/$id")) {
                if (webviewGrab(p, "FlixCloud")) return true
            }
        }

        note(callback, "FAILED ids=${ids.joinToString(",").take(70)}")
        return found
    }
}
