package com.animekhor

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class AnimeKhorProvider : MainAPI() {

    override var mainUrl = "https://animekhor.org"
    override var name = "AnimeKhor"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?status=&type=&order=update" to "Latest Release",
        "$mainUrl/anime/?status=&type=&order=popular" to "Popular",
        "$mainUrl/anime/?status=completed&type=&order=update" to "Completed",
    )

    // ============================================================
    // MAIN PAGE
    // ============================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page == 1) {
            request.data
        } else {
            request.data.replace("?", "page/$page/?")
        }

        val document = app.get(url).document

        val homeItems = mutableListOf<SearchResponse>()

        for (element in document.select("article.bs > div.bsx")) {
            val item = element.toSearchResult()

            if (item != null) {
                homeItems.add(item)
            }
        }

        return newHomePageResponse(
            request.name,
            homeItems.distinctBy { it.url }
        )
    }

    // ============================================================
    // SEARCH RESULT PARSER
    // ============================================================

    private fun Element.toSearchResult(): SearchResponse? {

        val linkEl = this.selectFirst("a") ?: return null

        val rawHref =
            fixUrlNull(linkEl.attr("href"))
                ?.trimEnd('/')
                ?: return null

        val href =
            rawHref
                .replace(
                    Regex("-(episode|ep)-\\d+.*$"),
                    ""
                )
                .let {
                    if (it.contains("/anime/")) {
                        it
                    } else {
                        "$mainUrl/anime/${it.substringAfterLast("/")}/"
                    }
                }

        val title =
            linkEl.attr("title")
                .ifBlank {
                    this.selectFirst("div.tt")?.text()
                }
                ?.trim()
                ?: return null

        val image =
            this.selectFirst("img")

        val rawPoster =
            image?.attr("data-lazy-src")
                ?.ifBlank { null }
                ?: image?.attr("data-src")
                    ?.ifBlank { null }
                ?: image?.attr("src")

        val posterUrl =
            fixUrlNull(
                rawPoster
                    ?.substringBefore("?")
                    ?.replace(
                        Regex("https?://i\\d+\\.wp\\.com/"),
                        "https://"
                    )
            )

        return newAnimeSearchResponse(
            title,
            href,
            TvType.Anime
        ) {
            this.posterUrl = posterUrl
        }
    }

    // ============================================================
    // SEARCH
    // ============================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val searchResults =
            mutableListOf<SearchResponse>()

        val targetLimit = 100
        var page = 1

        while (searchResults.size < targetLimit) {

            val url =
                if (page == 1) {
                    "$mainUrl/?s=$query"
                } else {
                    "$mainUrl/page/$page/?s=$query"
                }

            try {

                val document =
                    app.get(url).document

                val elements =
                    document.select("article.bs > div.bsx")

                if (elements.isEmpty()) {
                    break
                }

                for (element in elements) {

                    val item =
                        element.toSearchResult()

                    if (item != null) {
                        searchResults.add(item)
                    }
                }

                page++

            } catch (e: Exception) {
                break
            }
        }

        return searchResults
            .distinctBy { it.url }
            .take(targetLimit)
    }

    // ============================================================
    // LOAD ANIME
    // ============================================================

    override suspend fun load(
        url: String
    ): LoadResponse {

        val document =
            app.get(url).document

        val title =
            document
                .selectFirst("h1.entry-title, h1")
                ?.text()
                ?.trim()
                ?.replace(
                    Regex("(?i)(episode|ep)\\s*\\d+.*"),
                    ""
                )
                ?: ""

        // --------------------------------------------------------
        // POSTER
        // --------------------------------------------------------

        val posterElement =
            document.selectFirst(
                ".bigcontent .thumb img, " +
                ".bixbox .thumb img, " +
                "article .thumb img, " +
                ".infox .imgbox img, " +
                ".ts-post-image"
            )

        val rawPoster =
            posterElement
                ?.attr("data-lazy-src")
                ?.ifBlank { null }
                ?: posterElement
                    ?.attr("data-src")
                    ?.ifBlank { null }
                ?: posterElement
                    ?.attr("src")

        var poster =
            fixUrlNull(
                rawPoster
                    ?.substringBefore("?")
                    ?.replace(
                        Regex("https?://i\\d+\\.wp\\.com/"),
                        "https://"
                    )
            )

        if (poster.isNullOrBlank()) {

            val ogImage =
                document
                    .selectFirst(
                        "meta[property=og:image]"
                    )
                    ?.attr("content")

            if (
                ogImage != null &&
                !ogImage.contains("logo", true) &&
                !ogImage.contains("banner", true)
            ) {
                poster = fixUrlNull(ogImage)
            }
        }

        // --------------------------------------------------------
        // DESCRIPTION
        // --------------------------------------------------------

        val synopsis =
            document
                .selectFirst(
                    ".entry-content, " +
                    ".synp .entry-content, " +
                    "#synopsis, " +
                    ".desc"
                )
                ?.text()

        // --------------------------------------------------------
        // GENRES
        // --------------------------------------------------------

        val genres =
            document
                .select(
                    "a[href*=/genres/], .genxed a"
                )
                .map {
                    it.text()
                }

        // --------------------------------------------------------
        // EPISODE PARSER
        // --------------------------------------------------------

        fun parseEpisodeGrid(
            doc: org.jsoup.nodes.Document,
            currentUrl: String
        ): List<Episode> {

            val epList =
                mutableListOf<Episode>()

            val selectors =
                "div.eplister ul li, " +
                "div.episodelist ul li, " +
                "ul.episodelist li, " +
                "div.ep_list ul li, " +
                ".bixbox.bxcl ul li"

            for (li in doc.select(selectors)) {

                val epLink =
                    li.selectFirst("a")

                val epHref =
                    if (
                        epLink != null &&
                        epLink.hasAttr("href")
                    ) {

                        fixUrlNull(
                            epLink.attr("href")
                        )

                    } else if (
                        li.hasClass("selected") ||
                        li.hasAttr("selected") ||
                        li.select("div.playinfo").isNotEmpty()
                    ) {

                        currentUrl

                    } else {
                        continue
                    }

                if (epHref == null) {
                    continue
                }

                val epTitle =
                    (
                        epLink
                            ?.attr("title")
                            ?.ifBlank {
                                epLink.text()
                            }
                            ?: li.text()
                    ).trim()

                val epNumText =
                    li.selectFirst(".epl-num")
                        ?.text()
                        ?: epTitle

                val epNum =
                    Regex(
                        "(?i)episode\\s*(\\d+)"
                    )
                        .find(epNumText)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                        ?: Regex(
                            "(?i)ep\\s*(\\d+)"
                        )
                            .find(epNumText)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                        ?: Regex("\\d+")
                            .find(epNumText)
                            ?.value
                            ?.toIntOrNull()

                epList.add(
                    newEpisode(epHref) {

                        this.name =
                            epTitle.ifBlank {
                                "Episode $epNum"
                            }

                        this.episode = epNum
                    }
                )
            }

            return epList
                .distinctBy { it.data }
                .reversed()
        }

        var episodes =
            parseEpisodeGrid(
                document,
                url
            )

        // --------------------------------------------------------
        // FALLBACK EPISODE
        // --------------------------------------------------------

        if (episodes.isEmpty()) {

            val firstEpLink =
                document
                    .selectFirst(
                        ".epcurfirst a, " +
                        ".epcurlast a, " +
                        ".inepcx a, " +
                        ".bxcl a, " +
                        "a:matchesOwn((?i)watch)"
                    )
                    ?.attr("href")

            val anyEpLink =
                document
                    .select("a[href]")
                    .firstOrNull {

                        (
                            it.attr("href")
                                .contains("-episode-")
                            ||
                            it.attr("href")
                                .contains("-ep-")
                        )
                        &&
                        it.attr("href")
                            .contains(mainUrl)
                    }
                    ?.attr("href")

            val fallbackHref =
                fixUrlNull(
                    firstEpLink ?: anyEpLink
                )

            if (fallbackHref != null) {

                val epDocument =
                    app.get(fallbackHref).document

                episodes =
                    parseEpisodeGrid(
                        epDocument,
                        fallbackHref
                    )
            }
        }

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {

            this.posterUrl = poster
            this.plot = synopsis
            this.tags = genres

            addEpisodes(
                DubStatus.Subbed,
                episodes
            )
        }
    }

    // ============================================================
    // URL FIXER
    // ============================================================

    private fun fixRelativeUrl(
        url: String?,
        baseUrl: String
    ): String? {

        if (url.isNullOrBlank()) {
            return null
        }

        val trimmed =
            url.trim()

        return when {

            trimmed.startsWith("http://") ||
            trimmed.startsWith("https://") -> {
                trimmed
            }

            trimmed.startsWith("//") -> {
                "https:$trimmed"
            }

            trimmed.startsWith("/") -> {

                runCatching {

                    val uri =
                        URI(baseUrl)

                    "${uri.scheme}://${uri.host}$trimmed"

                }.getOrNull()
                    ?: trimmed
            }

            else -> {

                runCatching {

                    val uri =
                        URI(baseUrl)

                    val path =
                        uri.path
                            .substringBeforeLast("/", "")

                    "${uri.scheme}://${uri.host}$path/${trimmed.removePrefix("./")}"

                }.getOrNull()
                    ?: trimmed
            }
        }
    }

    // ============================================================
    // BASE64 DECODER
    // ============================================================

    private fun decodeBase64(
        value: String
    ): String? {

        return try {

            val cleaned =
                value
                    .trim()
                    .replace("\\/", "/")

            String(
                Base64.decode(
                    cleaned,
                    Base64.DEFAULT
                ),
                Charsets.UTF_8
            ).trim()

        } catch (e: Exception) {
            null
        }
    }

    // ============================================================
    // EXTRACT URL FROM HTML
    // ============================================================

    private fun extractIframeUrl(
        html: String
    ): String? {

        try {

            val parsed =
                Jsoup.parse(html)

            val iframe =
                parsed
                    .selectFirst("iframe[src]")

            if (iframe != null) {

                val src =
                    iframe
                        .attr("src")
                        .trim()

                if (src.isNotBlank()) {
                    return src
                }
            }

        } catch (e: Exception) {
        }

        return Regex(
            """(?i)<iframe[^>]+src\s*=\s*["']([^"']+)["']"""
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
    }

    // ============================================================
    // ABYSS URL EXTRACTION
    // ============================================================

    private fun extractAbyssVideoUrl(
        html: String
    ): String? {

        // --------------------------------------------------------
        // Direct MP4
        // --------------------------------------------------------

        val directMp4 =
            Regex(
                """https?://[^\s"'<>\\]+\.mp4(?:\?[^\s"'<>\\]*)?"""
            )
                .find(html)
                ?.value

        if (!directMp4.isNullOrBlank()) {
            return directMp4
        }

        // --------------------------------------------------------
        // M3U8
        // --------------------------------------------------------

        val directM3u8 =
            Regex(
                """https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?"""
            )
                .find(html)
                ?.value

        if (!directM3u8.isNullOrBlank()) {
            return directM3u8
        }

        // --------------------------------------------------------
        // Common video configuration formats
        // --------------------------------------------------------

        val patterns =
            listOf(

                Regex(
                    """(?i)(?:file|source|src|video|url)\s*[:=]\s*["']([^"']+)["']"""
                ),

                Regex(
                    """(?i)(?:file|source|src|video|url)\s*[:=]\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']"""
                ),

                Regex(
                    """(?i)file\s*:\s*["']([^"']+)["']"""
                ),

                Regex(
                    """(?i)source\s*:\s*["']([^"']+)["']"""
                ),

                Regex(
                    """(?i)src\s*:\s*["']([^"']+)["']"""
                ),

                Regex(
                    """(?i)videoUrl\s*[:=]\s*["']([^"']+)["']"""
                ),

                Regex(
                    """(?i)video_url\s*[:=]\s*["']([^"']+)["']"""
                ),

                Regex(
                    """(?i)streamUrl\s*[:=]\s*["']([^"']+)["']"""
                ),

                Regex(
                    """(?i)stream_url\s*[:=]\s*["']([^"']+)["']"""
                )
            )

        for (pattern in patterns) {

            val match =
                pattern.find(html)

            if (match != null) {

                var found =
                    match.groupValues
                        .getOrNull(1)
                        ?.trim()

                if (!found.isNullOrBlank()) {

                    found =
                        found
                            .replace("\\/", "/")
                            .replace("\\u0026", "&")

                    if (
                        found.startsWith("http://") ||
                        found.startsWith("https://")
                    ) {
                        return found
                    }
                }
            }
        }

        // --------------------------------------------------------
        // JSON-style escaped URL
        // --------------------------------------------------------

        val escaped =
            Regex(
                """https?:\\?/\\?/[^"'\\\s]+?\.(?:mp4|m3u8)[^"'\\\s]*"""
            )
                .find(html)
                ?.value

        if (!escaped.isNullOrBlank()) {

            return escaped
                .replace("\\/", "/")
                .replace("\\u0026", "&")
        }

        return null
    }

    // ============================================================
    // LOAD LINKS
    // ============================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document =
            app.get(
                data,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            ).document

        var found = false

        val embedLinks =
            mutableListOf<Pair<String, String>>()

        // --------------------------------------------------------
        // ADD EMBED
        // --------------------------------------------------------

        fun addEmbed(
            rawUrl: String?,
            name: String
        ) {

            if (rawUrl.isNullOrBlank()) {
                return
            }

            var url =
                rawUrl
                    .trim()
                    .replace("\\/", "/")
                    .replace("&amp;", "&")

            if (url.startsWith("//")) {
                url = "https:$url"
            }

            if (
                url.startsWith("http://") ||
                url.startsWith("https://")
            ) {

                if (
                    embedLinks.none {
                        it.first == url
                    }
                ) {

                    embedLinks.add(
                        Pair(
                            url,
                            name
                        )
                    )
                }
            }
        }

        // ========================================================
        // MIRROR SELECTOR
        // ========================================================

        val mirrorOptions =
            document.select(
                "select.mirror option[value]"
            )

        for (element in mirrorOptions) {

            val value =
                element
                    .attr("value")
                    .trim()

            val label =
                element
                    .text()
                    .trim()
                    .ifBlank {
                        "Server"
                    }

            if (value.isBlank()) {
                continue
            }

            // ----------------------------------------------------
            // Direct URL
            // ----------------------------------------------------

            if (
                value.startsWith("http://") ||
                value.startsWith("https://") ||
                value.startsWith("//")
            ) {

                addEmbed(
                    value,
                    label
                )

                continue
            }

            // ----------------------------------------------------
            // Base64 iframe
            // ----------------------------------------------------

            val decoded =
                decodeBase64(value)

            if (!decoded.isNullOrBlank()) {

                // Find iframe
                val iframeSrc =
                    extractIframeUrl(decoded)

                if (!iframeSrc.isNullOrBlank()) {

                    addEmbed(
                        iframeSrc,
                        label
                    )

                } else {

                    // Find direct URL
                    val directUrl =
                        Regex(
                            """https?://[^\s"'<>]+"""
                        )
                            .find(decoded)
                            ?.value

                    if (!directUrl.isNullOrBlank()) {

                        addEmbed(
                            directUrl,
                            label
                        )
                    }
                }
            }
        }

        // ========================================================
        // NORMAL PAGE IFRAMES
        // ========================================================

        val iframes =
            document.select(
                ".player-embed iframe, " +
                "#embed_holder iframe, " +
                "#pembed iframe, " +
                "iframe"
            )

        for (iframe in iframes) {

            val src =
                iframe
                    .attr("src")
                    .ifBlank {
                        iframe.attr("data-src")
                    }
                    .ifBlank {
                        iframe.attr("data-lazy-src")
                    }

            addEmbed(
                src,
                "Default Player"
            )
        }

        // ========================================================
        // PROCESS ALL SERVERS
        // ========================================================

        for ((embedUrl, serverLabel) in embedLinks) {

            try {

                // =================================================
                // A. ABYSS PLAYER
                // =================================================

                if (
                    embedUrl.contains(
                        "abyssplayer.com",
                        true
                    ) ||
                    serverLabel.contains(
                        "abyss",
                        true
                    )
                ) {

                    val abyssHeaders =
                        mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "$mainUrl/"
                        )

                    try {

                        val abyssResponse =
                            app.get(
                                embedUrl,
                                headers = abyssHeaders,
                                allowRedirects = true
                            )

                        val abyssHtml =
                            abyssResponse.text

                        // -----------------------------------------
                        // Try direct video extraction
                        // -----------------------------------------

                        var videoUrl =
                            extractAbyssVideoUrl(
                                abyssHtml
                            )

                        if (!videoUrl.isNullOrBlank()) {

                            videoUrl =
                                videoUrl
                                    .replace(
                                        "\\/",
                                        "/"
                                    )
                                    .replace(
                                        "\\u0026",
                                        "&"
                                    )

                            if (
                                videoUrl.contains(
                                    ".m3u8",
                                    true
                                )
                            ) {

                                M3u8Helper
                                    .generateM3u8(
                                        "AbyssPlayer",
                                        videoUrl,
                                        embedUrl,
                                        headers = abyssHeaders
                                    )
                                    .forEach { link ->

                                        callback(link)
                                        found = true
                                    }

                            } else {

                                callback(
                                    ExtractorLink(
                                        source = "AbyssPlayer",
                                        name = "AbyssPlayer",
                                        url = videoUrl,
                                        referer = embedUrl,
                                        quality = Qualities.Unknown.value,
                                        type = ExtractorLinkType.VIDEO,
                                        headers = abyssHeaders,
                                        extractorData = ""
                                    )
                                )

                                found = true
                            }
                        }

                        // -----------------------------------------
                        // Look for packed/embedded iframe
                        // -----------------------------------------

                        if (!found) {

                            val nestedIframe =
                                extractIframeUrl(
                                    abyssHtml
                                )

                            if (
                                !nestedIframe.isNullOrBlank() &&
                                nestedIframe != embedUrl
                            ) {

                                val nestedLinks =
                                    mutableListOf<ExtractorLink>()

                                if (
                                    loadExtractor(
                                        nestedIframe,
                                        embedUrl,
                                        subtitleCallback
                                    ) {
                                        nestedLinks.add(it)
                                    }
                                ) {

                                    for (link in nestedLinks) {

                                        callback(
                                            ExtractorLink(
                                                source = "AbyssPlayer",
                                                name =
                                                    "AbyssPlayer (${link.name})",
                                                url = link.url,
                                                referer = link.referer,
                                                quality = link.quality,
                                                type = link.type,
                                                headers = link.headers,
                                                extractorData =
                                                    link.extractorData
                                            )
                                        )

                                        found = true
                                    }
                                }
                            }
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // ---------------------------------------------
                    // Also try CloudStream's registered extractors
                    // ---------------------------------------------

                    if (!found) {

                        try {

                            val abyssLinks =
                                mutableListOf<ExtractorLink>()

                            if (
                                loadExtractor(
                                    embedUrl,
                                    data,
                                    subtitleCallback
                                ) {
                                    abyssLinks.add(it)
                                }
                            ) {

                                for (link in abyssLinks) {

                                    callback(
                                        ExtractorLink(
                                            source = "AbyssPlayer",
                                            name =
                                                "AbyssPlayer (${link.name})",
                                            url = link.url,
                                            referer = link.referer,
                                            quality = link.quality,
                                            type = link.type,
                                            headers = link.headers,
                                            extractorData =
                                                link.extractorData
                                        )
                                    )

                                    found = true
                                }
                            }

                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    // Don't let the generic extractor process Abyss again
                    continue
                }

                // =================================================
                // B. DAILYMOTION
                // =================================================

                if (
                    embedUrl.contains(
                        "dailymotion.com",
                        true
                    ) ||
                    embedUrl.contains(
                        "geo.dailymotion",
                        true
                    )
                ) {

                    val vidId =
                        Regex(
                            """video/([a-zA-Z0-9_]+)"""
                        )
                            .find(embedUrl)
                            ?.groupValues
                            ?.get(1)
                            ?: Regex(
                                """video=([a-zA-Z0-9_]+)"""
                            )
                                .find(embedUrl)
                                ?.groupValues
                                ?.get(1)

                    if (vidId != null) {

                        val tempLinks =
                            mutableListOf<ExtractorLink>()

                        if (
                            loadExtractor(
                                "https://www.dailymotion.com/video/$vidId",
                                data,
                                subtitleCallback
                            ) {
                                tempLinks.add(it)
                            }
                        ) {

                            for (link in tempLinks) {

                                callback(
                                    ExtractorLink(
                                        source =
                                            if (serverLabel.isNotBlank())
                                                serverLabel
                                            else
                                                "VidPlayer",

                                        name =
                                            if (serverLabel.isNotBlank())
                                                "$serverLabel (${link.name})"
                                            else
                                                link.name,

                                        url = link.url,
                                        referer = link.referer,
                                        quality = link.quality,
                                        type = link.type,
                                        headers = link.headers,
                                        extractorData =
                                            link.extractorData
                                    )
                                )
                            }

                            found = true
                            continue
                        }

                        // Direct Dailymotion M3U8 fallback
                        try {

                            val metaJson =
                                app.get(
                                    "https://www.dailymotion.com/player/metadata/video/$vidId",
                                    headers = mapOf(
                                        "User-Agent" to USER_AGENT
                                    )
                                ).text

                            val m3u8Url =
                                Regex(
                                    """https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"""
                                )
                                    .find(metaJson)
                                    ?.value

                            if (!m3u8Url.isNullOrBlank()) {

                                M3u8Helper
                                    .generateM3u8(
                                        if (serverLabel.isNotBlank())
                                            serverLabel
                                        else
                                            "VidPlayer",

                                        m3u8Url,

                                        "https://www.dailymotion.com/"
                                    )
                                    .forEach { link ->

                                        callback(link)
                                        found = true
                                    }

                                continue
                            }

                        } catch (e: Exception) {
                        }
                    }
                }

                // =================================================
                // C. RUMBLE
                // =================================================

                if (
                    embedUrl.contains(
                        "rumble",
                        true
                    )
                ) {

                    try {

                        val html =
                            app.get(
                                embedUrl,
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to data
                                )
                            ).text

                        val m3u8 =
                            Regex(
                                """https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"""
                            )
                                .find(html)
                                ?.value

                        if (!m3u8.isNullOrBlank()) {

                            M3u8Helper
                                .generateM3u8(
                                    "Rumble",
                                    m3u8,
                                    embedUrl
                                )
                                .forEach { link ->

                                    callback(link)
                                    found = true
                                }

                            continue
                        }

                    } catch (e: Exception) {
                    }
                }

                // =================================================
                // D. D.TUBE
                // =================================================

                if (
                    embedUrl.contains(
                        "d.tube",
                        true
                    )
                ) {

                    val vidId =
                        Regex(
                            """v=([a-zA-Z0-9-]+)"""
                        )
                            .find(embedUrl)
                            ?.groupValues
                            ?.get(1)
                            ?: embedUrl
                                .substringAfter(
                                    "/videos/"
                                )
                                .substringBefore("/")

                    if (vidId.isNotBlank()) {

                        try {

                            val m3u8 =
                                "https://nas2.d.tube/videos/$vidId/master.m3u8"

                            M3u8Helper
                                .generateM3u8(
                                    "DPlayer",
                                    m3u8,
                                    embedUrl
                                )
                                .forEach { link ->

                                    callback(link)
                                    found = true
                                }

                            continue

                        } catch (e: Exception) {
                        }
                    }
                }

                // =================================================
                // E. NATIVE CLOUDSTREAM EXTRACTORS
                // =================================================

                val tempLinks =
                    mutableListOf<ExtractorLink>()

                if (
                    loadExtractor(
                        embedUrl,
                        data,
                        subtitleCallback
                    ) {
                        tempLinks.add(it)
                    }
                ) {

                    for (link in tempLinks) {

                        val useServerName =
                            serverLabel.isNotBlank() &&
                            !serverLabel.contains(
                                "Default",
                                true
                            )

                        callback(
                            ExtractorLink(

                                source =
                                    if (useServerName)
                                        serverLabel
                                    else
                                        link.source,

                                name =
                                    if (useServerName)
                                        "$serverLabel (${link.name})"
                                    else
                                        link.name,

                                url = link.url,
                                referer = link.referer,
                                quality = link.quality,
                                type = link.type,
                                headers = link.headers,
                                extractorData =
                                    link.extractorData
                            )
                        )
                    }

                    found = true
                    continue
                }

                // =================================================
                // F. UPNS / P2PSTREAM
                // =================================================

                if (
                    embedUrl.contains(
                        "upns.live",
                        true
                    ) ||
                    embedUrl.contains(
                        "p2pstream.vip",
                        true
                    )
                ) {

                    try {

                        val host =
                            URI(embedUrl).host

                        if (!host.isNullOrBlank()) {

                            val id =
                                embedUrl
                                    .substringAfter("#")
                                    .substringAfterLast("/")

                            val apiCall =
                                "https://$host/api/v1/video?id=$id&w=1280&h=800&r=animekhor.org"

                            val apiRes =
                                app.get(
                                    apiCall,
                                    headers = mapOf(
                                        "Referer" to "$mainUrl/",
                                        "User-Agent" to USER_AGENT
                                    )
                                ).text

                            val m3u8 =
                                Regex(
                                    """https?://[^\s"'<>]+\.(?:m3u8|txt)[^\s"'<>]*"""
                                )
                                    .find(apiRes)
                                    ?.value

                            if (!m3u8.isNullOrBlank()) {

                                M3u8Helper
                                    .generateM3u8(
                                        serverLabel.ifBlank {
                                            "UPNS"
                                        },
                                        m3u8,
                                        "https://$host/"
                                    )
                                    .forEach { link ->

                                        callback(link)
                                        found = true
                                    }
                            }
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return found
    }
}