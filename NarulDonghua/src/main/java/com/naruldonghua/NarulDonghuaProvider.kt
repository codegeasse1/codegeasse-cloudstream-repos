package com.naruldonghua

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64

class NarulDonghuaProvider : MainAPI() {
    override var mainUrl = "https://naruldonghua.com"
    override var name = "Narul Donghua"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/ongoing/" to "Ongoing Series",
        "$mainUrl/completed/" to "Completed Series",
        "$mainUrl/upcoming/" to "Upcoming Series",
        "$mainUrl/comic-series/" to "Comic Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1 && request.data.endsWith("/")) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        val homeItems = mutableListOf<HomePageList>()

        val articles = document.select("article.bs, .swiper-slide.item")
        val cards = articles.mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        if (cards.isNotEmpty()) {
            homeItems.add(HomePageList(request.name, cards))
        }

        return newHomePageResponse(homeItems, hasNext = document.select(".hpage .r, .nav-previous").isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a") ?: return null
        val href = fixUrl(anchor.attr("href"))
        if (href.isBlank() || href.contains("/author/") || href.contains("/genres/")) return null

        val title = this.selectFirst(".tt, h2, .entry-title")?.text()?.trim()
            ?: anchor.attr("title")
            ?: return null

        val imgEl = this.selectFirst("img")
        val poster = imgEl?.attr("data-src")?.ifBlank { imgEl.attr("src") }
            ?: this.selectFirst(".backdrop")?.attr("style")?.let { style ->
                Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)?.groupValues?.get(1)
            }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article.bs, .listupd .bs").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, .title-section h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("–")?.trim()
            ?: "Unknown Title"

