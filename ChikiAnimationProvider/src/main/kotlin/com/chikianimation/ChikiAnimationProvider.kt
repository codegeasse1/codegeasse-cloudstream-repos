package com.chikianimation

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI

class ChikiAnimationProvider : MainAPI() {
    override var mainUrl = "https://chikianimation.online"
    override var name = "ChikiAnimation"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/anime/?status=&type=&order=update" to "Latest Release",
        "$mainUrl/anime/?status=&type=&order=popular" to "Popular",
        "$mainUrl/anime/?status=completed&type=&order=update" to "Completed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data.replace("?", "page/$page/?")
        val document = app.get(url).document

        val home = document.select("article.bs > div.bsx").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("a") ?: return null

        val rawHref = fixUrlNull(linkEl.attr("href")) ?: return null
        val href = rawHref.replace(Regex("-(episode|ep)-\\d+-[a-zA-Z0-9-]+/?$"), "")
            .let { if (it.contains("/anime/")) it else "$mainUrl/anime/${it.substringAfterLast("/")}" }

        val title = linkEl.attr("title").ifBlank {
            this.selectFirst("div.tt")?.text()
        }?.trim() ?: return null

        val rawPoster = this.selectFirst("img")?.attr("data-lazy-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("data-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("src")

        val posterUrl = fixUrlNull(rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.bs > div.bsx").mapNotNull { it.toSearchResult() }
    }

    // ---------------------------------------------------------------
    // LOAD (anime detail page + episode list)
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("(?i)(episode|ep)\\s*\\d+.*"), "") ?: ""

        val posterElement = document.selectFirst(
            ".bigcontent .thumb img, .bixbox .thumb img, article .thumb img, .infox .imgbox img, .ts-post-image"
        )

        val rawPoster = posterElement?.attr("data-lazy-src")?.ifBlank { null }
            ?: posterElement?.attr("data-src")?.ifBlank { null }
            ?: posterElement?.attr("src")

