package com.aniwaves

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject

class AniwavesProvider : MainAPI() {
    override var mainUrl = "https://aniwaves.ru"
    override var name = "Aniwaves"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Home",
        "$mainUrl/newest" to "Newest",
        "$mainUrl/added" to "Recently Added",
        "$mainUrl/completed" to "Completed",
    )

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to ua,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "X-Requested-With" to "XMLHttpRequest"
    )

    class PendingLink(val url: String, val referer: String, val tag: String, val headers: Map<String, String>, val m3u8: Boolean)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get(request.data, headers = headers).text
        val document = Jsoup.parse(html)
        val homeItems = mutableListOf<HomePageList>()

        if (request.name == "Home") {
            val sliderItems = document.select(".swiper-wrapper .swiper-slide.item").mapNotNull { parseSliderItem(it) }
            if (sliderItems.isNotEmpty()) {
                homeItems.add(HomePageList("Trending Now", sliderItems, isHorizontalImages = true))
            }
        }

        val gridElements = document.select(".ani.items .item, .top-table .item")
        val standardItems = gridElements.mapNotNull { parseStandardItem(it) }

        if (standardItems.isNotEmpty()) {
            homeItems.add(HomePageList(request.name, standardItems))
        }

        return newHomePageResponse(homeItems)
    }

    private fun parseSliderItem(element: Element): SearchResponse? {
        val title = element.selectFirst(".title")?.text()?.trim() ?: return null
        val url = element.selectFirst(".actions a.play")?.attr("href") ?: return null
        val styleAttr = element.selectFirst(".image div")?.attr("style") ?: ""
        val poster = Regex("""url\(['"]?(.*?)['"]?\)""").find(styleAttr)?.groupValues?.get(1) ?: ""

        return newAnimeSearchResponse(title, "$mainUrl$url", TvType.Anime) {
            this.posterUrl = poster
        }
    }

    private fun parseStandardItem(element: Element): SearchResponse? {
        val titleElement = element.selectFirst(".name.d-title") ?: return null
        val title = titleElement.text().trim()
        val url = titleElement.attr("href") ?: return null
        var poster = element.selectFirst("img")?.attr("src") ?: ""
        if (poster.isBlank() || poster.contains("data:image")) {
            poster = element.selectFirst("img")?.attr("data-src") ?: ""
        }

        val subEps = element.selectFirst(".ep-status.sub span")?.text()?.trim()?.toIntOrNull()
        val dubEps = element.selectFirst(".ep-status.dub span")?.text()?.trim()?.toIntOrNull()

        return newAnimeSearchResponse(title, "$mainUrl$url", TvType.Anime) {
            this.posterUrl = poster
            addSub(subEps)
            addDub(dubEps)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/filter?keyword=$query"
        val document = Jsoup.parse(app.get(searchUrl, headers = headers).text)
        return document.select(".ani.items .item").mapNotNull { parseStandardItem(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = headers).text
        val document = Jsoup.parse(html)

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""

        val animeId = url.substringAfterLast("-")
        val episodes = mutableListOf<Episode>()

        try {
            val epHtml = app.get("$mainUrl/ajax/episode/list/$animeId?vrf=", headers = headers).text
            val cleanEpHtml = if (epHtml.trim().startsWith("{")) JSONObject(epHtml).optString("result", epHtml) else epHtml
            val epDoc = Jsoup.parse(cleanEpHtml)

            for (el in epDoc.select("a")) {
                val rawNum = el.attr("data-num").ifBlank { el.attr("data-ep") }.ifBlank { el.text() }
                val epNum = Regex("""\d+""").find(rawNum)?.value?.toIntOrNull() ?: continue

                var epName = el.attr("title").ifBlank { el.text() }.trim()
                if (epName.isBlank() || epName.matches(Regex("""^\d+$"""))) {
                    epName = "Episode $epNum"
                }

                episodes.add(
                    newEpisode("$mainUrl/watch?animeId=$animeId&epNum=$epNum") {
                        this.name = epName
                        this.episode = epNum
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
        val animeId = Regex("""animeId=([^&]+)""").find(data)?.groupValues?.get(1) ?: return false
        val epNum = Regex("""epNum=([^&]+)""").find(data)?.groupValues?.get(1) ?: "1"

        val pendingLinks = mutableListOf<PendingLink>()

        fun originOf(url: String): String = Regex("""https?://[^/]+""").find(url)?.value ?: mainUrl

        val mediaUrlRegex = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:[^\s"'<>\\]*)?""")
        val idRegex = Regex("""(?:/e/|/embed-\d+/|/v/|[?&]id=)([\w-]+)""")

        fun isJunk(url: String): Boolean =
            url.contains("placeholder") || url.contains("/ads/") || url.contains("static.") ||
            url.contains("trailer") || url.contains("poster") || url.contains("preview")

        suspend fun emitRaw(p: PendingLink) {
            callback(
                newExtractorLink(
                    source = name,
                    name = p.tag,
                    url = p.url,
                    type = if (p.m3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = p.referer
                    this.headers = p.headers.ifEmpty { mapOf("Referer" to p.referer, "User-Agent" to ua) }
                }
            )
        }

        suspend fun validateLink(url: String, referer: String, hdrs: Map<String, String>, m3u8: Boolean): Boolean {
            return try {
                val h = hdrs.toMutableMap()
                if (!h.containsKey("User-Agent")) h["User-Agent"] = ua
                if (m3u8) {
                    val r = app.get(url, headers = h)
                    r.text.startsWith("#EXTM3U") || url.contains(".m3u8", true)
                } else {
                    app.head(url, headers = h).isSuccessful
                }
            } catch (e: Exception) {
                false
            }
        }

        suspend fun tryLink(url: String, referer: String, tag: String, hdrs: Map<String, String>, m3u8: Boolean): Boolean {
            val h = hdrs.toMutableMap()
            if (!h.containsKey("Referer")) h["Referer"] = referer
            if (validateLink(url, referer, h, m3u8)) {
                emitRaw(PendingLink(url, referer, tag, h, m3u8))
                return true
            }
            if (!isJunk(url)) pendingLinks.add(PendingLink(url, referer, tag, h, m3u8))
            return false
        }

        suspend fun extractingLoadExtractor(url: String, tag: String): Boolean {
            val intercepted = mutableListOf<ExtractorLink>()
            var ok = false
            try {
                ok = loadExtractor(url, data, subtitleCallback) { l -> intercepted.add(l) }
            } catch (e: Exception) {
                ok = false
            }
            if (!ok || intercepted.isEmpty()) return false

            var forwarded = false
            for (l in intercepted) {
                val m3u8 = l.type == ExtractorLinkType.M3U8 || l.url.contains("m3u8", true)
                val h = l.headers.toMutableMap()
                if (l.referer.isNotBlank()) h["Referer"] = l.referer
                if (validateLink(l.url, l.referer.ifBlank { url }, h, m3u8)) {
                    callback(l)
                    forwarded = true
                } else {
                    pendingLinks.add(PendingLink(l.url, l.referer.ifBlank { url }, tag, h, m3u8))
                }
            }
            return forwarded
        }

        suspend fun parseSubtitles(json: JSONObject) {
            val tracks = json.optJSONArray("tracks") ?: json.optJSONArray("subtitles") ?: return
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                val file = t.optString("file", "").replace("\\/", "/")
                if (file.isNotBlank()) {
                    subtitleCallback(newSubtitleFile(t.optString("label", "English"), file))
                }
            }
        }

        fun parseSourcesJson(json: JSONObject, embedUrl: String, tag: String): Boolean {
            return false // placeholder, real logic below in suspend fun
        }

        suspend fun handleSourcesJson(res: String, embedUrl: String, tag: String): Boolean {
            if (!res.trim().startsWith("{")) return false
            val j = JSONObject(res)
            val streamUrl = when (val src = j.opt("sources")) {
                is String -> src
                is JSONArray -> {
                    var r = ""
                    for (i in 0 until src.length()) {
                        val f = src.optJSONObject(i)?.optString("file") ?: ""
                        if (f.isNotBlank()) { r = f; break }
                    }
                    r
                }
                else -> ""
            }.replace("\\/", "/")

            if (streamUrl.startsWith("http")) {
                if (tryLink(streamUrl, embedUrl, tag, mapOf("Referer" to embedUrl), streamUrl.contains("m3u8", true))) return true
            }
            parseSubtitles(j)
            return false
        }

        // Probe MegaCloud/EchoVideo-style getSources APIs on ANY embed host
        suspend fun probeGetSources(embedUrl: String, tag: String): Boolean {
            val origin = originOf(embedUrl)
            val id = idRegex.find(embedUrl)?.groupValues?.get(1)
                ?: embedUrl.substringBefore("?").substringAfterLast("/")
            if (id.isBlank()) return false

            val paths = listOf(
                "/ajax/embed-4/getSources", "/embed-4/getSources",
                "/ajax/embed-1/getSources", "/embed-1/getSources",
                "/ajax/getSources", "/getSources"
            )
            for (p in paths) {
                try {
                    val res = app.get(
                        "$origin$p?id=$id",
                        headers = mapOf("User-Agent" to ua, "Referer" to embedUrl, "X-Requested-With" to "XMLHttpRequest")
                    ).text
                    if (handleSourcesJson(res, embedUrl, tag)) return true
                } catch (e: Exception) { }
            }
            return false
        }

        // Try MegaCloud/Vidplay extractors with this id, regardless of the embed host
        suspend fun tryKnownRewrites(embedUrl: String, tag: String): Boolean {
            val id = idRegex.find(embedUrl)?.groupValues?.get(1) ?: return false
            for (alt in listOf(
                "https://megacloud.tv/embed-2/e-1/$id",
                "https://megacloud.tv/embed-1/e-1/$id",
                "https://vidplay.site/e/$id"
            )) {
                if (extractingLoadExtractor(alt, tag)) return true
            }
            return false
        }

        suspend fun resolveEmbed(embedUrl: String, tag: String): Boolean {
            if (embedUrl.contains("echovideo")) {
                try {
                    val echoId = embedUrl.substringBefore("?").substringAfterLast("/")
                    val domain = originOf(embedUrl)
                    val embedSegment = Regex("""/(embed-\d+)/""").find(embedUrl)?.groupValues?.get(1) ?: "embed-1"
                    val echoRes = app.get(
                        "$domain/$embedSegment/getSources?id=$echoId",
                        headers = mapOf("User-Agent" to ua, "Referer" to embedUrl, "X-Requested-With" to "XMLHttpRequest")
                    ).text
                    if (handleSourcesJson(echoRes, embedUrl, tag)) return true
                } catch (e: Exception) { }
            }

            if (extractingLoadExtractor(embedUrl, tag)) return true
            if (probeGetSources(embedUrl, tag)) return true
            if (tryKnownRewrites(embedUrl, tag)) return true

            try {
                val embedHtml = app.get(
                    embedUrl,
                    headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to ua)
                ).text.replace("\\/", "/")

                val candidates = mutableListOf<String>()
                for (m in mediaUrlRegex.findAll(embedHtml)) {
                    candidates.add(m.value)
                }

                if (embedHtml.contains("eval(function(p,a,c,k,e,d)")) {
                    try {
                        val unpacked = getAndUnpack(embedHtml)
                        for (m in mediaUrlRegex.findAll(unpacked)) {
                            candidates.add(m.value)
                        }
                    } catch (e: Exception) { }
                }

                for (b64 in Regex("""["']([A-Za-z0-9+/=]{60,})["']""").findAll(embedHtml)) {
                    try {
                        val decoded = String(android.util.Base64.decode(b64.groupValues[1], android.util.Base64.DEFAULT))
                        for (m in mediaUrlRegex.findAll(decoded)) {
                            candidates.add(m.value)
                        }
                    } catch (e: Exception) { }
                }

                for (c in candidates.distinct()) {
                    if (isJunk(c)) continue
                    if (tryLink(c, embedUrl, tag, mapOf("Referer" to embedUrl), c.contains("m3u8", true))) return true
                }

                val iframeSrc = Jsoup.parse(embedHtml).selectFirst("iframe")?.attr("src") ?: ""
                if (iframeSrc.isNotBlank()) {
                    val cleanIframe = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                    if (cleanIframe != embedUrl) {
                        if (extractingLoadExtractor(cleanIframe, tag)) return true
                    }
                }
            } catch (e: Exception) { }

            return false
        }

        try {
            val serverUrl = "$mainUrl/ajax/server/list?servers=$animeId&eps=$epNum"
            val serverRes = app.get(serverUrl, headers = headers).text

            val serverJson = try { JSONObject(serverRes) } catch (e: Exception) { null }
            val serverHtml = serverJson?.optString("result")?.ifBlank { serverJson.optString("html") } ?: serverRes
            val serverDoc = Jsoup.parse(serverHtml)

            // Collect ids AND their visible labels (Vidplay / BYFMS / DGHG)
            val serverIds = mutableListOf<String>()
            val serverLabels = mutableMapOf<String, String>()
            for (el in serverDoc.select("li, a, div, span")) {
                val id = el.attr("data-link").ifBlank { el.attr("data-id") }.ifBlank { el.attr("data-server") }
                if (id.isNotBlank() && !serverIds.contains(id)) {
                    serverIds.add(id)
                    serverLabels[id] = el.text().trim()
                }
            }
            if (serverIds.isEmpty()) {
                for (m in Regex("""data-(?:link-)?id=["']([^"'>\s]+)["']""").findAll(serverHtml)) {
                    val v = m.groupValues[1].trim()
                    if (v.isNotBlank() && !serverIds.contains(v)) serverIds.add(v)
                }
            }

            for (id in serverIds) {
                val tag = serverLabels[id]?.takeIf { it.isNotBlank() } ?: "Aniwaves Server ${serverIds.indexOf(id) + 1}"
                val apiEndpoints = listOf(
                    "$mainUrl/ajax/episode/sources?id=$id",
                    "$mainUrl/ajax/sources?id=$id&asi=0&autoPlay=0",
                    "$mainUrl/ajax/server?get=$id"
                )

                var serverFound = false
                for (apiUrl in apiEndpoints) {
                    if (serverFound) break
                    try {
                        val sourceRes = app.get(apiUrl, headers = headers).text
                        if (!sourceRes.trim().startsWith("{")) continue

                        val json = JSONObject(sourceRes)
                        val result = json.optJSONObject("result") ?: json
                        var embedUrl = result.optString("url").ifBlank { result.optString("link") }.replace("\\/", "/")
                        if (embedUrl.startsWith("//")) embedUrl = "https:$embedUrl"
                        if (embedUrl.isBlank() || !embedUrl.startsWith("http")) continue

                        if (resolveEmbed(embedUrl, tag)) {
                            serverFound = true
                            found = true
                        }
                    } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (!found && pendingLinks.isNotEmpty()) {
            for (p in pendingLinks.distinctBy { it.url }) {
                emitRaw(p)
            }
            found = pendingLinks.isNotEmpty()
        }

        return found
    }
}