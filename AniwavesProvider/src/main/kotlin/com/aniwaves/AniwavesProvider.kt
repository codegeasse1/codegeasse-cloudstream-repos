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

        fun originOf(url: String): String = Regex("""https?://[^/]+""").find(url)?.value ?: mainUrl

        val mediaUrlRegex = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:[^\s"'<>\\]*)?""")

        fun isJunk(url: String): Boolean =
            url.contains("placeholder") || url.contains("/ads/") || url.contains("static.") ||
            url.contains("trailer") || url.contains("poster") || url.contains("preview")

        // Last-resort emitter: guarantees a link is always shown to the user
        fun addUnvalidated(url: String, referer: String, tag: String): Boolean {
            val isHls = url.contains("m3u8", true)
            callback(
                newExtractorLink(
                    source = name,
                    name = tag,
                    url = url,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = referer
                    this.headers = mapOf("Referer" to referer, "User-Agent" to ua)
                }
            )
            return true
        }

        suspend fun addValidatedM3u8(url: String, referer: String, tag: String): Boolean {
            return try {
                val res = app.get(url, headers = mapOf("Referer" to referer, "User-Agent" to ua))
                val good = res.text.startsWith("#EXTM3U") || url.contains(".m3u8", true)
                if (good) {
                    callback(
                        newExtractorLink(source = name, name = tag, url = url, type = ExtractorLinkType.M3U8) {
                            this.quality = Qualities.Unknown.value
                            this.referer = referer
                            this.headers = mapOf("Referer" to referer, "User-Agent" to ua)
                        }
                    )
                }
                good
            } catch (e: Exception) {
                false
            }
        }

        suspend fun addValidatedMp4(url: String, referer: String, tag: String): Boolean {
            return try {
                val head = app.head(url, headers = mapOf("Referer" to referer, "User-Agent" to ua))
                if (head.isSuccessful) {
                    callback(
                        newExtractorLink(source = name, name = tag, url = url, type = ExtractorLinkType.VIDEO) {
                            this.quality = Qualities.Unknown.value
                            this.referer = referer
                            this.headers = mapOf("Referer" to referer, "User-Agent" to ua)
                        }
                    )
                }
                head.isSuccessful
            } catch (e: Exception) {
                false
            }
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

                    if (echoRes.trim().startsWith("{")) {
                        val echoJson = JSONObject(echoRes)

                        val streamUrl = when (val src = echoJson.opt("sources")) {
                            is String -> src
                            is JSONArray -> {
                                var res = ""
                                for (i in 0 until src.length()) {
                                    val file = src.optJSONObject(i)?.optString("file") ?: ""
                                    if (file.isNotBlank()) {
                                        res = file
                                        break
                                    }
                                }
                                res
                            }
                            else -> ""
                        }.replace("\\/", "/")

                        if (streamUrl.isNotBlank() && streamUrl.startsWith("http")) {
                            if (streamUrl.contains("m3u8")) {
                                if (addValidatedM3u8(streamUrl, embedUrl, tag)) return true
                            } else {
                                if (addValidatedMp4(streamUrl, embedUrl, tag)) return true
                            }
                            return addUnvalidated(streamUrl, embedUrl, tag)
                        }
                        parseSubtitles(echoJson)
                    }
                } catch (e: Exception) { }
            }

            try {
                if (loadExtractor(embedUrl, data, subtitleCallback, callback)) return true
            } catch (e: Exception) { }

            try {
                val embedHtml = app.get(
                    embedUrl,
                    headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to ua)
                ).text.replace("\\/", "/")

                val candidates = mutableListOf<String>()
                // Use standard for loop to safely allow adding to the list inside the suspend context
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

                var fallback: String? = null
                for (c in candidates.distinct()) {
                    if (fallback == null && !isJunk(c)) fallback = c
                    if (isJunk(c)) continue
                    if (c.contains("m3u8")) {
                        if (addValidatedM3u8(c, embedUrl, tag)) return true
                    } else {
                        if (addValidatedMp4(c, embedUrl, tag)) return true
                    }
                }

                if (fallback != null) {
                    return addUnvalidated(fallback, embedUrl, tag)
                }

                val iframeSrc = Jsoup.parse(embedHtml).selectFirst("iframe")?.attr("src") ?: ""
                if (iframeSrc.isNotBlank()) {
                    val cleanIframe = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                    if (cleanIframe != embedUrl) {
                        try {
                            if (loadExtractor(cleanIframe, data, subtitleCallback, callback)) return true
                        } catch (e: Exception) { }
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

            val serverIds = mutableListOf<String>()
            for (el in serverDoc.select("a, div, li, span")) {
                val id = el.attr("data-link").ifBlank { el.attr("data-id") }.ifBlank { el.attr("data-server") }
                if (id.isNotBlank() && !serverIds.contains(id)) {
                    serverIds.add(id)
                }
            }
            if (serverIds.isEmpty()) {
                for (m in Regex("""data-(?:link-)?id=["']([^"'>\s]+)["']""").findAll(serverHtml)) {
                    val v = m.groupValues[1].trim()
                    if (v.isNotBlank() && !serverIds.contains(v)) serverIds.add(v)
                }
            }

            for (id in serverIds) {
                val tag = "Aniwaves Server ${serverIds.indexOf(id) + 1}"
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

                        if (embedUrl.contains("vidplay") || embedUrl.contains("mewcdn") || embedUrl.contains("megacloud")) {
                            val embedIdMatch = Regex("""(?:/e/|/embed-\d+/|/v/|[?&]id=)([\w-]+)""").find(embedUrl)
                            try { if (loadExtractor(embedUrl, data, subtitleCallback, callback)) serverFound = true } catch (e: Exception) { }
                            if (!serverFound && embedIdMatch != null) {
                                val embedId = embedIdMatch.groupValues[1]
                                for (alt in listOf(
                                    "https://megacloud.tv/embed-2/e-1/$embedId",
                                    "https://vidplay.site/e/$embedId"
                                )) {
                                    try {
                                        if (loadExtractor(alt, data, subtitleCallback, callback)) { serverFound = true; break }
                                    } catch (e: Exception) { }
                                }
                            }
                            if (serverFound) { found = true; break }
                        }

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

        return found
    }
}