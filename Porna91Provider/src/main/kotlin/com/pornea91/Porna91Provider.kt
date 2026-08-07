package com.pornea91

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Porna91Provider : MainAPI() {
    override var mainUrl = "https://91porna.com"
    override var name = "91Porna"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/comic/index/video?category=now_month_hot" to "Hot Ranking",
        "$mainUrl/comic/index/video?category=original" to "Original",
        "$mainUrl/comic/index/video?category=play" to "Currently Playing",
        "$mainUrl/comic/index/video?category=new_update" to "Recent Updates",
        "$mainUrl/melonshort" to "Short Videos",
        "$mainUrl/comic/index/av" to "Japan AV",
        "$mainUrl/moviesets" to "Collections",
        "$mainUrl/novels" to "Novels"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val docUrl = if (page == 1) request.data else "${request.data}&page=$page"
        val document = app.get(docUrl, headers = headers).document
        
        val items = document.select(".video-items .video-item, ul.video-items > li.video-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href*=/detail], a[href*=/avdetail], a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        val img = this.selectFirst("img")
        val title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".title, .line-clamp-2, .post-item-title, h3, h4")?.text()?.trim()
            ?: return null

        val rawPoster = img?.attr("data-src")?.ifBlank { img.attr("data-original") }?.ifBlank { img.attr("src") }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            // Using standard URLs to prevent Scoped Storage file:// URI blocks
            this.posterUrl = fixUrlNull(rawPoster) 
            this.posterHeaders = headers
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResponse>()
        
        for (page in 1..3) {
            val docUrl = if (page == 1) "$mainUrl/comic/index/search?keyword=$encodedQuery"
            else "$mainUrl/comic/index/search?keyword=$encodedQuery&page=$page"
            
            val document = app.get(docUrl, headers = headers).document
            val items = document.select(".video-items .video-item").mapNotNull {
                it.toSearchResult()
            }
            
            if (items.isEmpty()) break
            results.addAll(items)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        
        val title = document.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: document.selectFirst("h1, h2")?.text()?.trim() ?: "Video"

        var rawPoster = document.selectFirst("meta[property=og:image]")?.attr("content")
        if (rawPoster.isNullOrBlank()) {
            val img = document.selectFirst(".poster img, .video-cover img, .video-item img")
            rawPoster = img?.attr("data-src")?.ifBlank { img.attr("src") }
        }

        val tags = document.select("a[href*=/search?keyword=]").map { it.text().trim() }.filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(rawPoster)
            this.backgroundPosterUrl = fixUrlNull(rawPoster)
            this.posterHeaders = headers
            this.plot = title
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val html = app.get(data, headers = headers).text
        val mappedUrls = mutableSetOf<String>()

        suspend fun addStream(streamUrl: String) {
            var cleanUrl = streamUrl.replace("\\/", "/").trim()
            cleanUrl = cleanUrl.substringBefore("\"").substringBefore("'").substringBefore("\\")
            
            if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
            if (!cleanUrl.startsWith("http")) return
            if (!cleanUrl.contains(".m3u8") && !cleanUrl.contains(".mp4")) return
            if (!mappedUrls.add(cleanUrl)) return

            val isM3u8 = cleanUrl.contains(".m3u8")
            
            callback.invoke(
                newExtractorLink(name, name + if (isM3u8) " HLS" else " MP4", cleanUrl, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        // 1. Scan Main HTML (Aggressive Regex handles auth_key query parameters)
        Regex("""(https?[\\/]+[^\s"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?)""").findAll(html).forEach {
            addStream(it.groupValues[1])
        }

        // 2. Scan APIs if nothing is found in the raw HTML
        if (!found) {
            val vidIdMatch = Regex("""(?:video_key=|/detail/)(\d+)""").find(data)
            if (vidIdMatch != null) {
                val vidId = vidIdMatch.groupValues[1]
                val apiEndpoints = listOf(
                    "/api/video/detail?video_key=$vidId",
                    "/api/comic/video/detail?video_key=$vidId",
                    "/api/v1/video/detail?video_key=$vidId"
                )
                
                for (api in apiEndpoints) {
                    try {
                        val apiRes = app.get("$mainUrl$api", headers = headers).text
                        Regex("""(https?[\\/]+[^\s"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?)""").findAll(apiRes).forEach {
                            addStream(it.groupValues[1])
                        }
                        if (found) break
                    } catch (e: Exception) {}
                }
            }
        }
        
        // 3. Scan Embedded Iframes
        val document = Jsoup.parse(html)
        for (iframe in document.select("iframe")) {
            val src = fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") })
            if (src != null && src.startsWith("http")) {
                try {
                    val iframeHtml = app.get(src, headers = headers).text
                    Regex("""(https?[\\/]+[^\s"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?)""").findAll(iframeHtml).forEach {
                        addStream(it.groupValues[1])
                    }
                } catch (e: Exception) {}
            }
        }

        return found
    }
}
