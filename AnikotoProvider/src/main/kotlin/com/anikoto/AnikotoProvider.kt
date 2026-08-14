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
    override var mainUrl = "https://anikoto.cz"
    override var name = "Anikoto"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // anikoto's public catalog API (megaplay library) - provides series + episode ids + mal/anilist ids
    private val apiBase = "https://anikotoapi.site"
    // yenime's public megaplay resolver - turns a MAL id + episode into a playable proxied m3u8
    // (anikoto uses the same megaplay library, so this resolves anikoto episodes too)
    private val flikHubApi = "https://api.flikhub.net"
    private val yenimeUrl = "https://api.yenime.net"
    private val anilistApi = "https://graphql.anilist.co"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val ajaxHeaders = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "User-Agent" to userAgent,
        "Accept" to "application/json, text/javascript, */*; q=0.01"
    )

    // last-resort way to get a MAL id when the anikoto api is unreachable (needed for the flikhub source)
    private suspend fun resolveMalIdByTitle(title: String): String {
        if (title.isBlank()) return ""
        try {
            val query = """
                query(${'$'}search: String) {
                    Page(page: 1, perPage: 8) {
                        media(search: ${'$'}search, type: ANIME) {
                            id idMal title { romaji english }
                        }
                    }
                }
            """.trimIndent()
            val req = app.post(
                anilistApi,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "User-Agent" to userAgent
                ),
                json = mapOf("query" to query, "variables" to mapOf("search" to title))
            )
            if (!req.isSuccessful) return ""
            val json = JSONObject(req.text)
            val media = json.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return ""

            val wanted = title.lowercase().trim()
            var best = ""
            for (i in 0 until media.length()) {
                val m = media.optJSONObject(i) ?: continue
                val t = m.optJSONObject("title")
                val eng = t?.optString("english")?.lowercase() ?: ""
                val rom = t?.optString("romaji")?.lowercase() ?: ""
                val mal = m.optInt("idMal", 0)
                if (mal == 0) continue
                if (best.isBlank()) best = mal.toString()
                if (eng == wanted || rom == wanted) return mal.toString()
            }
            return best
        } catch (e: Exception) {
            return ""
        }
    }

    private fun extractItems(html: String, selector: String): List<SearchResponse> {
        val document = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()

        val elements = document.select(selector)
        for (element in elements) {
            val a = element.selectFirst("a[href*=/watch/]") ?: element.selectFirst("a") ?: continue
            val url = fixUrlNull(a.attr("href")) ?: continue

            val title = element.selectFirst("h2.title, h2, .name, .title")?.text()?.trim()
                ?: element.selectFirst("a.name, a.title, .info a")?.text()?.trim()
                ?: "Unknown"

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

    // ---- catalog from the anikoto API (recent anime, page-based) ----
    private suspend fun recentFromApi(page: Int): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        try {
            val res = app.get(
                "$apiBase/recent-anime?page=$page&per_page=20",
                headers = mapOf("User-Agent" to userAgent, "Accept" to "application/json")
            )
            if (!res.isSuccessful) return items
            val json = JSONObject(res.text)
            if (!json.optBoolean("ok", false)) return items
            val data = json.optJSONArray("data") ?: return items
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val title = item.optString("title").ifBlank { item.optString("alternative") }
                val slug = item.optString("slug")
                if (slug.isBlank()) continue
                val poster = item.optString("poster")
                items.add(newAnimeSearchResponse(title, "$mainUrl/watch/$slug", TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeItems = mutableListOf<HomePageList>()

        val apiItems = recentFromApi(page)
        if (apiItems.isNotEmpty()) {
            homeItems.add(HomePageList("Latest Episodes", apiItems))
            return newHomePageResponse(homeItems)
        }

        // fallback: scrape the site
        val html = app.get("$mainUrl/home").text
        val latest = extractItems(html, "#recent-update .item")
        if (latest.isNotEmpty()) homeItems.add(HomePageList("Latest Episodes", latest))
        val trending = extractItems(html, "#hotest .item, .w-side-section .item")
        if (trending.isNotEmpty()) homeItems.add(HomePageList("Trending", trending))
        val newAdded = extractItems(html, ".top-table[data-name=new-release] .item, .top-table[data-name=new-added] .item")
        if (newAdded.isNotEmpty()) homeItems.add(HomePageList("New Added", newAdded))

        return newHomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.get("$mainUrl/filter?keyword=$query").text
        return extractItems(html, ".item")
    }

    // ---- episodes ----

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url).text
        val document = Jsoup.parse(html)

        val title = document.selectFirst("h1.title")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst(".poster img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val plot = document.selectFirst(".synopsis .content")?.text()?.trim() ?: ""

        val animeId = document.selectFirst("#watch-main")?.attr("data-id") ?: ""
        val watchUrl = document.selectFirst("#watch-main")?.attr("data-url") ?: url.substringBefore("#").substringBefore("?")

        // anikoto series data from the public API (gives mal/ani ids + per-episode embed ids)
        var malId = ""
        var aniId = ""
        var isDub = false
        val apiEpisodes = mutableListOf<Pair<Int, String>>() // number -> episode_embed_id

        if (animeId.isNotBlank()) {
            try {
                val res = app.get(
                    "$apiBase/series/$animeId",
                    headers = mapOf("User-Agent" to userAgent, "Accept" to "application/json")
                )
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

            // if the anikoto api was unreachable, try AniList as a last resort for a MAL id
            if (malId.isBlank()) {
                malId = resolveMalIdByTitle(title)
            }
        }

        // the site's own episode list ajax (needs a session cookie - GET the page first, then POST)
        val siteEpisodes = mutableListOf<Pair<Int, String>>() // number -> data-ids
        if (animeId.isNotBlank()) {
            try {
                val res = app.post(
                    "$mainUrl/ajax/episode/list/$animeId",
                    headers = ajaxHeaders + mapOf("Referer" to url),
                    data = mapOf("style" to "default", "vrf" to "")
                )
                if (res.isSuccessful) {
                    var body = res.text
                    if (body.trim().startsWith("{")) {
                        val j = JSONObject(body)
                        if (j.optInt("status", 500) == 200) body = j.optString("result", body)
                    }
                    val epDoc = Jsoup.parse(body)
                    for (a in epDoc.select("a[data-slug], a[data-num], a[href*=/ep-]")) {
                        val num = a.attr("data-num").toIntOrNull()
                            ?: a.attr("data-slug").toIntOrNull()
                            ?: Regex("""/ep-(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                            ?: continue
                        val ids = a.attr("data-ids")
                        if (ids.isNotBlank()) siteEpisodes.add(num to ids)
                        else if (siteEpisodes.none { it.first == num }) siteEpisodes.add(num to "")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val episodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        if (apiEpisodes.isNotEmpty() || siteEpisodes.isNotEmpty()) {
            val maxNum = maxOf(
                apiEpisodes.maxOfOrNull { it.first } ?: 0,
                siteEpisodes.maxOfOrNull { it.first } ?: 0
            )
            val idMap = apiEpisodes.toMap()
            val siteIdMap = siteEpisodes.toMap()

            for (n in 1..maxNum) {
                val embedId = idMap[n].orEmpty()
                val siteIds = siteIdMap[n].orEmpty()

                val params = buildString {
                    append("?ep=$n")
                    if (malId.isNotBlank()) append("&mal=$malId")
                    if (aniId.isNotBlank()) append("&ani=$aniId")
                    if (embedId.isNotBlank()) append("&embed=$embedId")
                    if (siteIds.isNotBlank()) append("&ids=$siteIds")
                }

                episodes.add(newEpisode("$watchUrl$params") {
                    this.name = "Episode $n"
                    this.episode = n
                })
                if (isDub) {
                    dubEpisodes.add(newEpisode("$watchUrl$params&lang=dub") {
                        this.name = "Episode $n (Dub)"
                        this.episode = n
                    })
                }
            }
        }

        if (episodes.isEmpty()) {
            // last resort: total episode count from the api anime object (or page fallback)
            var total = 0
            if (animeId.isNotBlank()) {
                try {
                    val res = app.get("$apiBase/series/$animeId", headers = mapOf("User-Agent" to userAgent))
                    if (res.isSuccessful) {
                        val json = JSONObject(res.text)
                        total = json.optJSONObject("data")?.optJSONObject("anime")?.optString("episodes")
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
                    if (aniId.isNotBlank()) append("&ani=$aniId")
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
        val malId = Regex("""[?&]mal=(\d+)""").find(cleanData)?.groupValues?.get(1)
        val embedId = Regex("""[?&]embed=([^&]+)""").find(cleanData)?.groupValues?.get(1)
        val siteIds = Regex("""[?&]ids=([^&]+)""").find(cleanData)?.groupValues?.get(1)
        val requestedLang = Regex("""[?&]lang=(sub|dub)""").find(cleanData)?.groupValues?.get(1) ?: "sub"

        val watchUrl = cleanData.substringBefore("?")
        val langs = listOf(requestedLang) + listOf(if (requestedLang == "dub") "sub" else "dub")

        // ---- server 1: yenime/flikhub megaplay resolver (same library as anikoto, playable m3u8) ----
        for (lang in langs) {
            if (malId == null) break
            try {
                val res = app.get(
                    "$flikHubApi/megaplay?mal=$malId&ep=$ep&type=$lang",
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Accept" to "application/json",
                        "Referer" to yenimeUrl
                    )
                )
                if (!res.isSuccessful) continue
                val json = JSONObject(res.text)
                val streamUrl = json.optString("proxiedUrl").ifBlank { json.optString("m3u8") }
                if (streamUrl.isNotBlank() && !streamUrl.startsWith("blob:")) {
                    val tracks = json.optJSONArray("tracks")
                    if (tracks != null) {
                        for (i in 0 until tracks.length()) {
                            val track = tracks.optJSONObject(i) ?: continue
                            val subUrl = track.optString("file")
                            if (subUrl.isBlank()) continue
                            val label = track.optString("label", "English")
                            subtitleCallback(
                                newSubtitleFile(label, subUrl.replace("\\/", "/")) {
                                    this.headers = mapOf(
                                        "Referer" to "https://megaplay.buzz/",
                                        "User-Agent" to userAgent
                                    )
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
                            this.headers = mapOf(
                                "Referer" to yenimeUrl,
                                "User-Agent" to userAgent
                            )
                        }
                    )
                    found = true
                }
            } catch (e: Exception) {
                // try the next lang / server
            }
            if (found) break
        }

        // ---- server 2: megaplay embed directly from the episode id ----
        if (embedId.isNotBlank()) {
            for (lang in langs) {
                try {
                    val embedUrl = "https://megaplay.buzz/stream/s-2/$embedId/$lang"
                    if (resolveMegaplayEmbed(embedUrl, watchUrl, lang, subtitleCallback, callback)) {
                        found = true
                        break
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // ---- server 3: the site's own server list (data-ids -> server list -> embed url) ----
        if (siteIds.isNotBlank()) {
            try {
                val ref = watchUrl
                val listRes = app.get(
                    "$mainUrl/ajax/server/list?servers=$siteIds",
                    headers = ajaxHeaders + mapOf("Referer" to ref)
                )
                if (listRes.isSuccessful) {
                    var listBody = listRes.text
                    if (listBody.trim().startsWith("{")) {
                        val j = JSONObject(listBody)
                        if (j.optInt("status", 500) == 200) listBody = j.optString("result", listBody)
                    }
                    val doc = Jsoup.parse(listBody)
                    val serverItems = doc.select("li[data-link-id], li[data-id]")
                    for (li in serverItems) {
                        val linkId = li.attr("data-link-id").ifBlank { li.attr("data-id") }
                        if (linkId.isBlank()) continue
                        val serverName = li.selectFirst(".name, .server, a")?.text()?.trim()
                            ?: li.attr("data-title").ifBlank { "Server" }
                        val liType = li.attr("data-type")
                        val serverLang = if (liType == "dub" || liType == "eng") "dub" else "sub"
                        if (serverLang != requestedLang) continue

                        try {
                            val getRes = app.get(
                                "$mainUrl/ajax/server?get=$linkId",
                                headers = ajaxHeaders + mapOf("Referer" to ref)
                            )
                            if (getRes.isSuccessful) {
                                val sj = JSONObject(getRes.text)
                                if (sj.optInt("status", 500) == 200) {
                                    val streamUrl = sj.optJSONObject("result")?.optString("url")
                                        ?: sj.optString("url")
                                    if (streamUrl.startsWith("http")) {
                                        val resolved = resolveEmbed(streamUrl, ref, serverName, subtitleCallback, callback)
                                        if (resolved) found = true
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return found
    }

    // resolves any embed url a server returned - known hosts go through CloudStream's extractors,
    // megaplay/rapidcloud/vidplay-family embeds get the inline attempt, direct media is passed through.
    private suspend fun resolveEmbed(
        embedUrl: String,
        referer: String,
        serverName: String,
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
                host.contains("mikora") || host.contains("watching.onl") || host.contains("shiora")
        if (megaplayFamily) {
            if (resolveMegaplayEmbed(clean, referer, serverName, subtitleCallback, callback)) return true
        }

        // generic: let registered extractors try it
        return loadExtractor(clean, referer, subtitleCallback, callback)
    }

    // inline megaplay/rapidcloud-family embed resolver (best-effort; the flikhub source above is the reliable one)
    private suspend fun resolveMegaplayEmbed(
        embedUrl: String,
        referer: String,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf("Referer" to referer, "User-Agent" to userAgent)
        val host = try { java.net.URI(embedUrl).host } catch (e: Exception) { null } ?: return false

        var page: String
        try {
            page = app.get(embedUrl, referer = referer).text
        } catch (e: Exception) {
            return false
        }
        if (page.contains("Error Code") || page.isBlank()) return false

        var found = false

        // subtitles embedded in the page
        Regex("""captions['"]?\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).findAll(page).forEach { m ->
            Regex("""'file'\s*:\s*'([^']+)'""").findAll(m.groupValues[1]).forEach { sm ->
                val subUrl = sm.groupValues[1].replace("\\/", "/")
                if (subUrl.startsWith("http") && subUrl.contains(".vtt")) {
                    subtitleCallback(
                        newSubtitleFile("English", subUrl) {
                            this.headers = headers
                        }
                    )
                }
            }
        }

        // direct m3u8/mp4 in the page (sometimes the player data is inline)
        Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:[^\s"'<>\\]*)""").findAll(page).forEach { m ->
            val mediaUrl = m.value.replace("\\/", "/")
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = mediaUrl,
                    type = if (mediaUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = referer
                    this.headers = headers
                }
            )
            found = true
        }
        if (found) return true

        // packed/eval'd scripts (packer unpacking)
        val unpacked = try {
            if (page.contains("eval(function")) getAndUnpack(page) else ""
        } catch (e: Exception) {
            ""
        }
        if (unpacked.isNotBlank()) {
            Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:[^\s"'<>\\]*)""").findAll(unpacked).forEach { m ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = m.value.replace("\\/", "/"),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = referer
                        this.headers = headers
                    }
                )
                found = true
            }
            if (found) return true
        }

        // try the megacloud-family sources api with an id extracted from the page/url
        val idFromUrl = Regex("""/stream/s-?\d+/([^/]+)""").find(embedUrl)?.groupValues?.get(1)
            ?: Regex("""[?&]id=([^&]+)""").find(page)?.groupValues?.get(1)
        val pageId = Regex("""data-id=["']([^"']+)["']""").find(page)?.groupValues?.get(1)
        val sourceId = listOfNotNull(pageId, idFromUrl).firstOrNull { it.isNotBlank() && it.length > 2 }

        if (sourceId != null) {
            val apiBases = mutableListOf<String>()
            Regex("""https?://[^\s"'<>\\]+(?:api[^\s"'<>\\]*|/sources)""").findAll(page).forEach { m ->
                apiBases.add(m.value.replace("\\/", "/"))
            }
            apiBases.add("https://megaplayapi.site/api/sources")
            apiBases.add("https://$host/api/sources")

            for (rawBase in apiBases) {
                val base = when {
                    rawBase.contains("/sources") -> rawBase
                    else -> "${rawBase.substringBefore("/api")}/api/sources"
                }
                try {
                    val r = app.post(base, referer = referer, data = mapOf("id" to sourceId))
                    if (!r.isSuccessful) continue
                    val body = r.text
                    val j = JSONObject(body)
                    val arr = j.optJSONArray("sources")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val file = arr.optJSONObject(i)?.optString("file") ?: continue
                            if (!file.startsWith("http")) continue
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = name,
                                    url = file.replace("\\/", "/"),
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.referer = referer
                                    this.headers = headers
                                }
                            )
                            found = true
                        }
                    }
                } catch (e: Exception) {
                    // try the next api base
                }
                if (found) return true
            }
        }

        return found
    }
}
