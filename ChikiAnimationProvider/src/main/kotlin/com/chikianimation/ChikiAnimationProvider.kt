package com.chikianimation

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ChikiAnimationProvider : MainAPI() {
    override var mainUrl = "https://chikianimation.online"
    override var name = "ChikiAnimation"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val RPM = "https://chiki.rpmstream.live"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?status=&type=&order=update" to "Latest Release",
        "$mainUrl/anime/?status=&type=&order=popular" to "Popular",
        "$mainUrl/anime/?status=completed&type=&order=update" to "Completed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data.replace("?", "page/$page/?")
        val document = app.get(url).document
        val home = document.select("article.bs > div.bsx").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("a") ?: return null
        val rawHref = fixUrlNull(linkEl.attr("href")) ?: return null
        val href = rawHref.replace(Regex("-(episode|ep)-\\d+-[a-zA-Z0-9-]+/?$"), "")
            .let { if (it.contains("/anime/")) it else "$mainUrl/anime/${it.substringAfterLast("/")}" }
        val title = linkEl.attr("title").ifBlank { this.selectFirst("div.tt")?.text() }?.trim() ?: return null
        val rawPoster = this.selectFirst("img")?.attr("data-lazy-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("data-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("src")
        val posterUrl = fixUrlNull(rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://"))
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.bs > div.bsx").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("(?i)(episode|ep)\\s*\\d+.*"), "") ?: ""
        val posterElement = document.selectFirst(".bigcontent .thumb img, .bixbox .thumb img, article .thumb img, .infox .imgbox img, .ts-post-image")
        val rawPoster = posterElement?.attr("data-lazy-src")?.ifBlank { null }
            ?: posterElement?.attr("data-src")?.ifBlank { null }
            ?: posterElement?.attr("src")
        var poster = fixUrlNull(rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://"))
        if (poster.isNullOrBlank()) {
            val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
            if (ogImage != null && !ogImage.contains("logo", true) && !ogImage.contains("banner", true)) poster = fixUrlNull(ogImage)
        }
        val synopsis = document.selectFirst(".entry-content, .synp .entry-content, #synopsis, .desc")?.text()
        val genres = document.select("a[href*=/genres/], .genxed a").map { it.text() }

        fun parseEpisodeGrid(doc: org.jsoup.nodes.Document, currentUrl: String): List<Episode> {
            val elements = doc.select("div.eplister ul li, div.episodelist ul li, ul.episodelist li, div.ep_list ul li, .bixbox.bxcl ul li")
            return elements.mapNotNull { li ->
                val epLink = li.selectFirst("a")
                val epHref = if (epLink != null && epLink.hasAttr("href")) fixUrlNull(epLink.attr("href"))
                else if (li.hasClass("selected") || li.hasAttr("selected") || li.select("div.playinfo").isNotEmpty()) currentUrl
                else return@mapNotNull null
                if (epHref == null) return@mapNotNull null
                val epTitle = (epLink?.attr("title")?.ifBlank { epLink.text() } ?: li.text()).trim()
                val epNumText = li.selectFirst(".epl-num")?.text() ?: epTitle
                val epNum = Regex("(?i)episode\\s*(\\d+)").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(?i)ep\\s*(\\d+)").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("\\d+").find(epNumText)?.value?.toIntOrNull()
                newEpisode(epHref) {
                    this.name = epTitle.ifBlank { "Episode $epNum" }
                    this.episode = epNum
                }
            }.distinctBy { it.data }.reversed()
        }

        var episodes = parseEpisodeGrid(document, url)
        if (episodes.isEmpty()) {
            val firstEpLink = document.selectFirst(".epcurfirst a, .epcurlast a, .inepcx a, .bxcl a, a:matchesOwn((?i)watch)")?.attr("href")
            val anyEpLink = document.select("a[href]").firstOrNull {
                (it.attr("href").contains("-episode-") || it.attr("href").contains("-ep-")) && it.attr("href").contains(mainUrl)
            }?.attr("href")
            val fallbackHref = fixUrlNull(firstEpLink ?: anyEpLink)
            if (fallbackHref != null) episodes = parseEpisodeGrid(app.get(fallbackHref).document, fallbackHref)
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun fixRelativeUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> runCatching {
                val uri = URI(baseUrl); "${uri.scheme}://${uri.host}$trimmed"
            }.getOrNull() ?: trimmed
            else -> runCatching {
                val uri = URI(baseUrl); val path = uri.path.substringBeforeLast("/", "")
                "${uri.scheme}://${uri.host}$path/${trimmed.removePrefix("./")}"
            }.getOrNull() ?: trimmed
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || hex.length < 32) return null
        if (!hex.all { it in "0123456789abcdefABCDEF" }) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
        } catch (e: Exception) { null }
    }

    private fun aesDecrypt(blob: ByteArray, key: ByteArray, iv: ByteArray): String? {
        if (key.size !in intArrayOf(16, 24, 32) || iv.size != 16) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(blob), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

      override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val pageHtml = app.get(data, headers = mapOf("User-Agent" to UA)).text
        val document = Jsoup.parse(pageHtml)

        fun unescape(s: String): String = s
            .replace("\\/", "/")
            .replace("\\u002F", "/").replace("\\u002f", "/")
            .replace("\\u0026", "&").replace("&amp;", "&")

        fun extractStreamUrl(text: String): String? {
            Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(text)?.value?.let { return it }
            Regex("""https?://[^\s"'<>\\]*chunklist[^\s"'<>\\]*""").find(text)?.value?.let { return it }
            Regex("""https?://[^\s"'<>\\]*(?:rumble\.cloud|rmbl\.ws|tiktokcdn\.com)[^\s"'<>\\]*""").find(text)?.value?.let { return it }
            Regex("""//[a-z0-9][a-z0-9.-]+/[^\s"'<>\\]*\.m3u8[^\s"'<>\\]*""").find(text)?.value?.let { return "https:$it" }
            return null
        }

        suspend fun addM3u8(url: String, referer: String, label: String): Boolean {
            return try {
                val links = M3u8Helper.generateM3u8(label, url, referer)
                if (links.isNotEmpty()) {
                    for (l in links) callback(l)
                    true
                } else {
                    callback(newExtractorLink(source = name, name = label, url = url, type = ExtractorLinkType.M3U8) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                    })
                    true
                }
            } catch (e: Exception) {
                try {
                    callback(newExtractorLink(source = name, name = label, url = url, type = ExtractorLinkType.M3U8) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                    })
                    true
                } catch (e2: Exception) { false }
            }
        }

        val jsonUrlRegex = Regex("\"(?:url|source|sources|file|src|playlist|video|stream|playback|embed|iframe|link)\"\\s*:\\s*\"([^\"]+)\"")

        suspend fun scanForStream(text: String, referer: String, label: String): Boolean {
            val t = unescape(text)
            if (t.trim().startsWith("#EXTM3U")) {
                extractStreamUrl(t)?.let { if (addM3u8(it, referer, label)) return true }
                return false
            }
            extractStreamUrl(t)?.let { if (addM3u8(it, referer, label)) return true }
            for (m in jsonUrlRegex.findAll(t)) {
                val u = unescape(m.groupValues[1])
                if (u.startsWith("http") || u.startsWith("//")) {
                    if (addM3u8(if (u.startsWith("//")) "https:$u" else u, referer, label)) return true
                }
            }
            return false
        }

        suspend fun handleVpst(embedUrl: String): Boolean {
            try {
                val res = app.get(embedUrl, headers = mapOf("User-Agent" to UA, "Referer" to "https://videoplayerst.online/")).text
                if (scanForStream(res, "https://videoplayerst.online/", "VPST")) return true
            } catch (e: Exception) { }
            return false
        }

        suspend fun handleRumble(embedUrl: String): Boolean {
            try {
                val res = app.get(embedUrl, headers = mapOf("User-Agent" to UA, "Referer" to "https://rumble.com/")).text
                if (scanForStream(res, "https://rumble.com/", "Rumble")) return true
                try { if (loadExtractor(embedUrl, data, subtitleCallback, callback)) return true } catch (e: Exception) { }
            } catch (e: Exception) { }
            return false
        }

        suspend fun handleDailymotion(embedUrl: String): Boolean {
            val vid = Regex("""(?:video/|video=|embed/|player\.html\?video=)([a-zA-Z0-9_]+)""").find(embedUrl)?.groupValues?.get(1)
                ?: return false
            try {
                val meta = app.get("https://www.dailymotion.com/player/metadata/video/$vid", headers = mapOf("User-Agent" to UA)).text
                if (meta.contains("\"error\"") || !meta.contains("\"qualities\"")) return false
            } catch (e: Exception) { return false }
            return try {
                loadExtractor("https://www.dailymotion.com/video/$vid", data, subtitleCallback, callback)
            } catch (e: Exception) { false }
        }

        // Multi Player: Fetches API JSON config and extracts the stream URL
        suspend fun handleRpmStream(embedUrl: String): Boolean {
            try {
                var id = embedUrl.substringAfterLast("#", "").trim()
                if (id.isBlank() || id.contains("/")) {
                    id = embedUrl.substringAfter("?id=", "").substringBefore("&").trim()
                }
                if (id.isBlank()) {
                    id = embedUrl.substringAfterLast("/").trim()
                }
                if (id.isBlank()) return false

                val referer = "$RPM/"
                val hdr = mapOf("User-Agent" to UA, "Referer" to referer, "X-Requested-With" to "XMLHttpRequest")

                // The JS uses fetch('/api/...?id=' + hash) to get the video config as JSON
                val endpoints = listOf(
                    "$RPM/api/v1/video?id=$id",
                    "$RPM/api/v1/info?id=$id",
                    "$RPM/api/video?id=$id",
                    "$RPM/api/v1/video?id=$id&w=1280&h=800&r=chikianimation.online"
                )

                for (ep in endpoints) {
                    val res = try { app.get(ep, headers = hdr).text } catch (e: Exception) { continue }
                    if (res.isBlank()) continue
                    
                    // Direct M3U8 response
                    if (res.trim().startsWith("#EXTM3U")) { 
                        if (addM3u8(ep, referer, "Multi Player")) return true 
                    }
                    
                    // JSON response containing the stream URL
                    if (scanForStream(res, referer, "Multi Player")) return true
                    
                    // Base64 encoded response fallback
                    try {
                        val decoded = String(Base64.decode(res.trim(), Base64.DEFAULT))
                        if (scanForStream(decoded, referer, "Multi Player")) return true
                    } catch (e: Exception) {}
                }

                // Fallback: Fetch the embed page itself to find the exact API endpoint or inline sources
                val embedPage = try { 
                    app.get(embedUrl, headers = mapOf("User-Agent" to UA, "Referer" to mainUrl)).text 
                } catch (e: Exception) { "" }
                
                if (embedPage.isNotBlank()) {
                    // Look for dynamic API calls like fetch("/api/...id...")
                    val apiMatches = Regex("""fetch\s*\(\s*['"]([^'"]+)['"]""").findAll(embedPage)
                    for (match in apiMatches) {
                        var apiUrl = match.groupValues[1]
                        if (apiUrl.contains("id=") || apiUrl.contains("/api/")) {
                            apiUrl = apiUrl.replace("'+id+'", id).replace("\${id}", id).replace("+id+", id)
                            val fullApiUrl = if (apiUrl.startsWith("http")) apiUrl else "$RPM${apiUrl}"
                            val res = try { app.get(fullApiUrl, headers = hdr).text } catch (e: Exception) { "" }
                            if (res.isNotBlank() && scanForStream(res, referer, "Multi Player")) return true
                        }
                    }
                    
                    // Scan the embed page itself for any hidden sources
                    if (scanForStream(embedPage, referer, "Multi Player")) return true
                }
            } catch (e: Exception) { }
            return false
        }

        suspend fun handleEmbed(embedUrl: String): Boolean {
            val u = embedUrl.trim()
            if (u.isBlank()) return false
            return try {
                when {
                    u.contains("dailymotion", true) -> handleDailymotion(u)
                    u.contains("rpmstream.live", true) -> handleRpmStream(u)
                    u.contains("videoplayerst.online", true) -> handleVpst(u)
                    u.contains("rumble.com", true) || u.contains("rmbl.ws", true) -> handleRumble(u)
                    else -> false
                }
            } catch (e: Exception) { false }
        }

        // 1) iframes directly in page (default server)
        for (iframe in document.select("iframe[src], iframe[data-src]")) {
            val src = fixRelativeUrl(iframe.attr("src").ifBlank { iframe.attr("data-src") }, data) ?: continue
            try { if (handleEmbed(src)) found = true } catch (e: Exception) { }
        }

        // 2) base64 mirror options (server switcher, site order)
        for (option in document.select("option[value]")) {
            val value = option.attr("value")
            if (value.isBlank()) continue
            val decoded = try { String(Base64.decode(value, Base64.DEFAULT)) } catch (e: Exception) { value }
            val src = Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(decoded)?.groupValues?.get(1)
                ?: Regex("""(https?://[^\s"'<>]+)""").find(decoded)?.groupValues?.get(1)
                ?: continue
            val fixed = fixRelativeUrl(unescape(src), data) ?: continue
            try { if (handleEmbed(fixed)) found = true } catch (e: Exception) { }

            for (track in Jsoup.parse(decoded).select("track[src]")) {
                val subUrl = fixRelativeUrl(track.attr("src"), fixed)
                if (!subUrl.isNullOrBlank()) {
                    subtitleCallback(SubtitleFile(track.attr("label").ifBlank { "Subtitle" }, subUrl))
                }
            }
        }

        // 3) direct dailymotion embed in page
        if (!found) {
            val dm = Regex("""geo\.dailymotion\.com/player\.html\?video=([a-zA-Z0-9_]+)""").find(pageHtml)
                ?: Regex("""dailymotion\.com/(?:embed/)?video/([a-zA-Z0-9_]+)""").find(pageHtml)
            if (dm != null) {
                try { if (handleDailymotion("https://www.dailymotion.com/video/${dm.groupValues[1]}")) found = true } catch (e: Exception) { }
            }
        }

        // 4) raw stream url anywhere in page
        if (!found) {
            extractStreamUrl(unescape(pageHtml))?.let { m3u8 ->
                val referer = if (m3u8.contains("rumble.cloud") || m3u8.contains("rmbl.ws") || m3u8.contains("tiktokcdn")) "https://videoplayerst.online/" else "$mainUrl/"
                if (addM3u8(m3u8, referer, "Direct")) found = true
            }
        }

        return found
    }
}
