package com.chikianimation

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

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
        val linkEl   = this.selectFirst("a") ?: return null
        val rawHref  = fixUrlNull(linkEl.attr("href")) ?: return null
        val href = rawHref
            .replace(Regex("-(episode|ep)-\\d+-[a-zA-Z0-9-]+/?$"), "")
            .let { if (it.contains("/anime/")) it
                   else "$mainUrl/anime/${it.substringAfterLast("/")}" }

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
                    this.name    = epTitle.ifBlank { "Episode $epNum" }
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
            this.plot      = synopsis
            this.tags      = genres
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
                "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                     "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                     "Chrome/124.0.0.0 Safari/537.36",
                "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Referer"         to mainUrl
            )
        )
        val document = response.document
        val pageHtml = response.text

        // ── DEBUG ─────────────────────────────────────────────────
        Log.d(TAG, "HTML length: ${pageHtml.length}")
        Log.d(TAG, pageHtml.take(3000))

        document.select("iframe").forEachIndexed { i, el ->
            Log.d(TAG, "iframe[$i]: ${el.attributes()}")
        }
        document.select("script").forEach { s ->
            val c = s.html()
            if (c.contains("embed", true) || c.contains("dailymotion", true) ||
                c.contains("player", true) || c.contains("video", true)) {
                Log.d(TAG, "[SCRIPT]: ${c.take(600)}")
            }
        }
        Log.d(TAG, "pembed      : ${document.selectFirst("#pembed")?.html()?.take(400)}")
        Log.d(TAG, "embed_holder: ${document.selectFirst("#embed_holder")?.html()?.take(400)}")

        // ── Collect embed URLs ────────────────────────────────────
        val embedUrls = mutableSetOf<String>()

        fun addUrl(raw: String) {
            val u = raw.trim()
            when {
                u.startsWith("http") -> embedUrls.add(u)
                u.startsWith("//")   -> embedUrls.add("https:$u")
            }
        }

        // A. data-* attributes on every element
        document.allElements.forEach { el ->
            listOf("data-src","data-lazy-src","data-original",
                   "data-embed","data-default-embed","data-url",
                   "data-video","data-link").forEach { attr ->
                val v = el.attr(attr)
                if (v.isNotBlank()) {
                    Log.d(TAG, "[data-attr] ${el.tagName()} $attr=$v")
                    addUrl(v)
                }
            }
        }

        // B. iframes
        document.select("iframe").forEach { iframe ->
            listOf("src","data-src","data-lazy-src","data-original").forEach { attr ->
                val v = iframe.attr(attr)
                if (v.isNotBlank()) {
                    Log.d(TAG, "[iframe $attr]: $v")
                    addUrl(v)
                }
            }
        }

        // C. Base64 encoded embed
        document.select("[data-default-embed]").forEach { el ->
            val encoded = el.attr("data-default-embed")
            if (encoded.isNotBlank()) {
                runCatching {
                    val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
                    Log.d(TAG, "[base64]: $decoded")
                    Regex("""(?:src|href)=["']([^"']+)["']""")
                        .findAll(decoded).forEach { m -> addUrl(m.groupValues[1]) }
                }
            }
        }

        // D. Raw HTML — iframe src
        Regex("""<iframe[^>]+src=["']([^"'<>]+)["']""")
            .findAll(pageHtml).forEach { m ->
                Log.d(TAG, "[raw src]: ${m.groupValues[1]}")
                addUrl(m.groupValues[1])
            }

        // E. Raw HTML — iframe data-src
        Regex("""<iframe[^>]+data-src=["']([^"'<>]+)["']""")
            .findAll(pageHtml).forEach { m ->
                Log.d(TAG, "[raw data-src]: ${m.groupValues[1]}")
                addUrl(m.groupValues[1])
            }

        // F. JS variable scan
        Regex(
            """(?:src|url|embed|link|video)\s*[:=]\s*["']""" +
            """(https?://[^"']+(?:dailymotion|streamtape|dood|""" +
            """filemoon|mp4upload|ok\.ru|drive\.google)[^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(pageHtml).forEach { m ->
            Log.d(TAG, "[JS var]: ${m.groupValues[1]}")
            addUrl(m.groupValues[1])
        }

        Log.d(TAG, "Collected ${embedUrls.size} embed URL(s):")
        embedUrls.forEach { Log.d(TAG, "  -> $it") }

        // ── Extract from each URL ─────────────────────────────────
        var found = false

        for (raw in embedUrls) {
            val url2 = fixUrlNull(raw) ?: continue
            Log.d(TAG, "Trying: $url2")
            try {
                val finalUrl = when {
                    // geo.dailymotion.com → standard embed
                    url2.contains("geo.dailymotion.com") -> {
                        val id = Regex("""video=([a-zA-Z0-9]+)""")
                            .find(url2)?.groupValues?.get(1)
                        if (id != null) {
                            "https://www.dailymotion.com/embed/video/$id"
                        } else null
                    }
                    else -> url2
                }
                if (finalUrl != null) {
                    Log.d(TAG, "  loadExtractor -> $finalUrl")
                    loadExtractor(finalUrl, data, subtitleCallback, callback)
                    found = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "  Error: ${e.message}")
            }
        }

        Log.d(TAG, "=== done, found=$found ===")
        return found
    }
}