        var poster = fixUrlNull(rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://"))

        if (poster.isNullOrBlank()) {
            val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
            if (ogImage != null && !ogImage.contains("logo", true) && !ogImage.contains("banner", true)) {
                poster = fixUrlNull(ogImage)
            }
        }

        val synopsis = document.selectFirst(".entry-content, .synp .entry-content, #synopsis, .desc")?.text()
        val genres = document.select("a[href*=/genres/], .genxed a").map { it.text() }

        fun parseEpisodeGrid(doc: org.jsoup.nodes.Document, currentUrl: String): List<Episode> {
            val elements = doc.select("div.eplister ul li, div.episodelist ul li, ul.episodelist li, div.ep_list ul li, .bixbox.bxcl ul li")
            return elements.mapNotNull { li ->
                val epLink = li.selectFirst("a")
                val epHref = if (epLink != null && epLink.hasAttr("href")) fixUrlNull(epLink.attr("href"))
                else if (li.hasClass("selected") || li.hasAttr("selected") || li.select("div.playinfo").isNotEmpty()) currentUrl
                else return@mapNotNull null

                if (epHref == null) return@mapNotNull null

                val epTitle = (epLink?.attr("title")?.ifBlank { epLink.text() } ?: li.text()).trim()

                val epNumText = li.selectFirst(".epl-num")?.text() ?: epTitle
                val epNum = Regex("(?i)episode\\s*(\\d+)").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(?i)ep\\s*(\\d+)").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("\\d+").find(epNumText)?.value?.toIntOrNull()

                newEpisode(epHref) {
                    this.name = epTitle.ifBlank { "Episode $epNum" }
                    this.episode = epNum
                }
            }.distinctBy { it.data }.reversed()
        }

        var episodes = parseEpisodeGrid(document, url)

        if (episodes.isEmpty()) {
            val firstEpLink = document.selectFirst(
                ".epcurfirst a, .epcurlast a, .inepcx a, .bxcl a, a:matchesOwn((?i)watch)"
            )?.attr("href")
            val anyEpLink = document.select("a[href]").firstOrNull {
                (it.attr("href").contains("-episode-") || it.attr("href").contains("-ep-")) && it.attr("href").contains(mainUrl)
            }?.attr("href")

            val fallbackHref = fixUrlNull(firstEpLink ?: anyEpLink)

            if (fallbackHref != null) {
                val epDocument = app.get(fallbackHref).document
                episodes = parseEpisodeGrid(epDocument, fallbackHref)
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun fixRelativeUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> {
                runCatching {
                    val uri = URI(baseUrl)
                    "${uri.scheme}://${uri.host}$trimmed"
                }.getOrNull() ?: trimmed
            }
            else -> {
                runCatching {
                    val uri = URI(baseUrl)
                    val path = uri.path.substringBeforeLast("/", "")
                    "${uri.scheme}://${uri.host}$path/${trimmed.removePrefix("./")}"
                }.getOrNull() ?: trimmed
            }
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS – Dailymotion + Multi Player (rpmstream) + fallback
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val pageHtml = document.html()
        var found = false

        // ---------------------------------------------------------------
        // 1) Default embedded Dailymotion player. Don't return early even
        // if a match is found — the video may have been deleted, in which
        // case loadExtractor silently produces nothing and we must still
        // fall through to try the other mirror options below.
        // ---------------------------------------------------------------
        val dmRegex = Regex("""geo\.dailymotion\.com/player\.html\?video=([a-zA-Z0-9]+)""")
        val dmMatch = dmRegex.find(pageHtml)
        if (dmMatch != null) {
            val videoId = dmMatch.groupValues[1]
            try {
                loadExtractor(
                    url = "https://www.dailymotion.com/video/$videoId",
                    referer = data,
                    callback = callback,
                    subtitleCallback = subtitleCallback
                )
                found = true
            } catch (_: Exception) { }
        }

        // ---------------------------------------------------------------
        // 2) Walk every mirror <option value="..."> — don't stop at the
        // first one, so Multi Player (or any other server) still gets a
        // chance even if Dailymotion's video was deleted.
        // ---------------------------------------------------------------
        document.select("option[value]").forEach { option ->
            val value = option.attr("value")
            if (value.isBlank()) return@forEach

            try {
                val decoded = String(Base64.decode(value, Base64.DEFAULT))
                val src = Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(decoded)?.groupValues?.get(1)
                    ?: Regex("""embedUrl["']?\s*content=["']([^"']+)["']""")
                        .find(decoded)?.groupValues?.get(1)

                if (src.isNullOrBlank()) return@forEach

                when {
                    // Dailymotion referenced via an option payload
                    src.contains("dailymotion") -> {
                        val vid = Regex("""(?:video/|video=|embed/)([a-zA-Z0-9_]+)""")
                            .find(src)?.groupValues?.get(1)
                        if (vid != null) {
                            try {
                                loadExtractor(
                                    "https://www.dailymotion.com/video/$vid",
                                    data,
                                    subtitleCallback,
                                    callback
                                )
                                found = true
                            } catch (_: Exception) { }
                        }
                    }

                    // rpmstream.live SPA player — the URL uses a fragment
                    // (#id), which is never sent to the server on a plain
                    // GET. The real manifest comes from its API instead.
                    // NOTE: its api/v1/video response is encrypted binary,
                    // not JSON — no subtitle tracks can be recovered from
                    // it, only the video stream via the master.m3u8 chain.
                    src.contains("rpmstream.live") -> {
                        val fragmentId = src.substringAfterLast("#").ifBlank { null }
                        if (fragmentId != null) {
                            try {
                                val apiUrl = "https://chiki.rpmstream.live/api/v1/video" +
                                    "?id=$fragmentId&w=1280&h=800&r=chikianimation.online"
                                val apiResponse = app.get(
                                    apiUrl,
                                    headers = mapOf("Referer" to data)
                                ).text

                                val m3u8Url = Regex(""""(?:url|file|src|m3u8|playlist)"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
                                    .find(apiResponse)?.groupValues?.get(1)
                                    ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                                        .find(apiResponse)?.value

                                if (!m3u8Url.isNullOrBlank()) {
                                    val cleanUrl = m3u8Url.replace("\\/", "/")
                                    M3u8Helper.generateM3u8("Multi Player", cleanUrl, apiUrl).forEach {
                                        callback(it)
                                    }
                                    found = true
                                }
                            } catch (_: Exception) { }
                        }
                    }

                    // Any other custom host — original fallback logic
                    else -> {
                        try {
                            val res = app.get(src, headers = mapOf("Referer" to data))
                            val doc = res.document

                            doc.select("track").forEach { track ->
                                val trackSrc = track.attr("src").ifBlank { track.attr("data-src") }
                                val label = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Subtitle" }
                                val subUrl = fixRelativeUrl(trackSrc, src)
                                if (!subUrl.isNullOrBlank()) {
                                    subtitleCallback(SubtitleFile(label, subUrl))
                                }
                            }

                            var foundStream = false
                            doc.select("source").forEach { source ->
                                val streamUrl = fixRelativeUrl(source.attr("src"), src)
                                if (!streamUrl.isNullOrBlank() && (streamUrl.contains(".m3u8") || streamUrl.contains(".txt") || source.attr("type").contains("mpegurl", true))) {
                                    M3u8Helper.generateM3u8("Multi Player", streamUrl, src).forEach { callback(it) }
                                    foundStream = true
                                }
                            }

                            if (!foundStream) {
                                Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.txt)(?:\?[^\s"'<>]*)?""").findAll(res.text).forEach { match ->
                                    M3u8Helper.generateM3u8("Multi Player", match.value, src).forEach { callback(it) }
                                    foundStream = true
                                }
                            }
                            if (foundStream) found = true
                        } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) { }
        }

        return found
    }
}
