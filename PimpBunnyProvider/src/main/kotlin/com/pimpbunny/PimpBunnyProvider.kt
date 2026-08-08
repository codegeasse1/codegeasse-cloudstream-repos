package com.pimpbunny

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class PimpBunnyProvider : MainAPI() {
    override var mainUrl = "https://pimpbunny.com"
    override var name = "PimpBunny"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    private var sessionInitialized = false
    private suspend fun initSession() {
        if (!sessionInitialized) {
            app.get(mainUrl, headers = headers).text
            sessionInitialized = true
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/onlyfans-creators/" to "Models",
        "$mainUrl/categories/4k/" to "4K",
        "$mainUrl/categories/amateur/" to "Amateur",
        "$mainUrl/categories/anal/" to "Anal",
        "$mainUrl/categories/asian/" to "Asian",
        "$mainUrl/categories/bbw/" to "BBW",
        "$mainUrl/categories/big-ass/" to "Big Ass",
        "$mainUrl/categories/big-tits/" to "Big Tits",
        "$mainUrl/categories/blonde/" to "Blonde",
        "$mainUrl/categories/blowjob/" to "Blowjob",
        "$mainUrl/categories/brunette/" to "Brunette",
        "$mainUrl/categories/creampie/" to "Creampie",
        "$mainUrl/categories/cumshot/" to "Cumshot",
        "$mainUrl/categories/ebony/" to "Ebony",
        "$mainUrl/categories/hardcore/" to "Hardcore",
        "$mainUrl/categories/latina/" to "Latina",
        "$mainUrl/categories/lesbian/" to "Lesbian",
        "$mainUrl/categories/milf/" to "MILF",
        "$mainUrl/categories/pov/" to "POV",
        "$mainUrl/categories/teen/" to "Teen",
        "$mainUrl/categories/threesome/" to "Threesome"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        initSession()

        val docUrl = if (page == 1) request.data else "${request.data}${page}/"
        val document = app.get(docUrl, headers = headers).document
        
        val isModelsPage = request.data.contains("/models/") || request.data.contains("/onlyfans-creators/") || request.data.contains("/channels/")
        val items = mutableListOf<SearchResponse>()

        if (isModelsPage) {
            val modelElements = document.select("div.b6m-video, div.item, div.video-block, div.video-item, article.post").ifEmpty {
                document.select("a[href*=/onlyfans-creators/], a[href*=/models/]")
            }
            for (element in modelElements) {
                parseModelItem(element)?.let { items.add(it) }
            }
        } else {
            for (element in document.select("div.b6m-video, div.item, div.video-block, div.video-item, article.post")) {
                element.toSearchResult()?.let { items.add(it) }
            }
        }
        
        val hasNext = document.select(".pagination a.next, .page-next, a[rel=next]").isNotEmpty() || items.size >= 16
        
        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items.distinctBy { it.url })),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href*=/video]") ?: this.selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        
        if (href.contains("/categories/") || href.contains("/models/") || href.contains("/channels/") || href.contains("/onlyfans-creators/")) return null
        
        val img = this.selectFirst("img")
        val title = img?.attr("alt")?.trim()?.ifBlank { null }
            ?: link.attr("title")?.trim()?.ifBlank { null }
            ?: this.selectFirst(".ui-card-title, .text-truncate, .title, .video-title")?.text()?.trim()
            ?: return null
            
        val posterUrl = fixUrlNull(
            img?.attr("data-original")?.ifBlank { img.attr("data-src")?.ifBlank { img.attr("src") } }
        )
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // ---------------------------------------------------------------
    // MODEL CARD PARSER (With Strict Blacklist)
    // ---------------------------------------------------------------
    private fun parseModelItem(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href*=/models/], a[href*=/onlyfans-creators/], a[href*=/channels/]") 
            ?: element.takeIf { it.tagName() == "a" && (it.attr("href").contains("onlyfans-creators") || it.attr("href").contains("model")) } 
            ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null

        val uriPath = href.substringAfter(mainUrl).substringBefore("?")
        if (uriPath == "/onlyfans-creators/" || uriPath == "/models/" || uriPath == "/channels/") return null
        if (href.contains("?sort_by=") || href.contains("?mode=")) return null

        val imgEl = element.selectFirst("img") ?: linkEl.selectFirst("img")
        val poster = imgEl?.let {
            it.attr("data-original").ifBlank {
                it.attr("data-src").ifBlank {
                    it.attr("src")
                }
            }
        } ?: element.selectFirst("[data-original]")?.attr("data-original") ?: ""

        val title = element.selectFirst(".model-name, .name, .title, .ui-card-title, .text-truncate")?.text()?.trim()
            ?: imgEl?.attr("alt")?.ifBlank { null }
            ?: linkEl.attr("title")?.ifBlank { null }
            ?: linkEl.text().trim()
            ?: "Model"

        val titleLow = title.lowercase()
        val blacklist = setOf("verified", "models", "all models", "open to collab", "collab", "alphabetical", "most viewed", "newest", "tr", "en", "es", "fr", "pt", "de", "it", "cn", "jp", "ru", "...", "next", "prev")
        if (blacklist.contains(titleLow) || titleLow.matches(Regex("""^\d+$"""))) return null

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        initSession()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val maxPages = 5
        val results = mutableListOf<SearchResponse>()
        for (page in 1..maxPages) {
            val docUrl = if (page == 1) "$mainUrl/search/$encodedQuery/"
                         else "$mainUrl/search/$encodedQuery/$page/"
            val document = app.get(docUrl, headers = headers).document
            
            val videoItems = mutableListOf<SearchResponse>()
            for (element in document.select("div.b6m-video, div.item, div.video-block, div.video-item, article.post")) {
                element.toSearchResult()?.let { videoItems.add(it) }
            }
            
            val modelItems = mutableListOf<SearchResponse>()
            for (element in document.select("div.b6m-video, div.item, div.video-block, div.video-item, article.post, a[href*=/onlyfans-creators/]")) {
                parseModelItem(element)?.let { modelItems.add(it) }
            }
            
            if (videoItems.isEmpty() && modelItems.isEmpty()) break
            results.addAll(videoItems)
            results.addAll(modelItems)
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        initSession()
        val document = app.get(url, headers = headers).document

        val isModelPage = url.contains("/models/") || url.contains("/onlyfans-creators/") || url.contains("/channels/") || url.contains("/channel/")

        // ---------------------------------------------------------------
        // MODEL PAGE WITH FULL PAGINATION LOOP
        // ---------------------------------------------------------------
        if (isModelPage) {
            val modelTitle = document.selectFirst("title")?.text()?.substringBefore("-")?.trim()
                ?: document.selectFirst("h1, h2, .page-title, .title, .model-name")?.text()?.trim()
                ?: "Model Videos"

            var poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            if (poster.isNullOrBlank()) {
                poster = document.selectFirst(".model-image img, .post-thumbnail img, .avatar img, .poster img")?.let {
                    it.attr("data-original").ifBlank { it.attr("data-src").ifBlank { it.attr("src") } }
                }
            }

            val episodes = mutableListOf<Episode>()
            var currentDoc = document
            var currentUrl = url
            var pageCount = 1

            // Scrape up to 20 pages (640 videos) automatically
            while (pageCount <= 20) {
                val videoElements = currentDoc.select("div.b6m-video, div.item, div.video-block, div.video-item, article.post")
                
                for (el in videoElements) {
                    val linkEl = el.selectFirst("a[href*=/video]") ?: el.selectFirst("a") ?: continue
                    val videoHref = fixUrlNull(linkEl.attr("href")) ?: continue
                    
                    if (videoHref == currentUrl || videoHref.contains("/models/") || videoHref.contains("/onlyfans-creators/") || videoHref.contains("/categories/")) continue

                    val imgEl = el.selectFirst("img") ?: linkEl.selectFirst("img")
                    val videoTitle = imgEl?.attr("alt")?.trim()?.ifBlank { null }
                        ?: linkEl.attr("title")?.trim()?.ifBlank { null }
                        ?: el.selectFirst(".ui-card-title, .text-truncate, .title, .video-title")?.text()?.trim()
                        ?: "Video"

                    val videoPoster = imgEl?.let {
                        it.attr("data-original").ifBlank {
                            it.attr("data-src").ifBlank {
                                it.attr("src")
                            }
                        }
                    }

                    episodes.add(
                        newEpisode(videoHref) {
                            this.name = videoTitle
                            this.posterUrl = fixUrlNull(videoPoster)
                        }
                    )
                }

                // Advanced Pagination Logic: Looks for "Next" OR the exact number of the next page
                val nextBtn = currentDoc.select(".pagination a").firstOrNull {
                    val text = it.text().trim().lowercase()
                    it.hasClass("next") || it.parent()?.hasClass("next") == true || text.contains("next") || text == "»" || text == ">"
                } ?: currentDoc.select(".pagination a").firstOrNull {
                    it.text().trim() == (pageCount + 1).toString()
                }

                var nextHref = nextBtn?.attr("href")

                if (nextHref.isNullOrBlank() || nextHref == "#" || nextHref.startsWith("javascript:")) {
                    val dataParams = nextBtn?.attr("data-parameters")
                    if (!dataParams.isNullOrBlank()) {
                        nextHref = "?$dataParams"
                    }
                }

                if (nextHref.isNullOrBlank()) break

                val nextUrl = when {
                    nextHref.startsWith("http") -> nextHref
                    nextHref.startsWith("?") -> currentUrl.substringBefore("?") + nextHref
                    nextHref.startsWith("/") -> mainUrl.trimEnd('/') + nextHref
                    else -> currentUrl.substringBeforeLast("/") + "/" + nextHref
                }

                if (nextUrl == currentUrl) break

                try {
                    currentUrl = nextUrl
                    currentDoc = app.get(currentUrl, headers = headers).document
                    pageCount++
                } catch (e: Exception) {
                    break
                }
            }

            val distinctEpisodes = episodes.distinctBy { it.data }.mapIndexed { index, ep ->
                ep.apply { this.episode = index + 1 }
            }

            return newTvSeriesLoadResponse(modelTitle, url, TvType.TvSeries, distinctEpisodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = "Videos from $modelTitle"
            }
        }

        // ---------------------------------------------------------------
        // NORMAL SINGLE VIDEO PAGE
        // ---------------------------------------------------------------
        val title = document.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: document.selectFirst("h1, h2")?.text()?.trim() ?: "Video"
        var posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        if (posterUrl.isNullOrBlank()) {
            posterUrl = document.selectFirst(".poster img, .video-cover img")?.attr("data-original")
                ?: document.selectFirst(".poster img, .video-cover img")?.attr("data-src")
                ?: document.selectFirst(".poster img, .video-cover img")?.attr("src")
        }
        val tags = document.select("a[href*=/categories/], a[href*=/models/], a[href*=/onlyfans-creators/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = title
            this.tags = tags
        }
    }

    // ---------------------------------------------------------------
    // 302 REDIRECT RESOLVER (Fixes ExoPlayer Buffering Bug)
    // ---------------------------------------------------------------
    private suspend fun resolveIfRedirect(url: String, referer: String): String {
        if (!url.contains("/get_file/")) return url
        return try {
            val response = app.get(url, headers = headers + mapOf("Referer" to referer), allowRedirects = false)
            val location = response.headers["location"] ?: response.headers["Location"]
            if (!location.isNullOrBlank()) {
                if (location.startsWith("http")) location else mainUrl.trimEnd('/') + location
            } else {
                url
            }
        } catch (e: Exception) {
            url
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
        initSession()
        var found = false
        
        // Use exact referer to fetch initial page properly
        val reqHeaders = headers + mapOf("Referer" to "$mainUrl/")
        val mainHtml = app.get(data, headers = reqHeaders).text
        val mappedUrls = mutableSetOf<String>()

        suspend fun searchForStream(html: String, referer: String) {
            // General Regex that catches direct sources
            val generalRegex = Regex("""['"](https?://[^'"]+?(?:/get_file/|\.mp4|\.m3u8)[^'"]*)['"]""")
            for (match in generalRegex.findAll(html)) {
                val url = match.groupValues[1].replace("&amp;", "&").replace("\\/", "/")
                
                // Block GIF and image previews
                if (url.endsWith(".gif", true) || url.contains(".gif?")) continue
                if (url.contains(".jpg") || url.contains(".png")) continue

                if (mappedUrls.add(url)) {
                    val isM3u8 = url.contains(".m3u8")
                    val qualityStr = extractQualityFromUrl(url)
                    val qualityVal = getQualityFromString(qualityStr)
                    val sourceName = if (qualityStr != "Unknown") "$name ${qualityStr}p" else if (isM3u8) "$name M3U8" else "$name MP4"

                    // Resolve redirects before feeding to ExoPlayer to prevent buffering
                    val finalUrl = resolveIfRedirect(url, referer)

                    callback(newExtractorLink(name, sourceName, finalUrl, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.referer = referer
                        this.quality = qualityVal
                    })
                    found = true
                }
            }

            // KVS Player Flashvars extraction
            val flashvarsRegex = Regex("""(?:video_url|video_alt_url\d*)\s*[:=]\s*['"](http[^'"]+)['"]""")
            for (match in flashvarsRegex.findAll(html)) {
                val rawUrl = match.groupValues[1].replace("&amp;", "&").replace("\\/", "/")
                
                if (rawUrl.endsWith(".gif", true) || rawUrl.contains(".gif?")) continue
                if (rawUrl.contains(".jpg") || rawUrl.contains(".png")) continue

                if (mappedUrls.add(rawUrl)) {
                    val isM3u8 = rawUrl.contains(".m3u8")
                    val qualityStr = extractQualityFromUrl(rawUrl)
                    val qualityVal = getQualityFromString(qualityStr)
                    val sourceName = if (qualityStr != "Unknown") "$name ${qualityStr}p" else if (isM3u8) "$name M3U8" else "$name MP4"

                    val finalUrl = resolveIfRedirect(rawUrl, referer)

                    callback(newExtractorLink(name, sourceName, finalUrl, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.referer = referer
                        this.quality = qualityVal
                    })
                    found = true
                }
            }

            // KVS Player JSON extraction (Loops to find EVERY quality available)
            val playerNames = listOf(
                "player_aaaa", "player_data", "player_info", "player", "videoConfig",
                "config", "playInfo", "playerConfig", "videoInfo"
            )
            for (pName in playerNames) {
                val match = Regex("""$pName\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html)
                if (match != null) {
                    try {
                        val json = match.groupValues[1]
                        val encrypt = Regex("\"encrypt\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        
                        val urlMatches = Regex(""""(?:url|video_url|video_alt_url\d*)["']?\s*:\s*["']([^"']+)["']""").findAll(json)
                        
                        for (uMatch in urlMatches) {
                            val urlEncoded = uMatch.groupValues[1]
                            val realUrl = when (encrypt) {
                                1 -> String(Base64.decode(urlEncoded, Base64.DEFAULT), Charsets.UTF_8)
                                2 -> URLDecoder.decode(String(Base64.decode(urlEncoded, Base64.DEFAULT), Charsets.UTF_8), "UTF-8")
                                else -> urlEncoded
                            }
                            
                            val cleanUrl = realUrl.replace("\\/", "/").replace("&amp;", "&")
                            
                            if (cleanUrl.endsWith(".gif", true) || cleanUrl.contains(".gif?")) continue
                            if (cleanUrl.contains(".jpg") || cleanUrl.contains(".png")) continue
                            
                            if ((cleanUrl.contains(".m3u8") || cleanUrl.contains(".mp4") || cleanUrl.contains("get_file")) && mappedUrls.add(cleanUrl)) {
                                val isM3u8 = cleanUrl.contains(".m3u8")
                                val qualityStr = extractQualityFromUrl(cleanUrl)
                                val qualityVal = getQualityFromString(qualityStr)
                                val sourceName = if (qualityStr != "Unknown") "Player ${qualityStr}p" else if (isM3u8) "Player M3U8" else "Player MP4"

                                val finalUrl = resolveIfRedirect(cleanUrl, referer)

                                callback(newExtractorLink(
                                    name, 
                                    sourceName, 
                                    finalUrl,
                                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = referer
                                    this.quality = qualityVal
                                })
                                found = true
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        searchForStream(mainHtml, data)

        // API fallback if normal extraction fails
        if (!found && data.contains("video_key=")) {
            val videoKey = data.substringAfter("video_key=").substringBefore("&")
            val apiUrls = listOf(
                "$mainUrl/api/play?video_key=$videoKey",
                "$mainUrl/comic/play?video_key=$videoKey",
                "$mainUrl/api/video?key=$videoKey",
                "$mainUrl/api/getVideo?key=$videoKey"
            )
            for (api in apiUrls) {
                try {
                    val json = app.get(api, headers = headers).text
                    val urlMatch = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(json)
                    if (urlMatch != null) {
                        val streamUrl = urlMatch.groupValues[1]
                        if (streamUrl.contains(".m3u8") && mappedUrls.add(streamUrl)) {
                            val finalUrl = resolveIfRedirect(streamUrl, data)
                            callback(newExtractorLink(name, "API", finalUrl, ExtractorLinkType.M3U8) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            })
                            found = true
                            break
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return found
    }

    // ---------- Quality Parsing Helpers ----------
    private fun extractQualityFromUrl(url: String): String {
        val qualityMatch = Regex("""(?i)(?:_|-|/)(\d{3,4})p?(?:\.mp4|/)""").find(url) 
            ?: Regex("""(?i)(\d{3,4})p""").find(url)
        return qualityMatch?.groupValues?.get(1) ?: "Unknown"
    }

    private fun getQualityFromString(qualityString: String): Int {
        return when {
            qualityString.contains("2160") || qualityString.contains("4k", true) -> Qualities.P2160.value
            qualityString.contains("1440") || qualityString.contains("2k", true) -> 1440
            qualityString.contains("1080") -> Qualities.P1080.value
            qualityString.contains("720") -> Qualities.P720.value
            qualityString.contains("480") -> Qualities.P480.value
            qualityString.contains("360") -> Qualities.P360.value
            qualityString.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }
}
