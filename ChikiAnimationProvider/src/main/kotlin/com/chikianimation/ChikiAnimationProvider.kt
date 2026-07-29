package com.chikianimation

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
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
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/124.0.0.0 Safari/537.36",
                "Referer" to mainUrl
            )
        )
        val document = response.document
        val pageHtml = response.text

        val embedUrls = mutableSetOf<String>()

        fun addUrl(raw: String) {
            val u = raw.trim()
            when {
                u.startsWith("http") -> embedUrls.add(u)
                u.startsWith("//") -> embedUrls.add("https:$u")
            }
        }

        // 1. Extract from <option> values (Server dropdown selector)
        document.select("option").forEach { option ->
            val value = option.attr("value").trim()
            if (value.isNotBlank()) {
                // Try Base64 Decoding first
                val decoded = runCatching {
                    String(Base64.decode(value, Base64.DEFAULT))
                }.getOrNull() ?: value

                // Regex search for iframe src inside option html/value
                Regex("""(?:src|href)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(decoded).forEach { m -> addUrl(m.groupValues[1]) }

                if (value.startsWith("http") || value.startsWith("//")) {
                    addUrl(value)
                }
            }
        }

        // 2. Extract from <iframe> elements (src, data-src, etc.)
        document.select("iframe").forEach { iframe ->
            listOf("src", "data-src", "data-lazy-src", "data-original").forEach { attr ->
                val v = iframe.attr(attr)
                if (v.isNotBlank()) addUrl(v)
            }
        }

        // 3. Extract from raw regex matched iframes
        Regex("""<iframe[^>]+(?:src|data-src)=["']([^"'<>]+)["']""", RegexOption.IGNORE_CASE)
            .findAll(pageHtml).forEach { m -> addUrl(m.groupValues[1]) }

        Log.d(TAG, "Collected ${embedUrls.size} embed URL(s):")
        embedUrls.forEach { Log.d(TAG, " -> $it") }

        var found = false

        for (raw in embedUrls) {
            val url = fixUrlNull(raw) ?: continue
            Log.d(TAG, "Processing embed URL: $url")

            // Check if URL is Dailymotion
            if (url.contains("dailymotion.com")) {
                val videoId = Regex("""(?:video/|video=)([a-zA-Z0-9]+)""")
                    .find(url)?.groupValues?.get(1)

                if (videoId != null) {
                    val dmSuccess = invokeDailymotion(videoId, callback)
                    if (dmSuccess) found = true
                }
            } else {
                // Pass non-Dailymotion embeds to standard Cloudstream extractors
                try {
                    loadExtractor(url, data, subtitleCallback, callback)
                    found = true
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading extractor for $url: ${e.message}")
                }
            }
        }

        return found
    }

    // Direct Dailymotion M3U8 Extractor via Metadata API
    private suspend fun invokeDailymotion(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val metadataUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val response = app.get(metadataUrl).text

            // Extract the master m3u8 playlist URL from metadata JSON
            val m3u8Url = Regex(""""url"\s*:\s*"(https://[^"]+\.m3u8[^"]*)"""")
                .find(response)?.groupValues?.get(1)
                ?.replace("\\/", "/")

            if (!m3u8Url.isNullOrEmpty()) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8Url,
                    referer = "https://www.dailymotion.com/"
                ).forEach(callback)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dailymotion extraction failed: ${e.message}")
            false
        }
    }
}
