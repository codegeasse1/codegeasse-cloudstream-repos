package com.chikianimation

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
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

    companion object {
        private const val TAG = "ChikiAnimation"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?status=&type=&order=update" to "Latest Release",
        "$mainUrl/anime/?status=&type=&order=popular" to "Popular",
        "$mainUrl/anime/?status=completed&type=&order=update" to "Completed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data
        else request.data.replace("?", "page/$page/?")
        val document = app.get(url).document
        val home = document.select("article.bs > div.bsx").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("a") ?: return null
        val rawHref = fixUrlNull(linkEl.attr("href")) ?: return null
        val href = rawHref
            .replace(Regex("-(episode|ep)-\\d+-[a-zA-Z0-9-]+/?$"), "")
            .let {
                if (it.contains("/anime/")) it
                else "$mainUrl/anime/${it.substringAfterLast("/")}"
            }

        val title = linkEl.attr("title").ifBlank {
            this.selectFirst("div.tt")?.text()
        }?.trim() ?: return null

        val rawPoster =
            this.selectFirst("img")?.attr("data-lazy-src")?.ifBlank { null }
                ?: this.selectFirst("img")?.attr("data-src")?.ifBlank { null }
                ?: this.selectFirst("img")?.attr("src")

        val posterUrl = fixUrlNull(
            rawPoster?.substringBefore("?")
                ?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://")
        )
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.bs > div.bsx").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("(?i)(episode|ep)\\s*\\d+.*"), "") ?: ""

        val posterElement = document.selectFirst(
            ".bigcontent .thumb img, .bixbox .thumb img, " +
                    "article .thumb img, .infox .imgbox img, .ts-post-image"
        )
        val rawPoster =
            posterElement?.attr("data-lazy-src")?.ifBlank { null }
                ?: posterElement?.attr("data-src")?.ifBlank { null }
                ?: posterElement?.attr("src")

        var poster = fixUrlNull(
            rawPoster?.substringBefore("?")
                ?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://")
        )
        if (poster.isNullOrBlank()) {
            val og = document.selectFirst("meta[property=og:image]")?.attr("content")
            if (og != null && !og.contains("logo", true) && !og.contains("banner", true))
                poster = fixUrlNull(og)
        }

        val synopsis = document.selectFirst(
            ".entry-content, .synp .entry-content, #synopsis, .desc"
        )?.text()
        val genres = document.select("a[href*=/genres/], .genxed a").map { it.text() }

        fun parseEpisodeGrid(
            doc: org.jsoup.nodes.Document,
            currentUrl: String
        ): List<Episode> {
            return doc.select(
                "div.eplister ul li, div.episodelist ul li, " +
                        "ul.episodelist li, div.ep_list ul li, .bixbox.bxcl ul li"
            ).mapNotNull { li ->
                val epLink = li.selectFirst("a")
                val epHref: String = when {
                    epLink != null && epLink.hasAttr("href") ->
                        fixUrlNull(epLink.attr("href")) ?: return@mapNotNull null
                    li.select("div.playinfo").isNotEmpty() -> currentUrl
                    else -> return@mapNotNull null
                }

                val epTitle = (
                        epLink?.attr("title")?.ifBlank { epLink.text() } ?: li.text()
                        ).trim()

                val epNumText = li.selectFirst(".epl-num")?.text() ?: epTitle
                val epNum: Int? =
                    Regex("(?i)episode\\s*(\\d+)").find(epNumText)
                        ?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("(?i)ep\\s*(\\d+)").find(epNumText)
                            ?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("\\d+").find(epNumText)?.value?.toIntOrNull()

                newEpisode(epHref) {
                    this.name = epTitle.ifBlank { "Episode $epNum" }
                    this.episode = epNum
                }
            }.distinctBy { it.data }.reversed()
        }

        var episodes = parseEpisodeGrid(document, url)

        if (episodes.isEmpty()) {
            val firstEpLink = document
                .selectFirst(".epcurfirst a, .epcurlast a, .inepcx a, .bxcl a")
                ?.attr("href")
            val anyEpLink = document.select("a[href]").firstOrNull {
                (it.attr("href").contains("-episode-") ||
                        it.attr("href").contains("-ep-")) &&
                        it.attr("href").contains(mainUrl)
            }?.attr("href")

            fixUrlNull(firstEpLink ?: anyEpLink)?.let { fallback ->
                episodes = parseEpisodeGrid(app.get(fallback).document, fallback)
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "=== loadLinks: $data ===")

        val response = app.get(
            data,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Referer" to mainUrl
            )
        )
        val document = response.document
        val pageHtml = response.text

        val embedUrls = mutableSetOf<String>()

        fun addUrl(raw: String?) {
            if (raw.isNullOrBlank()) return
            val u = raw.trim()
            when {
                u.startsWith("http://") || u.startsWith("https://") -> embedUrls.add(u)
                u.startsWith("//") -> embedUrls.add("https:$u")
            }
        }

        fun processRawValue(str: String) {
            if (str.isBlank()) return
            addUrl(str)

            // Base64 Decode
            val decoded = runCatching {
                String(Base64.decode(str, Base64.DEFAULT))
            }.getOrNull()

            val textToScan = decoded ?: str
            Regex("""(?:src|href)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(textToScan).forEach { m -> addUrl(m.groupValues[1]) }
        }

        // 1. Dropdown options parsing
        document.select("option").forEach { option ->
            processRawValue(option.attr("value"))
            listOf("data-embed", "data-src", "data-link", "data-url").forEach { attr ->
                processRawValue(option.attr(attr))
            }
        }

        // 2. Fetch AJAX Servers
        document.select("[data-post][data-nume]").forEach { el ->
            val postId = el.attr("data-post")
            val nume = el.attr("data-nume")
            val type = el.attr("data-type")
            if (postId.isNotBlank() && nume.isNotBlank()) {
                runCatching {
                    val ajaxRes = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                            "Referer" to data
                        ),
                        data = mapOf(
                            "action" to "player_ajax",
                            "post" to postId,
                            "nume" to nume,
                            "type" to type
                        )
                    ).text
                    processRawValue(ajaxRes)
                }
            }
        }

        // 3. Attributes parsing
        document.allElements.forEach { el ->
            listOf("data-embed", "data-src", "data-lazy-src", "data-original", "data-url", "data-video", "data-link").forEach { attr ->
                val v = el.attr(attr)
                if (v.isNotBlank()) processRawValue(v)
            }
        }

        // 4. Iframes parsing
        document.select("iframe").forEach { iframe ->
            listOf("src", "data-src", "data-lazy-src", "data-original").forEach { attr ->
                val v = iframe.attr(attr)
                if (v.isNotBlank()) addUrl(v)
            }
        }

        // 5. Full HTML Regex Scan
        Regex("""<iframe[^>]+(?:src|data-src)=["']([^"'<>]+)["']""", RegexOption.IGNORE_CASE)
            .findAll(pageHtml).forEach { m -> addUrl(m.groupValues[1]) }

        Log.d(TAG, "Collected ${embedUrls.size} embed URL(s)")

        var found = false

        for (raw in embedUrls) {
            val url = fixUrlNull(raw) ?: continue
            Log.d(TAG, "Processing server URL: $url")

            when {
                // Dailymotion
                url.contains("dailymotion.com") || url.contains("geo.dailymotion") -> {
                    val videoId = Regex("""(?:video/|video=|embed/|/video/)([a-zA-Z0-9]+)""")
                        .find(url)?.groupValues?.get(1)

                    if (videoId != null) {
                        val ok = invokeDailymotion(videoId, subtitleCallback, callback)
                        if (ok) found = true
                    }
                }

                // Custom Multi Player Server (rpmstream, organicgoods, etc.)
                url.contains("rpmstream") || url.contains("organicgoods") || url.contains("chiki.") -> {
                    val ok = extractCustomPlayer(url, subtitleCallback, callback)
                    if (ok) found = true
                }

                // Standard Extractors + Fallback
                else -> {
                    var extracted = false
                    try {
                        loadExtractor(url, data, subtitleCallback, callback)
                        extracted = true
                        found = true
                    } catch (_: Exception) {
                        extracted = false
                    }

                    if (!extracted) {
                        val ok = extractCustomPlayer(url, subtitleCallback, callback)
                        if (ok) found = true
                    }
                }
            }
        }

        return found
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

    // Custom Player Extractor (Handles rpmstream.live, organicgoods.cfd, and embedded subtitled players)
    private suspend fun extractCustomPlayer(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val res = app.get(
                iframeUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Referer" to mainUrl
                )
            )
            val doc = res.document
            val html = res.text

            // Subtitles from <track> tags
            doc.select("track").forEach { track ->
                val trackSrc = track.attr("src").ifBlank { track.attr("data-src") }
                val label = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Subtitle" }
                val subUrl = fixRelativeUrl(trackSrc, iframeUrl)
                if (!subUrl.isNullOrBlank()) {
                    subtitleCallback(SubtitleFile(label, subUrl))
                }
            }

            // Streams from <source> tags
            doc.select("source").forEach { source ->
                val streamUrl = fixRelativeUrl(source.attr("src"), iframeUrl)
                val type = source.attr("type")
                if (!streamUrl.isNullOrBlank()) {
                    if (type.contains("mpegurl", true) || streamUrl.contains(".m3u8") || streamUrl.contains(".txt") || streamUrl.contains("master")) {
                        M3u8Helper.generateM3u8(
                            source = "Multi Player",
                            streamUrl = streamUrl,
                            referer = iframeUrl
                        ).forEach { link ->
                            callback(link)
                            found = true
                        }
                    } else {
                        callback(
                            ExtractorLink(
                                source = "Multi Player",
                                name = "Multi Player",
                                url = streamUrl,
                                referer = iframeUrl,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        found = true
                    }
                }
            }

            // Fallback Regex for embedded M3U8 / .TXT playlists
            if (!found) {
                val mediaRegex = Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.txt)(?:\?[^\s"'<>]*)?""")
                mediaRegex.findAll(html).forEach { match ->
                    val streamUrl = match.value
                    M3u8Helper.generateM3u8(
                        source = "Multi Player",
                        streamUrl = streamUrl,
                        referer = iframeUrl
                    ).forEach { link ->
                        callback(link)
                        found = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting custom player $iframeUrl: ${e.message}")
        }
        return found
    }

    // Robust Multi-tier Dailymotion Resolver
    private suspend fun invokeDailymotion(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Referer" to "https://www.dailymotion.com/"
        )

        fun addM3u8(m3u8Url: String): Boolean {
            var added = false
            val cleanUrl = m3u8Url.replace("\\/", "/")
            runCatching {
                M3u8Helper.generateM3u8(
                    source = "Dailymotion",
                    streamUrl = cleanUrl,
                    referer = "https://www.dailymotion.com/"
                ).forEach { link ->
                    callback(link)
                    added = true
                }
            }
            return added
        }

        // 1. Metadata API
        runCatching {
            val metaUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val json = app.get(metaUrl, headers = headers).text
            Regex("""https?:\\?/\\?/[^"]+\.m3u8[^"]*""").findAll(json).forEach { match ->
                if (addM3u8(match.value)) found = true
            }
        }
        if (found) return true

        // 2. Geo Metadata API
        runCatching {
            val geoMetaUrl = "https://geo.dailymotion.com/player/metadata/video/$videoId"
            val json = app.get(geoMetaUrl, headers = headers).text
            Regex("""https?:\\?/\\?/[^"]+\.m3u8[^"]*""").findAll(json).forEach { match ->
                if (addM3u8(match.value)) found = true
            }
        }
        if (found) return true

        // 3. Embed HTML Scraping
        runCatching {
            val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
            val html = app.get(embedUrl, headers = headers).text
            Regex("""https?:\\?/\\?/[^"]+\.m3u8[^"]*""").findAll(html).forEach { match ->
                if (addM3u8(match.value)) found = true
            }
        }
        if (found) return true

        // 4. Cloudstream Extractor Fallback
        runCatching {
            loadExtractor("https://www.dailymotion.com/embed/video/$videoId", subtitleCallback, callback)
            found = true
        }

        return found
    }
}