        val poster = document.selectFirst(".thumb img, .mvelement img, meta[property=og:image]")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }.ifBlank { it.attr("content") }
        }

        val plot = document.selectFirst(".desc.mindes, .entry-content p")?.text()?.trim()

        val episodes = mutableListOf<Episode>()

        val epElements = document.select(
            ".eplister ul li, .episodelist ul li, .lister ul li, .naveps a, div.ep_list ul li"
        )
        epElements.forEach { el ->
            val a = el.selectFirst("a") ?: if (el.tagName() == "a") el else return@forEach
            val epHref = fixUrl(a.attr("href"))
            if (epHref.isBlank() || epHref == mainUrl) return@forEach

            val epTitle = a.selectFirst("h3, h4, .epl-title")?.text()?.trim() ?: a.text().trim()
            val epNum = Regex("""(?:episode|ep\.?)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: el.selectFirst(".epl-num")?.text()?.trim()?.toIntOrNull()

            episodes.add(newEpisode(epHref) {
                this.name = epTitle
                this.episode = epNum
            })
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = title
                this.episode = 1
            })
        }

        val sortedEpisodes = episodes.sortedBy { it.episode ?: 0 }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            addEpisodes(DubStatus.Subbed, sortedEpisodes.distinctBy { it.data })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val document = app.get(data).document

        // ------------------------------------------------------------
        // Some posts store the iframe HTML with entities (&quot; &amp; \/).
        // Without unescaping, the src regexes miss them => "No Links Found"
        // even though the same player works on the site.
        // ------------------------------------------------------------
        fun String.unescape(): String = this
            .replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
            .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replace("\\/", "/").replace("&amp;", "&")

        fun addDirect(url: String, tag: String, referer: String): Boolean {
            val clean = url.unescape().trim()
            val finalUrl = when {
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("http") -> clean
                else -> return false
            }
            val isHls = finalUrl.contains("m3u8") || finalUrl.contains(".txt")
            callback(
                newExtractorLink(
                    source = name,
                    name = tag,
                    url = finalUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Referer" to referer)
                }
            )
            return true
        }

        // ------------------------------------------------------------
        // SELF-CONTAINED Dailymotion resolution.
        // Does NOT rely on built-in extractors (they are often missing).
        // Uses the public player metadata API which returns direct
        // .mp4 and .m3u8 URLs for any public video.
        // ------------------------------------------------------------
        suspend fun resolveDailymotion(src: String): Boolean {
            val idRegex = Regex("""(?:/embed/video/|/video/|video=|/embed/)([a-zA-Z0-9]+)""")
            var id = idRegex.find(src)?.groupValues?.get(1)

            // dai.ly short links: follow the redirect to get the real URL
            if (id == null && src.contains("dai.ly")) {
                val real = try { app.get(src).url } catch (_: Exception) { src }
                id = idRegex.find(real)?.groupValues?.get(1)
            }
            if (id == null) return false

            var ok = false
            try {
                val meta = app.get(
                    "https://www.dailymotion.com/player/metadata/video/$id?embed=false",
                    headers = mapOf("User-Agent" to USER_AGENT)
                ).text
                Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").findAll(meta).forEach { m ->
                    val u = m.groupValues[1]
                    if (u.contains("m3u8") || u.contains(".mp4")) {
                        if (addDirect(u, "Dailymotion", "https://www.dailymotion.com/")) ok = true
                    }
                }
            } catch (_: Exception) { }

            // Fallback to built-in extractor only if metadata API failed
            if (!ok) {
                try {
                    ok = loadExtractor("https://www.dailymotion.com/video/$id", data, subtitleCallback, callback)
                } catch (_: Exception) { }
            }
            return ok
        }

        // ------------------------------------------------------------
        // SELF-CONTAINED Rumble resolution: scrape the embed page for the
        // hls-vod / cdn.rumble.cloud playlists you captured in network logs.
        // ------------------------------------------------------------
        suspend fun resolveRumble(src: String): Boolean {
            var ok = false
            try { ok = loadExtractor(src, data, subtitleCallback, callback) } catch (_: Exception) { }
            if (!ok) {
                try {
                    val page = app.get(
                        src,
                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to data)
                    ).text.unescape()
                    val m3u8 = Regex("""https?://rumble\.com/hls-vod/[^"'\s]+\.m3u8[^"'\s]*""").find(page)?.value
                        ?: Regex("""https?://[a-z0-9.-]*cdn\.rumble\.cloud/[^"'\s]+\.m3u8[^"'\s]*""").find(page)?.value
                    if (m3u8 != null) ok = addDirect(m3u8, "Rumble", "https://rumble.com/")
                } catch (_: Exception) { }
            }
            return ok
        }

        suspend fun handleEmbed(rawSrc: String): Boolean {
            val src = rawSrc.unescape().trim().removeSurrounding("\"").removeSurrounding("'")
            if (src.isBlank() || src == "about:blank" || src.startsWith("javascript")) return false

            // 0) Already a direct media file (raw CDN m3u8/mp4 in base64)
            if (src.contains(".m3u8") || src.contains(".mp4")) {
                return addDirect(src, "Direct", data)
            }

            // 1) Dailymotion (any format: embed/video, player.html, dai.ly)
            if (src.contains("dailymotion") || src.contains("dai.ly")) {
                return resolveDailymotion(src)
            }

            // 2) Rumble
            if (src.contains("rumble.com")) {
                return resolveRumble(src)
            }

            // 3) narulplex.p2pstream.vip custom player
            if (src.contains("narulplex.p2pstream.vip")) {
                val id = Regex("""[?&]id=([\w-]+)""").find(src)?.groupValues?.get(1)
                    ?: Regex("""/e/([\w-]+)""").find(src)?.groupValues?.get(1)
                    ?: src.substringAfterLast("#").ifBlank { null }
                    ?: src.trimEnd('/').substringAfterLast("/")

                if (!id.isNullOrBlank()) {
                    try {
                        val videoApiUrl = "https://narulplex.p2pstream.vip/api/v1/video?id=$id&w=1280&h=800&r=naruldonghua.com"
                        val res = app.get(
                            videoApiUrl,
                            headers = mapOf("Referer" to src, "User-Agent" to USER_AGENT)
                        ).text

                        val streamMatch = Regex(""""(?:url|file|src|m3u8)"\s*:\s*"([^"]+\.(?:m3u8|txt)[^"]*)"""")
                            .find(res)?.groupValues?.get(1)
                            ?: Regex("""https?://[^\s"'<>]+\.(?:m3u8|txt)[^\s"'<>]*""").find(res)?.value

                        if (!streamMatch.isNullOrBlank()) {
                            return addDirect(streamMatch, "Narul P2P", src)
                        }
                    } catch (_: Exception) { }
                }
                return false
            }

            // 4) Generic: built-in extractor, then deep-scan the embed page
            var linkFound = false
            try { linkFound = loadExtractor(src, data, subtitleCallback, callback) } catch (_: Exception) { }
            if (!linkFound) {
                try {
                    val text = app.get(
                        src,
                        headers = mapOf("Referer" to data, "User-Agent" to USER_AGENT)
                    ).text.unescape()
                    Regex("""https?://[^"'\s<>]+?(?:m3u8|\.mp4)[^"'\s<>]*""").find(text)?.value?.let {
                        linkFound = addDirect(it, "Narul Stream", src)
                    }
                } catch (_: Exception) { }
            }
            return linkFound
        }

        // Parse direct iframe payloads
        document.select(".player-embed iframe, iframe[src]").forEach { iframe ->
            val src = fixUrl(iframe.attr("src").ifBlank { iframe.attr("data-litespeed-src") })
            if (src.isNotBlank() && !src.contains("about:blank")) {
                if (handleEmbed(src)) found = true
            }
        }

        // Parse base64 dropdown servers (Mirror selector)
        document.select("select.mirror option").forEach { option ->
            val base64Val = option.attr("value").trim()
            if (base64Val.length > 20) {
                try {
                    // MIME decoder tolerates line breaks / stray chars that
                    // made the strict decoder throw on some posts
                    val decodedHtml = String(Base64.getMimeDecoder().decode(base64Val)).unescape()

                    val iframeSrc = Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(decodedHtml)?.groupValues?.get(1)
                        ?: Regex("""src\s*=\s*([^\s>]+)""", RegexOption.IGNORE_CASE)
                            .find(decodedHtml)?.groupValues?.get(1)
                        ?: Regex("""https?://[^\s"'<>]+""").find(decodedHtml)?.value

                    if (!iframeSrc.isNullOrBlank()) {
                        if (handleEmbed(fixUrl(iframeSrc.trim()))) found = true
                    }
                } catch (_: Exception) { }
            }
        }

        return found
    }
}