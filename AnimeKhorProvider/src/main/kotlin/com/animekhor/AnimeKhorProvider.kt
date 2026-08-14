package com.animekhor

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimeKhorProvider : MainAPI() {
    override var mainUrl = "https://animekhor.org"
    override var name = "AnimeKhor"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // AES-CBC key/IV used by the upns.live / p2pstream.vip player to encrypt its video API response
        private val UPN_KEY = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
        private val UPN_IV = "1234567890oiuytr".toByteArray(Charsets.UTF_8)
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?status=&type=&order=update" to "Latest Release",
        "$mainUrl/anime/?status=&type=&order=popular" to "Popular",
        "$mainUrl/anime/?status=completed&type=&order=update" to "Completed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data.replace("?", "page/$page/?")
        val document = app.get(url).document
        
        val homeItems = mutableListOf<SearchResponse>()
        for (element in document.select("article.bs > div.bsx")) {
            val item = element.toSearchResult()
            if (item != null) homeItems.add(item)
        }

        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("a") ?: return null
        val rawHref = fixUrlNull(linkEl.attr("href"))?.trimEnd('/') ?: return null
        
        val href = rawHref.replace(Regex("-(episode|ep)-\\d+.*$"), "")
            .let { if (it.contains("/anime/")) it else "$mainUrl/anime/${it.substringAfterLast("/")}/" }

        val title = linkEl.attr("title").ifBlank { this.selectFirst("div.tt")?.text() }?.trim() ?: return null

        val rawPoster = this.selectFirst("img")?.attr("data-lazy-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("data-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("src")

        val posterUrl = fixUrlNull(rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        val targetLimit = 100
        var page = 1

        while (searchResults.size < targetLimit) {
            val url = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
            try {
                val document = app.get(url).document
                val elements = document.select("article.bs > div.bsx")
                if (elements.isEmpty()) break

                for (element in elements) {
                    val item = element.toSearchResult()
                    if (item != null) searchResults.add(item)
                }
                page++
            } catch (e: Exception) {
                break
            }
        }
        return searchResults.distinctBy { it.url }.take(targetLimit)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()?.replace(Regex("(?i)(episode|ep)\\s*\\d+.*"), "") ?: ""
        
        val posterElement = document.selectFirst(".bigcontent .thumb img, .bixbox .thumb img, article .thumb img, .infox .imgbox img, .ts-post-image")
        val rawPoster = posterElement?.attr("data-lazy-src")?.ifBlank { null } ?: posterElement?.attr("data-src")?.ifBlank { null } ?: posterElement?.attr("src")
        var poster = fixUrlNull(rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://"))

        if (poster.isNullOrBlank()) {
            val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
            if (ogImage != null && !ogImage.contains("logo", true) && !ogImage.contains("banner", true)) {
                poster = fixUrlNull(ogImage)
            }
        }
        
        val synopsis = document.selectFirst(".entry-content, .synp .entry-content, #synopsis, .desc")?.text()
        val genres = document.select("a[href*=/genres/], .genxed a").map { it.text() }

        fun parseEpisodeGrid(doc: org.jsoup.nodes.Document, currentUrl: String): List<Episode> {
            val epList = mutableListOf<Episode>()
            for (li in doc.select("div.eplister ul li, div.episodelist ul li, ul.episodelist li, div.ep_list ul li, .bixbox.bxcl ul li")) {
                val epLink = li.selectFirst("a")
                val epHref = if (epLink != null && epLink.hasAttr("href")) fixUrlNull(epLink.attr("href")) 
                             else if (li.hasClass("selected") || li.hasAttr("selected") || li.select("div.playinfo").isNotEmpty()) currentUrl 
                             else continue
                             
                if (epHref == null) continue
                val epTitle = (epLink?.attr("title")?.ifBlank { epLink.text() } ?: li.text()).trim()
                val epNumText = li.selectFirst(".epl-num")?.text() ?: epTitle
                
                // Safely handles texts like "14 END" by extracting only digits
                val epNum = Regex("(?i)episode\\s*(\\d+)").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(?i)ep\\s*(\\d+)").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("\\d+").find(epNumText)?.value?.toIntOrNull()

                epList.add(newEpisode(epHref) {
                    this.name = epTitle.ifBlank { "Episode $epNum" }
                    this.episode = epNum
                })
            }
            return epList.distinctBy { it.data }.reversed()
        }

        var episodes = parseEpisodeGrid(document, url)
        if (episodes.isEmpty()) {
            val firstEpLink = document.selectFirst(".epcurfirst a, .epcurlast a, .inepcx a, .bxcl a, a:matchesOwn((?i)watch)")?.attr("href")
            val anyEpLink = document.select("a[href]").firstOrNull { 
                (it.attr("href").contains("-episode-") || it.attr("href").contains("-ep-")) && it.attr("href").contains(mainUrl)
            }?.attr("href")
            
            val fallbackHref = fixUrlNull(firstEpLink ?: anyEpLink)
            if (fallbackHref != null) {
                val epDocument = app.get(fallbackHref).document
                episodes = parseEpisodeGrid(epDocument, fallbackHref)
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try { app.get(data).document } catch (e: Exception) { return false }
        var found = false
        val seen = mutableSetOf<String>()

        // mirrors from the "Select Video Server" dropdown (base64-encoded iframes, label = server name)
        val mirrors = mutableListOf<Pair<String, String>>()
        for (option in document.select("select.mirror option[value]")) {
            val label = option.text().trim().ifBlank { "Server" }
            val value = option.attr("value").trim()
            if (value.isBlank()) continue
            val url = decodeMirrorValue(value)
            if (url != null && url.isNotBlank()) mirrors.add(label to url)
        }

        // the default/visible player iframe
        for (iframe in document.select(".player-embed iframe, #embed_holder iframe, #pembed iframe, iframe")) {
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
            if (src.isNotBlank()) {
                val clean = (if (src.startsWith("//")) "https:$src" else src).trim().replace("\\/", "/")
                if (seen.add(clean)) {
                    if (resolveEmbed(clean, "Server", data, subtitleCallback, callback)) found = true
                }
            }
        }

        for ((label, url) in mirrors) {
            val clean = url.trim().replace("\\/", "/")
            if (!seen.add(clean)) continue
            if (resolveEmbed(clean, label, data, subtitleCallback, callback)) found = true
        }

        return found
    }

    private fun decodeMirrorValue(value: String): String? {
        if (value.startsWith("http") || value.startsWith("//")) {
            return if (value.startsWith("//")) "https:$value" else value
        }
        return try {
            val decoded = String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8).trim()
            val iframeSrc = Jsoup.parse(decoded).select("iframe").attr("src")
            if (iframeSrc.isNotBlank()) iframeSrc else Regex("""https?://[^\s"'<>]+""").find(decoded)?.value
        } catch (e: Exception) { null }
    }

    private suspend fun resolveEmbed(
        url: String,
        label: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = url.replace("\\/", "/")
        val host = try { URI(clean).host } catch (e: Exception) { null }
        if (host.isNullOrBlank()) return false

        // direct media link
        if (Regex("""\.(m3u8|mp4|webm)($|\?)""", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = clean,
                    type = if (clean.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
                }
            )
            return true
        }

        // track whether any extractor link was actually emitted, so a native extractor
        // that matches the domain but yields nothing doesn't swallow the server silently
        var emitted = false
        val trackedCallback: (ExtractorLink) -> Unit = { link ->
            emitted = true
            callback(link)
        }

        if (host.contains("dailymotion.com") || host.contains("dai.ly")) {
            try {
                loadExtractor(clean, referer, subtitleCallback, trackedCallback)
                if (emitted) return true
            } catch (e: Exception) { }
        }

        if (host.contains("ok.ru") || host.contains("odnoklassniki")) {
            if (resolveOkru(clean, label, referer, subtitleCallback, trackedCallback)) return true
            try {
                loadExtractor(clean, referer, subtitleCallback, trackedCallback)
                if (emitted) return true
            } catch (e: Exception) { }
        }

        if (host.contains("rumble.com")) {
            if (resolveRumble(clean, label, subtitleCallback, trackedCallback)) return true
        }

        if (host.contains("d.tube")) {
            if (resolveDtube(clean, label, subtitleCallback, trackedCallback)) return true
        }

        if (host.contains("turbovid") || host.contains("emturbovid") || host.contains("turboviplay")) {
            if (resolveTurbovid(clean, label, referer, subtitleCallback, trackedCallback)) return true
        }

        if (host.contains("upns.live") || host.contains("p2pstream.vip")) {
            if (resolveUpns(clean, label, subtitleCallback, trackedCallback)) return true
        }

        if (host.contains("bysekoze") || host.contains("byse")) {
            if (resolveByse(clean, label, subtitleCallback, trackedCallback)) return true
        }

        if (host.contains("abyssplayer") || host.contains("abyss")) {
            if (resolveAbyss(clean, label, referer, subtitleCallback, trackedCallback)) return true
        }

        // generic: native cloudstream extractors (turbovid, vidhide, streamwish, ...)
        try {
            loadExtractor(clean, referer, subtitleCallback, trackedCallback)
            if (emitted) return true
        } catch (e: Exception) { }

        // generic: scrape the embed page for a direct stream
        try {
            val html = app.get(clean, headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)).text.replace("\\/", "/")
            val stream = Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""").find(html)?.value
            if (stream != null) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = label,
                        url = stream,
                        type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = clean
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Referer" to clean, "User-Agent" to USER_AGENT)
                    }
                )
                return true
            }
        } catch (e: Exception) { }

        return false
    }

    // ok.ru serves several page formats depending on the video (escaped JSON with
    // ondemandHls, entity-escaped JSON with hlsManifestUrl, or a legacy "videos" array),
    // and blocks non-browser HTTP clients. Fast path first; when it yields no stream,
    // render the embed page in a real browser engine (WebView) that ok.ru treats normally.
    private suspend fun resolveOkru(
        embedUrl: String,
        label: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = embedUrl.replace("\\\\/", "/")
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to referer,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-User" to "?1",
        )
        val html = try { app.get(clean, headers = headers).text } catch (e: Exception) { null }
        if (html != null) {
            if (emitOkruStream(html, label, clean, callback)) return true
            // a fetched page that looks like a "video deleted" page has no stream to find
            // in a WebView either, so skip the browser fallback
            if (looksLikeDeletedOkru(html)) return false
        }
        return resolveOkruWebView(clean, label, referer, callback)
    }

    // ok.ru's "video no longer exists" page has a generic title (no video name in quotes)
    private fun looksLikeDeletedOkru(html: String): Boolean {
        if (html.contains("data-options")) return false
        val title = Regex("""<title>([^<]*)</title>""").find(html)?.groupValues?.get(1) ?: return false
        return title.contains("See video") && !title.contains("&quot;")
    }

    // parse whatever format ok.ru served and emit the stream (returns true if emitted)
    private suspend fun emitOkruStream(
        html: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val unescaped = html.replace("\\&quot;", "\"")
            .replace("&quot;", "\"")
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }

        // 1) modern formats: hlsManifestUrl / ondemandHls m3u8
        val hls = Regex("""(?:hlsManifestUrl|ondemandHls)"\s*:\s*"([^"]+\.m3u8[^"]*)""").find(unescaped)?.groupValues?.get(1)?.trim()
        if (hls != null && hls.startsWith("http")) {
            val links = M3u8Helper.generateM3u8(label, hls, referer)
            if (links.isNotEmpty()) {
                links.forEach { callback(it) }
                return true
            }
        }

        // 2) legacy format: "videos":[{"name":"...","url":"..."}]
        val videosStr = Regex(""""videos":(\[[^]]*\])""").find(unescaped)?.groupValues?.get(1)
        if (videosStr != null) {
            val videos = try { JSONArray(videosStr) } catch (e: Exception) { null }
            if (videos != null && videos.length() > 0) {
                var emitted = false
                for (i in 0 until videos.length()) {
                    val v = videos.optJSONObject(i) ?: continue
                    val u = v.optString("url").ifBlank { continue }
                    val url = if (u.startsWith("//")) "https:$u" else u
                    if (!url.startsWith("http")) continue
                    emitted = true
                    callback(
                        newExtractorLink(
                            source = name,
                            name = label,
                            url = url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://ok.ru/"
                            this.quality = okruQuality(v.optString("name"))
                            this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://ok.ru/")
                        }
                    )
                }
                if (emitted) return true
            }
        }

        // 3) fallback: direct signed .okcdn.ru stream URL
        val direct = Regex(""""url"\s*:\s*"(https?://[^"]*\?expires=[^"]*)""").find(unescaped)?.groupValues?.get(1)
        if (direct != null && direct.startsWith("http")) {
            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = direct,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://ok.ru/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://ok.ru/")
                }
            )
            return true
        }

        return false
    }

    // load the ok.ru embed in an in-app browser (WebView) - a real Chrome engine with
    // proper TLS/cookies, so it gets the same page a browser does even when ok.ru
    // blocks the okhttp client. The script grabs the data-options JSON from the DOM and
    // signals deleted videos so the resolver can exit early.
    private suspend fun resolveOkruWebView(
        embedUrl: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var captured: String? = null
        val resolver = WebViewResolver(
            interceptUrl = Regex("""\.m3u8|x-deleted"""),
            additionalUrls = listOf(Regex("""\.m3u8"""), Regex("""videoPreview""")),
            useOkhttp = false,
            script = "(function(){var el=document.querySelector('[data-options]');" +
                "if(el)return 'STREAM:'+el.getAttribute('data-options');" +
                "var t=document.title||'';" +
                "if(t.indexOf('See video')===0&&t.indexOf('\"')===-1){location.replace('https://ok.ru/x-deleted');return 'DELETED';}" +
                "return '';})()",
            scriptCallback = { s ->
                val v = s.trim().trim('"')
                if (v.startsWith("STREAM:")) captured = v.removePrefix("STREAM:")
            },
            timeout = 12_000L
        )
        val (fixedRequest, extraRequests) = resolver.resolveUsingWebView(
            embedUrl,
            referer = referer,
            requestCallBack = { req ->
                val u = req.url.toString()
                u.contains(".m3u8") || (u.contains("videoPreview") && captured != null) || u.contains("x-deleted")
            }
        )

        // if the player itself requested an m3u8, use that directly
        val m3u8Request = (listOfNotNull(fixedRequest?.url?.toString()) + extraRequests.map { it.url.toString() })
            .firstOrNull { it.contains(".m3u8") }
        if (m3u8Request != null) {
            val links = M3u8Helper.generateM3u8(label, m3u8Request, embedUrl)
            if (links.isNotEmpty()) {
                links.forEach { callback(it) }
                return true
            }
        }

        val html = captured ?: return false
        return emitOkruStream(html, label, embedUrl, callback)
    }

    private fun okruQuality(name: String): Int {
        val n = name.uppercase()
        return when {
            n.contains("4K") || n.contains("ULTRA") -> Qualities.P2160.value
            n.contains("1440") || n.contains("QUAD") -> Qualities.P1440.value
            n.contains("1080") || n.contains("FULL") -> Qualities.P1080.value
            n.contains("720") || n.contains("HD") -> Qualities.P720.value
            n.contains("480") || n.contains("SD") -> Qualities.P480.value
            n.contains("360") || n.contains("LOW") -> Qualities.P360.value
            n.contains("240") || n.contains("LOWEST") -> Qualities.P240.value
            n.contains("144") || n.contains("MOBILE") -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }
    }


    private suspend fun resolveRumble(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = embedUrl.replace("\\/", "/")
        val html = try { app.get(clean).text } catch (e: Exception) { return false }
        val m3u8 = Regex(""""hls"\s*:\s*\{[^}]*"url"\s*:\s*"([^"]+\.m3u8[^"]*)""").find(html)?.groupValues?.get(1)
            ?: Regex("""https?://rumble\.com/hls-vod/[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)?.value
            ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)?.value
        if (m3u8 == null) return false
        val links = M3u8Helper.generateM3u8(label, m3u8, clean)
        if (links.isEmpty()) return false
        links.forEach { callback(it) }
        return true
    }

    private suspend fun resolveDtube(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = embedUrl.replace("\\/", "/")
        val vidId = Regex("""[?&]v=([a-zA-Z0-9-]+)""").find(clean)?.groupValues?.get(1)
            ?: clean.substringAfter("/videos/").substringBefore("/").substringBefore("?").ifBlank { return false }
        val m3u8 = "https://nas2.d.tube/videos/$vidId/master.m3u8"
        val links = M3u8Helper.generateM3u8(label, m3u8, clean)
        if (links.isEmpty()) return false
        links.forEach { callback(it) }
        return true
    }

    // Turbovid (turbovidhls.com / emturbovid / turboviplay): the embed page is behind a
    // Cloudflare challenge, so don't scrape it - the master playlist is directly constructible
    // on the CDN from the video code embedded in the URL.
    private suspend fun resolveTurbovid(
        embedUrl: String,
        label: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = embedUrl.replace("\\/", "/")
        val code = clean.substringBefore("?").substringBefore("#").trimEnd('/').substringAfterLast('/')
        if (code.isBlank() || code.contains(".")) return false
        val m3u8 = (1..3).mapNotNull { i ->
            val url = "https://cdn$i.turboviplay.com/data3/$code/$code.m3u8"
            try {
                val text = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).text
                if (text.startsWith("#EXTM3U") || text.contains("#EXT-X-STREAM-INF")) url else null
            } catch (e: Exception) { null }
        }.firstOrNull()
        if (m3u8 == null) return false
        try {
            val subText = app.get(
                "https://sub.turboviplay.to/sub/$code/$code.json",
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text
            for (m in Regex("\"file\"\\s*:\\s*\"([^\"]+\\.(?:vtt|srt)[^\"]*)\"").findAll(subText)) {
                val subUrl = m.groupValues[1]
                subtitleCallback(
                    newSubtitleFile(subUrl.substringAfterLast('/').substringBeforeLast('.').ifBlank { "English" }, subUrl) {
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    }
                )
            }
        } catch (e: Exception) { }
        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://turbovidhls.com/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
        return true
    }

    private suspend fun resolveAbyss(
        embedUrl: String,
        label: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = embedUrl.replace("\\/", "/")
        val html = try {
            app.get(clean, headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)).text
        } catch (e: Exception) { return false }
        val url = Regex("""<source[^>]+src=["']([^"']+\.mp4[^"'\s<>]*)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""(?:file|src|url)\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"'\s<>]*)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*""").find(html)?.value
        if (url == null) return false
        // only emit when the stream actually responds, so dead servers don't show in the picker
        val playable = try {
            app.get(
                url,
                headers = mapOf("Range" to "bytes=0-0", "Referer" to referer, "User-Agent" to USER_AGENT)
            ).isSuccessful
        } catch (e: Exception) { false }
        if (!playable) return false
        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = url,
                type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = ""
                this.quality = Qualities.Unknown.value
                this.headers = emptyMap()
            }
        )
        return true
    }

    // CloudPlayer (upns.live) / FilePlayer (p2pstream.vip): fetch the encrypted video payload,
    // AES-CBC decrypt it, then play the returned m3u8.
    private suspend fun resolveUpns(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = embedUrl.replace("\\/", "/")
        val host = try { URI(clean).host } catch (e: Exception) { null } ?: return false
        val id = clean.substringAfterLast("#").substringBefore("&").ifBlank { return false }
        val refererHost = try { URI(mainUrl).host } catch (e: Exception) { null } ?: "animekhor.org"
        val api = "https://$host/api/v1/video?id=$id&w=1280&h=800&r=$refererHost"
        val resText = try {
            app.get(api, headers = mapOf("Referer" to clean, "User-Agent" to USER_AGENT)).text
        } catch (e: Exception) { return false }
        val jsonText = aesCbcDecrypt(resText.trim()) ?: return false
        val json = try { JSONObject(jsonText) } catch (e: Exception) { return false }
        val streamUrl = json.optString("cfNative").ifBlank { json.optString("cf") }
        if (streamUrl.isBlank() || !streamUrl.startsWith("http")) return false

        val subs = json.optJSONObject("subtitle")
        if (subs != null) {
            val it = subs.keys()
            while (it.hasNext()) {
                val subName = it.next()
                val rel = subs.optString(subName)
                if (rel.isBlank()) continue
                val subUrl = if (rel.startsWith("http")) rel.substringBefore("#") else "https://$host" + rel.substringBefore("#")
                subtitleCallback(
                    newSubtitleFile(subName, subUrl) {
                        this.headers = mapOf("Referer" to "https://$host/", "User-Agent" to USER_AGENT)
                    }
                )
            }
        }

        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://$host/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("Referer" to "https://$host/", "User-Agent" to USER_AGENT)
            }
        )
        return true
    }

    // VGPlayer (bysekoze.com): Byse-family player - details -> playback -> AES-GCM decrypt -> m3u8
    private suspend fun resolveByse(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = embedUrl.replace("\\/", "/")
        val base = try { "https://" + (URI(clean).host ?: return false) } catch (e: Exception) { return false }
        val code = clean.trimEnd('/').substringAfterLast('/')
        if (code.isBlank() || code.contains(".")) return false
        val details = try {
            JSONObject(app.get("$base/api/videos/$code/embed/details", headers = mapOf("Referer" to clean, "User-Agent" to USER_AGENT)).text)
        } catch (e: Exception) { return false }
        val embedFrameUrl = details.optString("embed_frame_url")
        if (embedFrameUrl.isBlank()) return false
        val embedBase = try { "https://" + (URI(embedFrameUrl).host ?: return false) } catch (e: Exception) { return false }
        val code2 = embedFrameUrl.trimEnd('/').substringAfterLast('/')
        val playbackText = try {
            app.get(
                "$embedBase/api/videos/$code2/embed/playback",
                headers = mapOf(
                    "accept" to "*/*",
                    "accept-language" to "en-US,en;q=0.5",
                    "priority" to "u=1, i",
                    "referer" to embedFrameUrl,
                    "x-embed-parent" to clean,
                    "User-Agent" to USER_AGENT
                )
            ).text
        } catch (e: Exception) { return false }
        val p = try { JSONObject(playbackText).optJSONObject("playback") } catch (e: Exception) { null } ?: return false
        val keyParts = p.optJSONArray("key_parts") ?: return false
        if (keyParts.length() < 2) return false
        val decrypted = aesGcmDecrypt(p.optString("payload"), p.optString("iv"), keyParts) ?: return false
        val sources = try { JSONObject(decrypted).optJSONArray("sources") } catch (e: Exception) { null } ?: return false
        val streamUrl = (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }
            .map { it.optString("url") }
            .firstOrNull { it.startsWith("http") } ?: return false
        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedBase
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("Referer" to embedBase, "User-Agent" to USER_AGENT)
            }
        )
        return true
    }

    private fun aesCbcDecrypt(hex: String): String? {
        return try {
            val cleaned = hex.replace(Regex("[^0-9a-fA-F]"), "")
            if (cleaned.isEmpty()) return null
            val bytes = cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (bytes.isEmpty()) return null
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(UPN_KEY, "AES"), IvParameterSpec(UPN_IV))
            String(cipher.doFinal(bytes), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    private fun aesGcmDecrypt(payload: String, iv: String, keyParts: org.json.JSONArray): String? {
        return try {
            val keyBytes = b64UrlDecode(keyParts.getString(0)) + b64UrlDecode(keyParts.getString(1))
            val ivBytes = b64UrlDecode(iv)
            val cipherBytes = b64UrlDecode(payload)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8).removePrefix("\uFEFF")
        } catch (e: Exception) { null }
    }

    private fun b64UrlDecode(s: String): ByteArray {
        val fixed = s.replace('-', '+').replace('_', '/')
        val pad = (4 - fixed.length % 4) % 4
        return Base64.decode(fixed + "=".repeat(pad), Base64.DEFAULT)
    }
}
