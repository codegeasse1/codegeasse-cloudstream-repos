package com.leakporner

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64

class LeakPornerProvider : MainAPI() {
    override var mainUrl = "https://leakporner.org"
    override var name = "LeakPorner"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.NSFW)

    companion object {
        // 58img.top (and similar CDN hosts on this theme) commonly require a
        // same-site Referer or they silently reject the request — this is
        // the likely cause of images not rendering.
        private const val IMG_REFERER = "https://leakporner.org/"
    }

    // ---------- Main Page ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document
        val items = document.select("article.loop-video, article.post").mapNotNull { el -> parseVideoItem(el) }
        val hasNext = document.select(".pagination a.next").isNotEmpty()

        return newHomePageResponse(
            list = listOf(HomePageList(name, items)),
            hasNext = hasNext
        )
    }

    // ---------- Search ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("article.loop-video, article.post").mapNotNull { el ->
            parseVideoItem(el)
        }
    }

    // ---------- Load Details ----------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst(".entry-title, .video-title, h1")?.text()
            ?: document.select("meta[property=og:title]").attr("content")
            ?: "No title"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".post-thumbnail img, .video-player img")?.let {
                it.attr("data-src").ifBlank { null }
                    ?: it.attr("data-lazy-src").ifBlank { null }
                    ?: it.attr("data-original").ifBlank { null }
                    ?: it.attr("srcset").substringBefore(" ").ifBlank { null }
                    ?: it.attr("src")
            }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrl(poster)
            this.posterHeaders = mapOf("Referer" to IMG_REFERER)
        }
    }

    // ---------- Load Video Sources ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val docText = app.get(data).text
        val document = org.jsoup.Jsoup.parse(docText)

        val iframeUrls = mutableSetOf<String>()

        // 1. Scrape standard iframes — skip blob: urls, they're browser-internal
        //    object references and can never be fetched server-side.
        document.select("iframe").forEach {
            val src = it.attr("src").ifBlank { it.attr("data-src") }
            if (src.isNotBlank() && !src.startsWith("blob:")) iframeUrls.add(fixUrl(src))
        }

        // 2. Scrape Base64 encoded iframes (Very common on tube sites)
        val base64Regex = Regex("""(?:data-embed|value)=["']([A-Za-z0-9+/=]+)["']""")
        base64Regex.findAll(docText).forEach { match ->
            runCatching {
                val decoded = String(Base64.getDecoder().decode(match.groupValues[1]))
                val srcMatch = Regex("""src=["']([^"']+)["']""").find(decoded)
                srcMatch?.groupValues?.get(1)?.let {
                    if (!it.startsWith("blob:")) iframeUrls.add(fixUrl(it))
                }
            }
        }

        // 3. Fallback: many WordPress video/tube themes load each server's iframe
        // via an AJAX call per "server tab" rather than embedding it in the raw
        // page HTML — which would explain why iframeUrls stays empty even
        // though 6 real embeds exist on the page. This block tries the common
        // "doo_player_ajax" pattern used by several such themes.
        // NOTE: this endpoint/param shape is NOT confirmed for this specific
        // site — if it doesn't return anything, the real fix requires seeing
        // the actual AJAX request in DevTools (Network → Fetch/XHR) when
        // clicking between server tabs, since the exact action name and
        // parameter keys vary by theme.
        if (iframeUrls.isEmpty()) {
            document.select("[data-post][data-nume][data-type], li[data-embed], .server-item[data-id]").forEach { el ->
                val post = el.attr("data-post").ifBlank { el.attr("data-id") }
                val nume = el.attr("data-nume").ifBlank { "1" }
                val type = el.attr("data-type").ifBlank { "movie" }
                if (post.isBlank()) return@forEach

                runCatching {
                    val ajaxRes = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to post,
                            "nume" to nume,
                            "type" to type
                        ),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to data
                        )
                    ).text

                    val embedSrc = Regex("""src=["']([^"']+)["']""").find(ajaxRes)?.groupValues?.get(1)
                        ?: Regex(""""embed_url"\s*:\s*"([^"]+)"""").find(ajaxRes)?.groupValues?.get(1)

                    embedSrc?.let {
                        val clean = it.replace("\\/", "/")
                        if (!clean.startsWith("blob:")) iframeUrls.add(fixUrl(clean))
                    }
                }
            }
        }

        // 4. Process all gathered iframes (AbyssPlayer, HGCloud, Morencius, etc.)
        iframeUrls.forEach { iframeUrl ->
            runCatching {
                // Pass to built-in CloudStream extractors first
                if (loadExtractor(iframeUrl, data, subtitleCallback, callback)) {
                    found = true
                } else {
                    // Deep scrape: Fetch the 3rd-party iframe HTML
                    val iframeHtml = app.get(iframeUrl, headers = mapOf("Referer" to data)).text

                    // Dig for hidden file objects (JSON, JWPlayer configs, pure variables)
                    val streamRegex = Regex("""(?:file|src|url|source)["']?\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4|txt)[^"']*)["']""")
                    val rawUrlRegex = Regex("""(https?://[^"'\s>]+\.(?:m3u8|mp4)[^"'\s>]*)""")

                    var iframeFound = false

                    // Match JSON/JS configs
                    streamRegex.findAll(iframeHtml).forEach { match ->
                        val streamUrl = match.groupValues[1].replace("\\/", "/")
                        addStreamLink(streamUrl, iframeUrl, callback)
                        iframeFound = true
                    }

                    // Match raw strings in HTML if the above fails
                    if (!iframeFound) {
                        rawUrlRegex.findAll(iframeHtml).forEach { match ->
                            val streamUrl = match.groupValues[1].replace("\\/", "/")
                            addStreamLink(streamUrl, iframeUrl, callback)
                            iframeFound = true
                        }
                    }

                    if (iframeFound) found = true
                }
            }
        }

        // 5. Main Page Fallback Regex (In case video is hosted directly without an iframe)
        val directStreamRegex = Regex("""(?:file|src|url)["']?\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4|txt)[^"']*)["']""")
        directStreamRegex.findAll(docText).forEach { match ->
            val streamUrl = match.groupValues[1].replace("\\/", "/")
            addStreamLink(streamUrl, data, callback)
            found = true
        }

        return found
    }

    // ---------- Helpers ----------
    // Fixed: was a plain function calling a suspend function (newExtractorLink)
    // — that mismatch is exactly what broke the Gradle build. Marking this
    // suspend and awaiting it correctly resolves the compile error.
    private suspend fun addStreamLink(rawUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        // ExoPlayer refuses to play .txt or .tar HLS manifests unless explicitly labeled
        val safeUrl = if (rawUrl.contains(".txt") && !rawUrl.contains(".m3u8")) "$rawUrl#.m3u8" else rawUrl
        val isM3u8 = safeUrl.contains(".m3u8")

        callback.invoke(
            newExtractorLink(
                source = name,
                name = if (isM3u8) "HLS Server" else "MP4 Server",
                url = safeUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun parseVideoItem(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("a[href]") ?: return null
        val href = linkEl.attr("href")

        // Advanced Lazy Load Image Scraper
        val posterEl = element.selectFirst("img")
        val poster = posterEl?.let {
            it.attr("data-src").ifBlank { null }
                ?: it.attr("data-lazy-src").ifBlank { null }
                ?: it.attr("data-original").ifBlank { null }
                ?: it.attr("srcset").substringBefore(" ").ifBlank { null }
                ?: it.attr("src")
        } ?: ""

        val title = element.selectFirst(".entry-header span, .post-title, .title, .entry-title")?.text() ?: "No title"

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrl(poster)
            this.posterHeaders = mapOf("Referer" to IMG_REFERER)
        }
    }

    private fun fixUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl.trimEnd('/') + url
            else -> url
        }
    }
}