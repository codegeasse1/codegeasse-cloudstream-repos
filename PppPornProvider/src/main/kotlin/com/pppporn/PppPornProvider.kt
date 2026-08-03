package com.asiangirlporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AsianGirlPornProvider : MainAPI() {
    override var mainUrl = "https://asiangirl.porn"
    override var name = "AsianGirlPorn"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    // ---------------------------------------------------------------
    // MAIN PAGE TABS – exactly the sections you asked for
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/new/" to "Latest",
        "$mainUrl/categories/china-av/" to "China Producer",
        "$mainUrl/categories/japan-producer/" to "Japan Producer",
        "$mainUrl/hot/" to "Weekly Hot",
        "$mainUrl/" to "Being Watched",          // special AJAX‑pagination
        "$mainUrl/categories/" to "Categories"   // list of all categories
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data

        // ----- Special: Being Watched (uses AJAX for page > 1) -----
        if (url == "$mainUrl/" && request.name == "Being Watched") {
            return getBeingWatchedPage(page)
        }

        // ----- Special: Categories list -----
        if (url == "$mainUrl/categories/" && request.name == "Categories") {
            return getCategoriesPage()
        }

        // ----- Normal listing with ?page=N -----
        val docUrl = if (page == 1) url else "${url}?page=$page"
        val doc = app.get(docUrl).document
        val items = doc.select("div.item.card-video").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ---------------------------------------------------------------
    // BEING WATCHED – homepage section + AJAX load‑more
    // ---------------------------------------------------------------
    private suspend fun getBeingWatchedPage(page: Int): HomePageResponse {
        val doc = if (page == 1) {
            // first page: parse the homepage
            app.get("$mainUrl/").document
        } else {
            // subsequent pages: use the exact AJAX URL from the "Load more" button
            val from = (page - 1) * 18   // the site loads ~18 items per chunk
            val ajaxUrl = "$mainUrl/?mode=async&function=get_block" +
                "&block_id=list_videos_being_watched_videos" +
                "&sort_by=last_time_view_date" +
                "&from_videos=$from&from_albums=$from"
            app.get(ajaxUrl).document
        }
        val items = doc.select("div.item.card-video").mapNotNull { it.toSearchResult() }
        return newHomePageResponse("Being Watched", items)
    }

    // ---------------------------------------------------------------
    // CATEGORIES PAGE – parse the /categories/ grid
    // ---------------------------------------------------------------
    private suspend fun getCategoriesPage(): HomePageResponse {
        val doc = app.get("$mainUrl/categories/").document
        val categories = doc.select("a[href*=/categories/]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            // Skip the "Categories" link that just leads back to the same page
            if (href == "$mainUrl/categories/") return@mapNotNull null

            val img = a.selectFirst("img")
            val name = img?.attr("alt")?.ifBlank { a.text() }?.trim() ?: return@mapNotNull null
            val poster = fixUrlNull(img?.attr("src") ?: img?.attr("data-src"))

            newMovieSearchResponse(name, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
        return newHomePageResponse("Categories", categories)
    }

    // ---------------------------------------------------------------
    // UNIVERSAL VIDEO‑CARD PARSER (works for every section)
    // ---------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href*=/v/]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        val img = this.selectFirst("img")
        val rawTitle = img?.attr("alt")?.trim()?.ifBlank {
            this.selectFirst("h4.card-video__title a")?.text()?.trim()
        } ?: return null

        val rawPoster = img?.attr("data-src")?.ifBlank { img.attr("src") }
        val posterUrl = fixUrlNull(rawPoster)

        return newMovieSearchResponse(rawTitle, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ---------------------------------------------------------------
    // SEARCH (same pattern: page 1 = HTML, page 2+ = AJAX)
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val maxPages = 3
        val results = mutableListOf<SearchResponse>()
        for (page in 1..maxPages) {
            val doc = if (page == 1) {
                app.get("$mainUrl/search/$encodedQuery/").document
            } else {
                val from = (page - 1) * 20
                val ajaxUrl = "$mainUrl/search/$encodedQuery/" +
                    "?mode=async&function=get_block" +
                    "&block_id=list_videos_videos_list_search_result" +
                    "&q=$encodedQuery" +
                    "&from_videos=$from&from_albums=$from" +
                    "&_=${System.currentTimeMillis()}"
                app.get(ajaxUrl).document
            }
            val items = doc.select("div.item.card-video").mapNotNull { it.toSearchResult() }
            if (items.isEmpty()) break
            results.addAll(items)
        }
        return results
    }

    // ---------------------------------------------------------------
    // LOAD (detail page)
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val pageHtml = doc.html()

        val title = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: "Video"

        var posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
        if (posterUrl.isNullOrBlank()) {
            posterUrl = Regex("""poster="([^"]+)"""").find(pageHtml)?.groupValues?.get(1)
        }

        val tags = doc.select("a[href*=/tags/], a[href*=/categories/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.plot = title
            this.tags = tags
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (generic KVS‑style scraper)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val response = app.get(data)
        val pageHtml = response.text

        val cdnRegex = Regex("""https?:\\?/\\?/[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*""")

        cdnRegex.findAll(pageHtml).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/").replace("&amp;", "&")
            if (cleanUrl.isNotBlank() && !cleanUrl.endsWith(".jpg") && !cleanUrl.endsWith(".png") && !cleanUrl.endsWith(".webp")) {
                val isM3u8 = cleanUrl.contains(".m3u8")
                callback(
                    newExtractorLink(
                        source = name,
                        name = if (isM3u8) "$name HLS" else "$name MP4",
                        url = cleanUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        if (!found) {
            response.document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.startsWith("http")) {
                    try {
                        val iframeHtml = app.get(src, headers = mapOf("Referer" to data)).text
                        cdnRegex.findAll(iframeHtml).forEach { match ->
                            val cleanUrl = match.value.replace("\\/", "/").replace("&amp;", "&")
                            if (cleanUrl.isNotBlank() && !cleanUrl.endsWith(".jpg") && !cleanUrl.endsWith(".png")) {
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name Embed",
                                        url = cleanUrl,
                                        type = if (cleanUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = src
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                found = true
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
        }

        return found
    }
}
