package com.anikoto

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import org.jsoup.Jsoup
import org.json.JSONObject

@CloudstreamPlugin
class AnikotoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnikotoProvider())
    }
}

class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikototv.to"
    override var name = "Anikoto"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val fallbackUrl = "https://anikoto.cz"
    // anikoto's public catalog API (megaplay library) - provides series + episode ids + mal/anilist ids
    private val apiBase = "https://anikotoapi.site"
    // yenime's public megaplay resolver (extra fallback when the site's own servers are unreachable)
    private val flikHubApi = "https://api.flikhub.net"
    private val yenimeUrl = "https://api.yenime.net"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    )

    private val ajaxHeaders = browserHeaders + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01"
    )

    // tries a path on the main domain, then the fallback domain
    private suspend fun getWithFallback(pathAndQuery: String, headers: Map<String, String>, referer: String): String? {
        val domains = listOf(mainUrl, fallbackUrl).distinct()
        for (d in domains) {
            try {
                val r = app.get(
                    "$d$pathAndQuery",
                    headers = headers + mapOf("Referer" to referer.ifBlank { "$d/" })
                )
                if (r.isSuccessful) return r.text
            } catch (e: Exception) {
                // try the next domain
            }
        }
        return null
    }

    // unwraps the {status, result} ajax wrapper
    private fun parseAjaxResult(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            return try {
                val j = JSONObject(trimmed)
                if (j.optInt("status", 500) == 200) j.optString("result", trimmed) else ""
            } catch (e: Exception) {
                trimmed
            }
        }
        return trimmed
    }

    // ---- catalog ----

    private fun extractItems(html: String, selector: String): List<SearchResponse> {
        val document = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()

        for (element in document.select(selector)) {
            val a = element.selectFirst("a[href*=/watch/]") ?: element.selectFirst("a") ?: continue
            val url = fixUrlNull(a.attr("href")) ?: continue
            if (!url.contains("/watch/")) continue

            val title = element.selectFirst("h2.title, h2, .name, .title")?.text()?.trim()
                ?: element.selectFirst("a.name, a.title, .info a")?.text()?.trim()
                ?: a.attr("title").ifBlank { "Unknown" }

            var poster = element.selectFirst("img")?.attr("data-src")?.ifBlank { null }
                ?: element.selectFirst("img")?.attr("src")?.ifBlank { null }

            if (poster.isNullOrBlank()) {
                val style = element.selectFirst(".image div, .poster div, div[style*=background]")?.attr("style") ?: ""
                poster = Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)
            }

            items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster ?: ""
            })
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeItems = mutableListOf<HomePageList>()
        val html = getWithFallback("/home", browserHeaders, "$mainUrl/")
        if (html != null) {
            val latest = extractItems(html, "#recent-update .item")
            if (latest.isNotEmpty()) homeItems.add(HomePageList("Latest Episodes", latest))
            val trending = extractItems(html, "#hotest .item, .w-side-section .item")
            if (trending.isNotEmpty()) homeItems.add(HomePageList("Trending", trending))
            val newAdded = extractItems(html, ".top-table[data-name=new-release] .item, .top-table[data-name=new-added] .item")
            if (newAdded.isNotEmpty()) homeItems.add(HomePageList("New Added", newAdded))
        }

        if (homeItems.isEmpty()) {
            // fallback to the anikoto api
            try {
                val res = app.get("$apiBase/recent-anime?page=$page&per_page=20", headers = mapOf("User-Agent" to userAgent))
                if (res.isSuccessful) {
                    val json = JSONObject(res.text)
                    if (json.optBoolean("ok", false)) {
                        val data = json.optJSONArray("data") ?: return newHomePageResponse(homeItems)
                        val items = mutableListOf<SearchResponse>()
                        for (i in 0 until data.length()) {
                            val item = data.optJSONObject(i) ?: continue
                            val title = item.optString("title").ifBlank { item.optString("alternative") }
                            val slug = item.optString("slug")
                            if (slug.isBlank()) continue
                            items.add(newAnimeSearchResponse(title, "$mainUrl/watch/$slug", TvType.Anime) {
                                this.posterUrl = item.optString("poster")
                            })
                        }
                        if (items.isNotEmpty()) homeItems.add(HomePageList("Latest Episodes", items))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return newHomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = getWithFallback("/filter?keyword=${query}", browserHeaders, "$mainUrl/")
        if (html != null) {
            val items = extractItems(html, ".item")
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    // ---- episodes ----

    private data class EpisodeInfo(val num: Int, val ids: String, val name: String, val hasDub: Boolean)

    // parses the site's episode list ajax response
    private fun parseEpisodeAnchors(html: String): List<EpisodeInfo> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<EpisodeInfo>()
        for (a in doc.select("a[data-num], a[data-slug], a[href*=/ep-]")) {
            val num = a.attr("data-num").toIntOrNull()
                ?: a.attr("data-slug").toIntOrNull()
                ?: Regex("""/ep-(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val ids = a.attr("data-ids")
            val name = a.attr("title").ifBlank { a.text().trim() }.ifBlank { "Episode $num" }
            val hasDub = a.attr("data-dub").isNotBlank() || a.attr("data-type") == "dub"
            out.add(EpisodeInfo(num, ids, name, hasDub))
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val pageHtml = getWithFallback(relativePath(url), browserHeaders, "$mainUrl/") ?: ""
        val document = Jsoup.parse(pageHtml)

        val title = document.selectFirst("h1.title")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst(".poster img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val plot = document.selectFirst(".synopsis .content, .synopsis")?.text()?.trim() ?: ""

        val animeId = document.selectFirst("#watch-main")?.attr("data-id") ?: ""
        val watchUrl = document.selectFirst("#watch-main")?.attr("data-url")?.trimEnd('/') ?: url.substringBefore("?").trimEnd('/')

        // series info + episodes from the anikoto api (gives mal/ani ids + per-episode embed ids)
        var malId = ""
        var aniId = ""
        var isDub = false
        val apiEpisodes = mutableListOf<Pair<Int, String>>() // number -> episode_embed_id

        if (animeId.isNotBlank()) {
            try {
                val res = app.get("$apiBase/series/$animeId", headers = mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
                if (res.isSuccessful) {
                    val json = JSONObject(res.text)
                    if (json.optBoolean("ok", false)) {
                        val data = json.optJSONObject("data")
                        val anime = data?.optJSONObject("anime")
                        if (anime != null) {
                            malId = anime.optString("mal_id")
                            aniId = anime.optString("ani_id")
                            isDub = anime.optInt("is_dub", 0) > 0
                        }
                        val eps = data?.optJSONArray("episodes")
                        if (eps != null) {
                            for (i in 0 until eps.length()) {
                                val ep = eps.optJSONObject(i) ?: continue
                                val num = ep.optInt("number", 0)
                                val embedId = ep.optString("episode_embed_id")
                                if (num > 0) apiEpisodes.add(num to embedId)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // the site's own episode list (data-ids per episode, needed for the server list)
        val siteEpisodes = mutableListOf<EpisodeInfo>()
        if (animeId.isNotBlank()) {
            val ref = "$mainUrl/watch/$animeId"
            val body = getWithFallback("/ajax/episode/list/$animeId", ajaxHeaders, ref)
            if (body != null) {
                val result = parseAjaxResult(body)
                if (result.isNotBlank()) siteEpisodes.addAll(parseEpisodeAnchors(result))
            }
            if (siteEpisodes.isEmpty()) {
                // retry with POST + style/vrf params (the site's browser sends these)
                for (d in listOf(mainUrl, fallbackUrl).distinct()) {
                    try {
                        val r = app.post(
                            "$d/ajax/episode/list/$animeId",
                            headers = ajaxHeaders + mapOf("Referer" to ref),
                            data = mapOf("style" to "default", "vrf" to "")
                        )
                        if (r.isSuccessful) {
                            val result = parseAjaxResult(r.text)
                            if (result.isNotBlank()) {
                                siteEpisodes.addAll(parseEpisodeAnchors(result))
                                if (siteEpisodes.isNotEmpty()) break
                            }
                        }
                    } catch (e: Exception) {
                        // next domain
                    }
                }
            }
        }

        val episodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        val idMap = apiEpisodes.toMap()
        val siteById = siteEpisodes.associate { it.num to it }

        val maxNum = maxOf(apiEpisodes.maxOfOrNull { it.first } ?: 0, siteEpisodes.maxOfOrNull { it.num } ?: 0)

        if (maxNum > 0) {
            for (n in 1..maxNum) {
                val embedId = idMap[n].orEmpty()
                val ids = siteById[n]?.ids.orEmpty()
                val params = buildString {
                    append("?ep=$n")
                    if (malId.isNotBlank()) append("&mal=$malId")
                    if (embedId.isNotBlank()) append("&embed=$embedId")
                    if (ids.isNotBlank()) append("&ids=$ids")
                }
                val epName = siteById[n]?.name ?: "Episode $n"
                episodes.add(newEpisode("$watchUrl$params") {
                    this.name = epName
                    this.episode = n
                })
                if (isDub || siteById[n]?.hasDub == true) {
                    dubEpisodes.add(newEpisode("$watchUrl$params&lang=dub") {
                        this.name = "$epName (Dub)"
                        this.episode = n
                    })
                }
            }
        }

        if (episodes.isEmpty()) {
            // last resort: total episode count from the api or the page
            var total = 0
            if (animeId.isNotBlank()) {
                try {
                    val res = app.get("$apiBase/series/$animeId", headers = mapOf("User-Agent" to userAgent))
                    if (res.isSuccessful) {
                        total = JSONObject(res.text).optJSONObject("data")?.optJSONObject("anime")?.optString("episodes")
                            ?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (total <= 0) {
                val infoBlock = document.selectFirst(".binfo, #w-info, .anime-info")
                val epsDiv = infoBlock?.select("div.meta > div")?.firstOrNull { it.text().contains("Episodes:") }
                total = epsDiv?.selectFirst("span")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 1
            }
            for (i in 1..total) {
                val params = buildString {
                    append("?ep=$i")
                    if (malId.isNotBlank()) append("&mal=$malId")
                }
                episodes.add(newEpisode("$watchUrl$params") {
                    this.name = "Episode $i"
                    this.episode = i
                })
                if (isDub) {
                    dubEpisodes.add(newEpisode("$watchUrl$params&lang=dub") {
                        this.name = "Episode $i (Dub)"
                        this.episode = i
                    })
                }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.episode })
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes.distinctBy { it.episode })
        }
    }

    private fun relativePath(url: String): String {
        val clean = url.substringBefore("?").substringBefore("#")
        for (d in listOf(mainUrl, fallbackUrl)) {
            if (clean.startsWith(d)) return clean.substring(d.length).ifEmpty { "/" }
        }
        return clean
    }

    // ---- links / servers ----

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val cleanData = data.substringBefore("#")
        val ep = Regex("""[?&]ep=(\d+)""").find(cleanData)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        val malId = Regex("""[?&]mal=(\d+)""").find(cleanData)?.groupValues?.get(1) ?: ""
        val embedId = Regex("""[?&]embed=([^&]+)""").find(cleanData)?.groupValues?.get(1) ?: ""
        val siteIds = Regex("""[?&]ids=([^&]+)""").find(cleanData)?.groupValues?.get(1) ?: ""
        val requestedLang = Regex("""[?&]lang=(sub|dub)""").find(cleanData)?.groupValues?.get(1) ?: "sub"

        val watchUrl = cleanData.substringBefore("?")
        val langs = listOf(requestedLang) + listOf(if (requestedLang == "dub") "sub" else "dub")

        // --- megaplay direct from the api's episode embed id (fast path) ---
        if (embedId.isNotBlank()) {
            for (lang in langs) {
                try {
                    val megaUrl = "https://megaplay.buzz/stream/s-2/$embedId/$lang"
                    if (resolveMegaPlay(megaUrl, lang, subtitleCallback, callback)) {
                        found = true
                        break
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // --- the site's own server list (data-ids -> servers -> megaplay embeds) ---
        if (siteIds.isNotBlank()) {
            val serverListBody = getWithFallback("/ajax/server/list?servers=$siteIds", ajaxHeaders, watchUrl)
            if (serverListBody != null) {
                val listHtml = parseAjaxResult(serverListBody)
                val doc = Jsoup.parse(listHtml)
                for (li in doc.select("li[data-link-id], li[data-id]")) {
                    val linkId = li.attr("data-link-id").ifBlank { li.attr("data-id") }
                    if (linkId.isBlank()) continue
                    val serverName = li.selectFirst(".name, .server, a")?.text()?.trim()
                        ?: li.attr("data-title").ifBlank { "Server" }
                    val liType = li.attr("data-type")
                    val serverLang = if (liType == "dub" || liType == "eng") "dub" else "sub"
                    if (liType.isNotBlank() && serverLang != requestedLang) continue

                    val serverBody = getWithFallback("/ajax/server?get=$linkId", ajaxHeaders, watchUrl)
                    if (serverBody != null) {
                        val streamUrl = try {
                            val j = JSONObject(serverBody)
                            if (j.optInt("status", 500) == 200) {
                                j.optJSONObject("result")?.optString("url") ?: j.optString("result").ifBlank { j.optString("url") }
                            } else ""
                        } catch (e: Exception) {
                            Regex(""""url"\s*:\s*"([^"]+)"""").find(serverBody)?.groupValues?.get(1) ?: ""
                        }
                        if (streamUrl.startsWith("http")) {
                            if (resolveEmbed(streamUrl, watchUrl, serverName, requestedLang, subtitleCallback, callback)) {
                                found = true
                            }
                        }
                    }
                }
            }
        }

        // --- yenime/flikhub megaplay resolver (only when nothing above worked) ---
        if (!found && malId.isNotBlank()) {
            for (lang in langs) {
                try {
                    val res = app.get(
                        "$flikHubApi/megaplay?mal=$malId&ep=$ep&type=$lang",
                        headers = mapOf("User-Agent" to userAgent, "Accept" to "application/json", "Referer" to yenimeUrl)
                    )
                    if (res.isSuccessful) {
                        val json = JSONObject(res.text)
                        val streamUrl = json.optString("proxiedUrl").ifBlank { json.optString("m3u8") }
                        if (streamUrl.isNotBlank() && !streamUrl.startsWith("blob:")) {
                            val tracks = json.optJSONArray("tracks")
                            if (tracks != null) {
                                for (i in 0 until tracks.length()) {
                                    val track = tracks.optJSONObject(i) ?: continue
                                    val subUrl = track.optString("file")
                                    if (subUrl.isBlank()) continue
                                    subtitleCallback(
                                        newSubtitleFile(track.optString("label", "English"), subUrl.replace("\\/", "/")) {
                                            this.headers = mapOf("Referer" to "https://megaplay.buzz/", "User-Agent" to userAgent)
                                        }
                                    )
                                }
                            }
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "MegaPlay ${lang.uppercase()}",
                                    url = streamUrl.replace("\\/", "/"),
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = yenimeUrl
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf("Referer" to yenimeUrl, "User-Agent" to userAgent)
                                }
                            )
                            found = true
                        }
                    }
                } catch (e: Exception) {
                    // try the next lang / source
                }
                if (found) break
            }
        }

        return found
    }

    private suspend fun resolveEmbed(
        embedUrl: String,
        referer: String,
        serverName: String,
        requestedLang: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = embedUrl.replace("\\/", "/")
        val host = try { java.net.URI(clean).host } catch (e: Exception) { null } ?: return false

        if (Regex("""\.(m3u8|mp4)($|\?)""", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            callback(
                newExtractorLink(
                    source = name,
                    name = serverName,
                    url = clean,
                    type = if (clean.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = referer
                    this.headers = mapOf("Referer" to referer, "User-Agent" to userAgent)
                }
            )
            return true
        }

        val megaplayFamily = host.contains("megaplay") || host.contains("megacloud") ||
                host.contains("rapid-cloud") || host.contains("vidplay") || host.contains("vidtube") ||
                host.contains("vidwish") || host.contains("mikora") || host.contains("watching.onl") ||
                host.contains("shiora")
        if (megaplayFamily) {
            if (resolveMegaPlay(clean, requestedLang, subtitleCallback, callback)) return true
        }

        return loadExtractor(clean, referer, subtitleCallback, callback)
    }

    // resolves a megaplay-family embed via its sources API:
    //   GET {host}/stream/getSources?id={episodeId}
    private suspend fun resolveMegaPlay(
        embedUrl: String,
        lang: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = embedUrl.replace("\\/", "/")
        val host = try { java.net.URI(clean).host } catch (e: Exception) { null } ?: return false

        // extract the episode id from /stream/s-2/{id}/{lang} (also s-5, s-8, ...)
        val epId = Regex("""/stream/s-\d+/(\d+)""").find(clean)?.groupValues?.get(1)
            ?: Regex("""[?&]id=(\d+)""").find(clean)?.groupValues?.get(1)
            ?: return false

        val headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "https://$host/",
            "Origin" to "https://$host",
            "User-Agent" to userAgent,
            "Accept" to "application/json, text/javascript, */*; q=0.01"
        )

        val sourcesText = try {
            app.get("https://$host/stream/getSources?id=$epId", headers = headers).text
        } catch (e: Exception) {
            return false
        }
        if (sourcesText.isBlank() || sourcesText.contains("Forbidden")) return false

        var found = false

        // subtitles + sources
        try {
            val j = JSONObject(sourcesText)
            val tracks = j.optJSONArray("tracks")
            if (tracks != null) {
                for (i in 0 until tracks.length()) {
                    val track = tracks.optJSONObject(i) ?: continue
                    val subUrl = track.optString("file")
                    if (subUrl.isBlank() || !subUrl.startsWith("http")) continue
                    subtitleCallback(
                        newSubtitleFile(track.optString("label", "English"), subUrl.replace("\\/", "/")) {
                            this.headers = mapOf("Referer" to "https://$host/", "User-Agent" to userAgent)
                        }
                    )
                }
            }
            val sources = j.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val s = sources.optJSONObject(i) ?: continue
                    val file = s.optString("file")
                    if (file.isBlank() || !file.startsWith("http")) continue
                    val label = s.optString("label").ifBlank { s.optString("quality") }.ifBlank { "HD" }
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "MegaPlay $label ($lang)",
                            url = file.replace("\\/", "/"),
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = "https://$host/"
                            this.headers = mapOf("Referer" to "https://$host/", "User-Agent" to userAgent)
                        }
                    )
                    found = true
                }
            }
        } catch (e: Exception) {
            // not json - fall through to regex
        }

        if (!found) {
            Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:[^\s"'<>\\]*)""").findAll(sourcesText).forEach { m ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = "MegaPlay ($lang)",
                        url = m.value.replace("\\/", "/"),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = "https://$host/"
                        this.headers = mapOf("Referer" to "https://$host/", "User-Agent" to userAgent)
                    }
                )
                found = true
            }
        }

        return found
    }
}
