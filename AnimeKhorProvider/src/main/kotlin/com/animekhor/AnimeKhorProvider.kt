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
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"

        private const val ABYSS_ORIGIN =
            "https://abyssplayer.com"

        private const val ABYSS_REFERER =
            "https://abyssplayer.com/"
    }


    // ============================================================
    // MAIN PAGE
    // ============================================================

    override val mainPage = mainPageOf(

        "$mainUrl/anime/?status=&type=&order=update" to
            "Latest Release",

        "$mainUrl/anime/?status=&type=&order=popular" to
            "Popular",

        "$mainUrl/anime/?status=completed&type=&order=update" to
            "Completed",
    )


    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url =
            if (page == 1) {

                request.data

            } else {

                request.data
                    .replace(
                        "?",
                        "page/$page/?"
                    )
            }


        val document =
            app.get(url).document


        val homeItems =
            mutableListOf<SearchResponse>()


        for (
            element in document.select(
                "article.bs > div.bsx"
            )
        ) {

            val item =
                element.toSearchResult()


            if (item != null) {
                homeItems.add(item)
            }
        }


        return newHomePageResponse(
            request.name,
            homeItems.distinctBy {
                it.url
            }
        )
    }


    // ============================================================
    // SEARCH RESULT
    // ============================================================

    private fun Element.toSearchResult():
        SearchResponse? {

        val linkEl =
            this.selectFirst("a")
                ?: return null


        val rawHref =
            fixUrlNull(
                linkEl.attr("href")
            )
                ?.trimEnd('/')
                ?: return null


        val href =
            rawHref
                .replace(
                    Regex(
                        "-(episode|ep)-\\d+.*$"
                    ),
                    ""
                )
                .let {

                    if (
                        it.contains(
                            "/anime/"
                        )
                    ) {

                        it

                    } else {

                        "$mainUrl/anime/" +
                            it.substringAfterLast("/") +
                            "/"
                    }
                }


        val title =
            linkEl
                .attr("title")
                .ifBlank {
                    this
                        .selectFirst("div.tt")
                        ?.text()
                }
                ?.trim()
                ?: return null


        val rawPoster =
            this
                .selectFirst("img")
                ?.attr("data-lazy-src")
                ?.ifBlank {
                    null
                }
                ?: this
                    .selectFirst("img")
                    ?.attr("data-src")
                    ?.ifBlank {
                        null
                    }
                ?: this
                    .selectFirst("img")
                    ?.attr("src")


        val posterUrl =
            fixUrlNull(
                rawPoster
                    ?.substringBefore("?")
                    ?.replace(
                        Regex(
                            "https?://i\\d+\\.wp\\.com/"
                        ),
                        "https://"
                    )
            )


        return newAnimeSearchResponse(
            title,
            href,
            TvType.Anime
        ) {

            this.posterUrl =
                posterUrl
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


        val targetLimit =
            100


        var page =
            1


        while (
            searchResults.size <
            targetLimit
        ) {

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
                    document.select(
                        "article.bs > div.bsx"
                    )


                if (
                    elements.isEmpty()
                ) {
                    break
                }


                for (
                    element in elements
                ) {

                    val item =
                        element.toSearchResult()


                    if (item != null) {
                        searchResults.add(item)
                    }
                }


                page++

            } catch (
                e: Exception
            ) {

                break
            }
        }


        return searchResults
            .distinctBy {
                it.url
            }
            .take(
                targetLimit
            )
    }


    // ============================================================
    // LOAD
    // ============================================================

    override suspend fun load(
        url: String
    ): LoadResponse {

        val document =
            app.get(url).document


        val title =
            document
                .selectFirst(
                    "h1.entry-title, h1"
                )
                ?.text()
                ?.trim()
                ?.replace(
                    Regex(
                        "(?i)(episode|ep)\\s*\\d+.*"
                    ),
                    ""
                )
                ?: ""


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
                ?.ifBlank {
                    null
                }
                ?: posterElement
                    ?.attr("data-src")
                    ?.ifBlank {
                        null
                    }
                ?: posterElement
                    ?.attr("src")


        var poster =
            fixUrlNull(
                rawPoster
                    ?.substringBefore("?")
                    ?.replace(
                        Regex(
                            "https?://i\\d+\\.wp\\.com/"
                        ),
                        "https://"
                    )
            )


        if (
            poster.isNullOrBlank()
        ) {

            val ogImage =
                document
                    .selectFirst(
                        "meta[property=og:image]"
                    )
                    ?.attr("content")


            if (
                ogImage != null &&
                !ogImage.contains(
                    "logo",
                    true
                ) &&
                !ogImage.contains(
                    "banner",
                    true
                )
            ) {

                poster =
                    fixUrlNull(
                        ogImage
                    )
            }
        }


        val synopsis =
            document
                .selectFirst(
                    ".entry-content, " +
                        ".synp .entry-content, " +
                        "#synopsis, " +
                        ".desc"
                )
                ?.text()


        val genres =
            document
                .select(
                    "a[href*=/genres/], .genxed a"
                )
                .map {
                    it.text()
                }


        // --------------------------------------------------------
        // Episode parser
        // --------------------------------------------------------

        fun parseEpisodeGrid(
            doc: org.jsoup.nodes.Document,
            currentUrl: String
        ): List<Episode> {

            val epList =
                mutableListOf<Episode>()


            for (
                li in doc.select(
                    "div.eplister ul li, " +
                        "div.episodelist ul li, " +
                        "ul.episodelist li, " +
                        "div.ep_list ul li, " +
                        ".bixbox.bxcl ul li"
                )
            ) {

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
                        li.select(
                            "div.playinfo"
                        ).isNotEmpty()
                    ) {

                        currentUrl

                    } else {

                        continue
                    }


                if (
                    epHref == null
                ) {
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
                        )
                        .trim()


                val epNumText =
                    li
                        .selectFirst(".epl-num")
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
                        ?: Regex(
                            "\\d+"
                        )
                            .find(epNumText)
                            ?.value
                            ?.toIntOrNull()


                epList.add(
                    newEpisode(
                        epHref
                    ) {

                        this.name =
                            epTitle.ifBlank {
                                "Episode $epNum"
                            }

                        this.episode =
                            epNum
                    }
                )
            }


            return epList
                .distinctBy {
                    it.data
                }
                .reversed()
        }


        var episodes =
            parseEpisodeGrid(
                document,
                url
            )


        if (
            episodes.isEmpty()
        ) {

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
                            it
                                .attr("href")
                                .contains(
                                    "-episode-"
                                ) ||
                                it
                                    .attr("href")
                                    .contains(
                                        "-ep-"
                                    )
                            ) &&
                            it
                                .attr("href")
                                .contains(
                                    mainUrl
                                )
                    }
                    ?.attr("href")


            val fallbackHref =
                fixUrlNull(
                    firstEpLink
                        ?: anyEpLink
                )


            if (
                fallbackHref != null
            ) {

                val epDocument =
                    app
                        .get(
                            fallbackHref
                        )
                        .document


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

            this.posterUrl =
                poster

            this.plot =
                synopsis

            this.tags =
                genres

            addEpisodes(
                DubStatus.Subbed,
                episodes
            )
        }
    }


    // ============================================================
    // URL HELPER
    // ============================================================

    private fun fixRelativeUrl(
        url: String?,
        baseUrl: String
    ): String? {

        if (
            url.isNullOrBlank()
        ) {
            return null
        }


        val trimmed =
            url.trim()


        return when {

            trimmed.startsWith(
                "http://"
            ) ||
                trimmed.startsWith(
                    "https://"
                ) -> {

                trimmed
            }


            trimmed.startsWith(
                "//"
            ) -> {

                "https:$trimmed"
            }


            trimmed.startsWith(
                "/"
            ) -> {

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
                        uri.path.substringBeforeLast(
                            "/",
                            ""
                        )

                    "${uri.scheme}://${uri.host}" +
                        "$path/" +
                        trimmed.removePrefix("./")

                }.getOrNull()
                    ?: trimmed
            }
        }
    }


    // ============================================================
    // ABYSS HELPERS
    // ============================================================

    private fun extractAbyssVideoUrl(
        embedUrl: String
    ): String? {

        try {

            /*
             * ----------------------------------------------------
             * STEP 1
             *
             * Load the AbyssPlayer page.
             * ----------------------------------------------------
             */

            val abyssHeaders =
                mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to ABYSS_REFERER,
                    "Origin" to ABYSS_ORIGIN,
                    "Accept" to "*/*",
                )


            val html =
                app.get(
                    embedUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$mainUrl/",
                        "Accept" to "text/html,application/xhtml+xml"
                    )
                ).text


            /*
             * ----------------------------------------------------
             * STEP 2
             *
             * First try normal direct video URLs.
             *
             * This preserves the old Abyss behaviour.
             * ----------------------------------------------------
             */

            val directPatterns =
                listOf(

                    Regex(
                        """<source[^>]+src=["']([^"']+)["']""",
                        RegexOption.IGNORE_CASE
                    ),

                    Regex(
                        """file\s*:\s*["']([^"']+)["']""",
                        RegexOption.IGNORE_CASE
                    ),

                    Regex(
                        """https?://[^\s"'<>]+\.mp4(?:\?[^\s"'<>]*)?""",
                        RegexOption.IGNORE_CASE
                    ),

                    Regex(
                        """https?://storage\.googleapis\.com/[^\s"'<>]+""",
                        RegexOption.IGNORE_CASE
                    )
                )


            for (
                pattern in directPatterns
            ) {

                val match =
                    pattern
                        .find(html)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.replace(
                            "\\/",
                            "/"
                        )


                if (
                    !match.isNullOrBlank()
                ) {

                    return match
                }
            }


            /*
             * ----------------------------------------------------
             * STEP 3
             *
             * Your screenshot shows AbyssPlayer requesting:
             *
             * https://wbtqi2taq32.sssr.org/
             *     ?timestamp=...
             *     &sid=...
             *
             * The hostname can change, so don't hard-code it.
             * Find the actual API request in the player HTML/JS.
             * ----------------------------------------------------
             */

            val apiRequestRegex =
                Regex(
                    """https?://([a-zA-Z0-9.-]+\.(?:sssr|sssrr)\.org)/\?[^"'<>\\\s]+""",
                    RegexOption.IGNORE_CASE
                )


            val apiMatch =
                apiRequestRegex
                    .find(html)


            if (
                apiMatch != null
            ) {

                val apiUrl =
                    apiMatch.value
                        .replace(
                            "\\/",
                            "/"
                        )


                val jsonText =
                    runCatching {

                        app.get(
                            apiUrl,
                            headers = abyssHeaders
                        ).text

                    }.getOrNull()


                if (
                    !jsonText.isNullOrBlank()
                ) {

                    val result =
                        extractAbyssSoraUrl(
                            jsonText,
                            apiMatch
                                .groupValues
                                .getOrNull(1)
                        )


                    if (
                        result != null
                    ) {
                        return result
                    }
                }
            }


            /*
             * ----------------------------------------------------
             * STEP 4
             *
             * Sometimes the URL is assembled in JavaScript.
             *
             * Extract timestamp + sid and the sssr/sssr host
             * separately.
             * ----------------------------------------------------
             */

            val hostMatch =
                Regex(
                    """https?://([a-zA-Z0-9.-]+\.(?:sssr|sssrr)\.org)""",
                    RegexOption.IGNORE_CASE
                )
                    .find(html)


            val host =
                hostMatch
                    ?.groupValues
                    ?.getOrNull(1)


            val timestamp =
                Regex(
                    """["']?timestamp["']?\s*[:=]\s*["']?(\d{10,})""",
                    RegexOption.IGNORE_CASE
                )
                    .find(html)
                    ?.groupValues
                    ?.getOrNull(1)


            val sid =
                Regex(
                    """["']?sid["']?\s*[:=]\s*["']?([a-zA-Z0-9_-]+)""",
                    RegexOption.IGNORE_CASE
                )
                    .find(html)
                    ?.groupValues
                    ?.getOrNull(1)


            if (
                !host.isNullOrBlank() &&
                !timestamp.isNullOrBlank() &&
                !sid.isNullOrBlank()
            ) {

                val apiUrl =
                    "https://$host/" +
                        "?timestamp=$timestamp" +
                        "&sid=$sid"


                val jsonText =
                    runCatching {

                        app.get(
                            apiUrl,
                            headers = abyssHeaders
                        ).text

                    }.getOrNull()


                if (
                    !jsonText.isNullOrBlank()
                ) {

                    val result =
                        extractAbyssSoraUrl(
                            jsonText,
                            host
                        )


                    if (
                        result != null
                    ) {
                        return result
                    }
                }
            }


            /*
             * ----------------------------------------------------
             * STEP 5
             *
             * Search directly for /sora/... in the player page.
             * ----------------------------------------------------
             */

            val soraRegex =
                Regex(
                    """(?:(?:https?:)?//[a-zA-Z0-9.-]+\.(?:sssr|sssrr)\.org)?/sora/[^\s"'<>\\]+""",
                    RegexOption.IGNORE_CASE
                )


            val soraMatch =
                soraRegex
                    .find(html)
                    ?.value
                    ?.replace(
                        "\\/",
                        "/"
                    )


            if (
                !soraMatch.isNullOrBlank()
            ) {

                if (
                    soraMatch.startsWith(
                        "http"
                    )
                ) {

                    return soraMatch

                } else if (
                    !host.isNullOrBlank()
                ) {

                    return "https://$host" +
                        soraMatch
                }
            }

        } catch (
            e: Exception
        ) {

            e.printStackTrace()
        }


        return null
    }


    private fun extractAbyssSoraUrl(
        jsonText: String,
        host: String?
    ): String? {

        /*
         * The exact JSON field name isn't visible in the
         * screenshots, so don't depend on one particular
         * property name.
         *
         * Instead search the JSON returned by the API for
         * the /sora/{id}/{token} URL/path that the browser
         * subsequently requests.
         */

        val cleaned =
            jsonText
                .replace(
                    "\\/",
                    "/"
                )
                .replace(
                    "\\u002F",
                    "/"
                )


        /*
         * Full URL.
         */

        val fullUrl =
            Regex(
                """https?://[a-zA-Z0-9.-]+\.(?:sssr|sssrr)\.org/sora/[^\s"'<>\\]+""",
                RegexOption.IGNORE_CASE
            )
                .find(cleaned)
                ?.value


        if (
            !fullUrl.isNullOrBlank()
        ) {

            return fullUrl
        }


        /*
         * Path-only URL.
         *
         * Screenshot:
         *
         * /sora/667504469/WVNxQW...
         */

        val path =
            Regex(
                """(/sora/[A-Za-z0-9._~!$&'()*+,;=:@%/-]+)""",
                RegexOption.IGNORE_CASE
            )
                .find(cleaned)
                ?.groupValues
                ?.getOrNull(1)


        if (
            !path.isNullOrBlank() &&
            !host.isNullOrBlank()
        ) {

            return "https://$host" +
                path
        }


        /*
         * Some responses can contain a normal video URL
         * instead of /sora/.
         */

        val videoUrl =
            Regex(
                """https?://[^\s"'<>\\]+(?:\.mp4|\.m3u8)(?:\?[^\s"'<>\\]*)?""",
                RegexOption.IGNORE_CASE
            )
                .find(cleaned)
                ?.value


        if (
            !videoUrl.isNullOrBlank()
        ) {

            return videoUrl
        }


        return null
    }


    // ============================================================
    // LOAD LINKS
    // ============================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (
            SubtitleFile
        ) -> Unit,
        callback: (
            ExtractorLink
        ) -> Unit
    ): Boolean {

        val document =
            app.get(data).document


        var found =
            false


        val embedLinks =
            mutableListOf<Pair<String, String>>()


        // --------------------------------------------------------
        // Add embed helper
        // --------------------------------------------------------

        fun addEmbed(
            rawUrl: String?,
            name: String
        ) {

            if (
                rawUrl.isNullOrBlank()
            ) {
                return
            }


            var url =
                rawUrl
                    .trim()
                    .replace(
                        "\\/",
                        "/"
                    )


            if (
                url.startsWith("//")
            ) {

                url =
                    "https:$url"
            }


            if (
                url.startsWith("http")
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
        // MIRROR OPTIONS
        // ========================================================

        val mirrorOptions =
            document.select(
                "select.mirror option[value]"
            )


        for (
            element in mirrorOptions
        ) {

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


            if (
                value.isNotBlank()
            ) {

                if (
                    value.startsWith(
                        "http"
                    ) ||
                    value.startsWith(
                        "//"
                    )
                ) {

                    addEmbed(
                        value,
                        label
                    )

                } else {

                    try {

                        val decoded =
                            String(
                                Base64.decode(
                                    value,
                                    Base64.DEFAULT
                                ),
                                Charsets.UTF_8
                            )
                                .trim()


                        val iframeSrc =
                            Jsoup
                                .parse(
                                    decoded
                                )
                                .select("iframe")
                                .attr("src")
                                .ifBlank {

                                    Regex(
                                        """src\s*=\s*["']([^"']+)["']""",
                                        RegexOption.IGNORE_CASE
                                    )
                                        .find(
                                            decoded
                                        )
                                        ?.groupValues
                                        ?.get(1)
                                }


                        if (
                            !iframeSrc.isNullOrBlank()
                        ) {

                            addEmbed(
                                iframeSrc,
                                label
                            )

                        } else {

                            val directUrl =
                                Regex(
                                    """https?://[^\s"'<>]+"""
                                )
                                    .find(
                                        decoded
                                    )
                                    ?.value


                            if (
                                directUrl != null
                            ) {

                                addEmbed(
                                    directUrl,
                                    label
                                )
                            }
                        }

                    } catch (
                        e: Exception
                    ) {
                    }
                }
            }
        }


        // ========================================================
        // IFRAMES
        // ========================================================

        val iframes =
            document.select(
                ".player-embed iframe, " +
                    "#embed_holder iframe, " +
                    "#pembed iframe, " +
                    "iframe"
            )


        for (
            iframe in iframes
        ) {

            val src =
                iframe
                    .attr("src")
                    .ifBlank {
                        iframe.attr(
                            "data-src"
                        )
                    }
                    .ifBlank {
                        iframe.attr(
                            "data-lazy-src"
                        )
                    }


            addEmbed(
                src,
                "Default Player"
            )
        }


        // ========================================================
        // PROCESS ALL EMBEDS
        // ========================================================

        for (
            (embedUrl, serverLabel)
            in embedLinks
        ) {

            try {

                // ==================================================
                // A. ABYSSPLAYER
                // ==================================================

                if (
                    embedUrl.contains(
                        "abyssplayer.com",
                        true
                    ) ||
                    embedUrl.contains(
                        "abyss",
                        true
                    )
                ) {

                    val mp4Url =
                        extractAbyssVideoUrl(
                            embedUrl
                        )


                    if (
                        !mp4Url.isNullOrBlank()
                    ) {

                        /*
                         * These headers match what your
                         * screenshots show AbyssPlayer using.
                         *
                         * Origin:
                         * https://abyssplayer.com
                         *
                         * Referer:
                         * https://abyssplayer.com/
                         */

                        val abyssVideoHeaders =
                            mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to ABYSS_REFERER,
                                "Origin" to ABYSS_ORIGIN,
                                "Accept" to "*/*",
                            )


                        callback(
                            ExtractorLink(
                                source =
                                    if (
                                        serverLabel.isNotBlank() &&
                                        !serverLabel.equals(
                                            "Default Player",
                                            true
                                        )
                                    ) {

                                        serverLabel

                                    } else {

                                        "AbyssPlayer"
                                    },

                                name =
                                    if (
                                        serverLabel.isNotBlank() &&
                                        !serverLabel.equals(
                                            "Default Player",
                                            true
                                        )
                                    ) {

                                        "$serverLabel (Abyss)"

                                    } else {

                                        "AbyssPlayer"
                                    },

                                url =
                                    mp4Url,

                                referer =
                                    ABYSS_REFERER,

                                quality =
                                    Qualities.Unknown.value,

                                type =
                                    ExtractorLinkType.VIDEO,

                                headers =
                                    abyssVideoHeaders,

                                extractorData =
                                    ""
                            )
                        )


                        found =
                            true


                        continue
                    }
                }


                // ==================================================
                // B. DAILYMOTION
                // ==================================================

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
                            .find(
                                embedUrl
                            )
                            ?.groupValues
                            ?.get(1)
                            ?: Regex(
                                """video=([a-zA-Z0-9_]+)"""
                            )
                                .find(
                                    embedUrl
                                )
                                ?.groupValues
                                ?.get(1)


                    if (
                        vidId != null
                    ) {

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

                            for (
                                link in tempLinks
                            ) {

                                callback(
                                    ExtractorLink(

                                        source =
                                            if (
                                                serverLabel.isNotBlank()
                                            ) {

                                                serverLabel

                                            } else {

                                                "VidPlayer"
                                            },

                                        name =
                                            if (
                                                serverLabel.isNotBlank()
                                            ) {

                                                "$serverLabel (${link.name})"

                                            } else {

                                                link.name
                                            },

                                        url =
                                            link.url,

                                        referer =
                                            link.referer,

                                        quality =
                                            link.quality,

                                        type =
                                            link.type,

                                        headers =
                                            link.headers,

                                        extractorData =
                                            link.extractorData
                                    )
                                )
                            }


                            found =
                                true


                            continue
                        }


                        // --------------------------------------------------
                        // Direct M3U8 fallback
                        // --------------------------------------------------

                        try {

                            val metaJson =
                                app.get(
                                    "https://www.dailymotion.com/player/metadata/video/$vidId"
                                ).text


                            val m3u8Url =
                                Regex(
                                    """https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"""
                                )
                                    .find(
                                        metaJson
                                    )
                                    ?.value


                            if (
                                !m3u8Url.isNullOrBlank()
                            ) {

                                M3u8Helper
                                    .generateM3u8(
                                        if (
                                            serverLabel.isNotBlank()
                                        ) {

                                            serverLabel

                                        } else {

                                            "VidPlayer"
                                        },

                                        m3u8Url,

                                        "https://www.dailymotion.com/"
                                    )
                                    .forEach {

                                        callback(it)

                                        found =
                                            true
                                    }


                                continue
                            }

                        } catch (
                            e: Exception
                        ) {
                        }
                    }
                }


                // ==================================================
                // C. RUMBLE
                // ==================================================

                if (
                    embedUrl.contains(
                        "rumble",
                        true
                    )
                ) {

                    val html =
                        app.get(
                            embedUrl
                        ).text


                    val m3u8 =
                        Regex(
                            """https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"""
                        )
                            .find(
                                html
                            )
                            ?.value


                    if (
                        m3u8 != null
                    ) {

                        M3u8Helper
                            .generateM3u8(
                                "Rumble",
                                m3u8,
                                embedUrl
                            )
                            .forEach {

                                callback(it)

                                found =
                                    true
                            }


                        continue
                    }
                }


                // ==================================================
                // D. D.TUBE
                // ==================================================

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
                            .find(
                                embedUrl
                            )
                            ?.groupValues
                            ?.get(1)
                            ?: embedUrl
                                .substringAfter(
                                    "/videos/"
                                )
                                .substringBefore(
                                    "/"
                                )


                    if (
                        vidId.isNotBlank()
                    ) {

                        val m3u8 =
                            "https://nas2.d.tube/videos/" +
                                "$vidId/master.m3u8"


                        M3u8Helper
                            .generateM3u8(
                                "DPlayer",
                                m3u8,
                                embedUrl
                            )
                            .forEach {

                                callback(it)

                                found =
                                    true
                            }


                        continue
                    }
                }


                // ==================================================
                // E. NATIVE EXTRACTORS
                // ==================================================

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

                    for (
                        link in tempLinks
                    ) {

                        callback(
                            ExtractorLink(

                                source =
                                    if (
                                        serverLabel.isNotBlank() &&
                                        !serverLabel.contains(
                                            "Default"
                                        )
                                    ) {

                                        serverLabel

                                    } else {

                                        link.source
                                    },

                                name =
                                    if (
                                        serverLabel.isNotBlank() &&
                                        !serverLabel.contains(
                                            "Default"
                                        )
                                    ) {

                                        "$serverLabel (${link.name})"

                                    } else {

                                        link.name
                                    },

                                url =
                                    link.url,

                                referer =
                                    link.referer,

                                quality =
                                    link.quality,

                                type =
                                    link.type,

                                headers =
                                    link.headers,

                                extractorData =
                                    link.extractorData
                            )
                        )
                    }


                    found =
                        true


                    continue
                }


                // ==================================================
                // F. CUSTOM PROXY SERVERS
                // ==================================================

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

                    val host =
                        URI(
                            embedUrl
                        ).host


                    val id =
                        embedUrl
                            .substringAfter("#")
                            .substringAfterLast("/")


                    val apiCall =
                        "https://$host/api/v1/video" +
                            "?id=$id" +
                            "&w=1280" +
                            "&h=800" +
                            "&r=animekhor.org"


                    try {

                        val apiRes =
                            app.get(
                                apiCall,
                                headers = mapOf(
                                    "Referer" to
                                        "$mainUrl/",

                                    "User-Agent" to
                                        USER_AGENT
                                )
                            ).text


                        val m3u8 =
                            Regex(
                                """https?://[^\s"'<>]+\.(?:m3u8|txt)[^\s"'<>]*"""
                            )
                                .find(
                                    apiRes
                                )
                                ?.value


                        if (
                            m3u8 != null
                        ) {

                            M3u8Helper
                                .generateM3u8(
                                    serverLabel,
                                    m3u8,
                                    "https://$host/"
                                )
                                .forEach {

                                    callback(it)

                                    found =
                                        true
                                }
                        }

                    } catch (
                        e: Exception
                    ) {
                    }
                }

            } catch (
                e: Exception
            ) {

                e.printStackTrace()
            }
        }


        return found
    }
}