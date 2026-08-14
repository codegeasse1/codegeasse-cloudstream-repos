package com.chiki2d

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
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ChikiAni2dProvider : MainAPI() {
    override var mainUrl = "https://chikianimation.com"
    override var name = "ChikiAni2d"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val GX = "https://galaxydonghua.xyz"
        // GDPlayer's playerConfig is encrypted with CryptoJS AES-JSON using a static passphrase
        // (EVP_BytesToKey(MD5) + salt -> AES-256-CBC). Verified against the GDPlayer v3 assets.
        private const val GDP_KEY = "F1r3b4Ll_GDP~5H"
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
        val href = fixUrlNull(linkEl.attr("href")) ?: return null
        val title = linkEl.attr("title").ifBlank { this.selectFirst("div.tt")?.text() }?.trim() ?: return null
        val img = this.selectFirst("img")
        val rawPoster = img?.attr("data-lazy-src")?.ifBlank { null }
            ?: img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("src")
        val posterUrl = fixUrlNull(
            rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://")
        )
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
        val posterElement = document.selectFirst(
            ".bigcontent .thumb img, .bixbox .thumb img, article .thumb img, .infox .imgbox img, .ts-post-image"
        )
        val rawPoster = posterElement?.attr("data-lazy-src")?.ifBlank { null }
            ?: posterElement?.attr("data-src")?.ifBlank { null }
            ?: posterElement?.attr("src")
        var poster = fixUrlNull(
            rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://")
        )
        if (poster.isNullOrBlank()) {
            val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
            if (ogImage != null && !ogImage.contains("logo", true) && !ogImage.contains("banner", true)) {
                poster = fixUrlNull(ogImage)
            }
        }
        val synopsis = document.selectFirst(".synp .entry-content, .entry-content")?.text()
        val genres = document.select("a[href*=/genres/], .genxed a").map { it.text() }

        val episodes = document
            .select(".eplister ul li, div.episodelist ul li, ul.episodelist li, .bixbox.bxcl ul li")
            .mapNotNull { li ->
                val epLink = li.selectFirst("a")
                val epHref = if (epLink != null && epLink.hasAttr("href")) fixUrlNull(epLink.attr("href")) else null
                if (epHref == null) return@mapNotNull null
                val epTitle = (epLink?.attr("title")?.ifBlank { epLink?.text() ?: "" } ?: li.text()).trim()
                val epNumText = li.selectFirst(".epl-num")?.text() ?: epTitle
                val epNum = Regex("(?i)(?:episode|ep)\\s*(\\d+)").find(epNumText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("\\d+").find(epNumText)?.value?.toIntOrNull()
                newEpisode(epHref) {
                    this.name = epNumText.ifBlank { "Episode $epNum" }
                    this.episode = epNum
                }
            }
            .distinctBy { it.data }
            .reversed()

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
        var found = false
        val pageHtml = try {
            app.get(data, headers = mapOf("User-Agent" to UA)).text
        } catch (e: Exception) {
            ""
        }
        if (pageHtml.isBlank()) return false
        val document = Jsoup.parse(pageHtml)

        fun unescape(s: String): String = s
            .replace("\\/", "/")
            .replace("\\u002F", "/").replace("\\u002f", "/")
            .replace("\\u0026", "&").replace("&amp;", "&")

        fun extractStreamUrl(text: String): String? {
            Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(text)?.value?.let { return it }
            Regex("""https?://[^\s"'<>\\]*chunklist[^\s"'<>\\]*""").find(text)?.value?.let { return it }
            return null
        }

        // 1) Default "All sub player" - galaxydonghua (GDPlayer) iframe directly in the page
        val gxIframe = document.select("#pembed iframe[src], #embed_holder iframe[src], iframe[src]").firstOrNull {
            it.attr("src").contains("galaxydonghua", true) || it.attr("data-src").contains("galaxydonghua", true)
        }
        if (gxIframe != null) {
            val src = fixRelativeUrl(gxIframe.attr("src").ifBlank { gxIframe.attr("data-src") }, data)
            if (src != null) {
                try { if (handleGdplayer(src, data, subtitleCallback, callback)) found = true } catch (e: Exception) { }
            }
        }

        // 2) Dailymotion player (mirror /v/2/ and/or geo.dailymotion embeds anywhere)
        val dmIds = LinkedHashSet<String>()
        Regex("geo\\.dailymotion\\.com/player/[a-zA-Z0-9_]+\\.html\\?video=([a-zA-Z0-9_]+)")
            .findAll(pageHtml).forEach { dmIds.add(it.groupValues[1]) }
        Regex("dailymotion\\.com/(?:embed/)?video/([a-zA-Z0-9_]+)")
            .findAll(pageHtml).forEach { dmIds.add(it.groupValues[1]) }

        // 3) Mirror switcher options (load extra servers when the default one fails)
        for (option in document.select("select.mirror option[value]")) {
            val v = option.attr("value").trim()
            if (v.isBlank() || !v.contains("/v/")) continue
            val mirrorUrl = fixRelativeUrl(v, data) ?: continue
            val mirrorHtml = try {
                app.get(mirrorUrl, headers = mapOf("User-Agent" to UA)).text
            } catch (e: Exception) {
                continue
            }
            if (v.contains("/v/2/")) {
                Regex("geo\\.dailymotion\\.com/player/[a-zA-Z0-9_]+\\.html\\?video=([a-zA-Z0-9_]+)")
                    .find(mirrorHtml)?.let { dmIds.add(it.groupValues[1]) }
            } else if (v.contains("/v/1/") && !found) {
                // Retry the galaxydonghua embed with the mirror page as referer
                val src = Regex("galaxydonghua\\.xyz/embed/[^\"'\\s>]+").find(mirrorHtml)?.value
                if (src != null) {
                    try { if (handleGdplayer("https://$src", mirrorUrl, subtitleCallback, callback)) found = true } catch (e: Exception) { }
                }
            }
        }

        // 4) Dailymotion fallback links
        for (id in dmIds) {
            if (found) break
            try { if (handleDailymotion(id, data, subtitleCallback, callback)) found = true } catch (e: Exception) { }
        }

        // 5) Raw stream URL anywhere in the page
        if (!found) {
            extractStreamUrl(unescape(pageHtml))?.let { m3u8 ->
                try {
                    if (M3u8Helper.generateM3u8(name, m3u8, "$mainUrl/").isNotEmpty()) found = true
                } catch (e: Exception) { }
            }
        }

        return found
    }

    private suspend fun handleGdplayer(
        embedUrl: String,
        refererPage: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedHost = if (embedUrl.contains("galaxydonghua", true)) {
            embedUrl.substringBefore("/embed").ifBlank { GX }.let { if (it.startsWith("http")) it else "https:$it" }
        } else {
            embedUrl.substringBefore("/embed").ifBlank { GX }
        }
        val gxBase = if (embedHost.startsWith("http")) embedHost else GX

        // The /embed/ route is gated: only browsers/iframe-like requests are served.
        val headers = mapOf(
            "User-Agent" to UA,
            "Referer" to refererPage,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "sec-fetch-dest" to "iframe",
            "sec-fetch-mode" to "navigate",
            "sec-fetch-site" to "cross-site",
        )
        val page = try { app.get(embedUrl, headers = headers).text } catch (e: Exception) { return false }
        val configMatch = Regex("playerConfig\\s*=\\s*(\\{[^;]+?\\})\\s*;").find(page) ?: return false
        val configJson = try { JSONObject(configMatch.groupValues[1]) } catch (e: Exception) { return false }
        val ct = configJson.optString("ct")
        val saltHex = configJson.optString("s")
        if (ct.isBlank() || saltHex.isBlank()) return false

        val salt = hexToBytes(saltHex) ?: return false
        val derived = evpBytesToKey(GDP_KEY.toByteArray(Charsets.UTF_8), salt, 48) ?: return false
        val plain = aesDecrypt(
            Base64.decode(ct, Base64.DEFAULT),
            derived.copyOfRange(0, 32),
            derived.copyOfRange(32, 48)
        ) ?: return false
        val pConf = try { JSONObject(plain) } catch (e: Exception) { return false }

        val apiURL = pConf.optString("apiURL").ifBlank { pConf.optString("baseURL") }
        val apiQuery = pConf.optString("apiQuery").ifBlank { pConf.optString("query") }
        if (apiURL.isBlank() || apiQuery.isBlank()) return false
        val fixedApi = fixStreamUrl(apiURL, gxBase) ?: return false

        val apiHeaders = mapOf(
            "User-Agent" to UA,
            "Referer" to gxBase,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
        )
        val apiRes = try {
            app.get("${fixedApi.trimEnd('/')}/api/?$apiQuery", headers = apiHeaders).text
        } catch (e: Exception) { return false }
        val resJson = try { JSONObject(apiRes) } catch (e: Exception) { return false }
        val sources = resJson.optJSONArray("sources") ?: return false
        if (sources.length() == 0) return false

        val baseURL = pConf.optString("baseURL").ifBlank { gxBase }
        var any = false
        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            val file = fixStreamUrl(src.optString("file"), baseURL) ?: continue
            val label = src.optString("label").ifBlank { "Stream" }
            val type = src.optString("type")
            val isM3u8 = type.contains("mpegurl", true) || type.contains("hls", true) || file.contains(".m3u8", true)
            val quality = label.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value
            try {
                if (isM3u8) {
                    val links = M3u8Helper.generateM3u8("$name - $label", file, gxBase)
                    if (links.isEmpty()) {
                        callback(
                            newExtractorLink(source = name, name = label, url = file, type = ExtractorLinkType.M3U8) {
                                this.referer = gxBase
                                this.quality = quality
                            }
                        )
                    } else {
                        for (l in links) callback(l)
                    }
                } else {
                    callback(
                        newExtractorLink(source = name, name = label, url = file, type = ExtractorLinkType.VIDEO) {
                            this.referer = gxBase
                            this.quality = quality
                        }
                    )
                }
                any = true
            } catch (e: Exception) { }
        }

        val tracks = resJson.optJSONArray("tracks")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val tr = tracks.optJSONObject(i) ?: continue
                val file = fixStreamUrl(tr.optString("file"), baseURL) ?: continue
                try {
                    subtitleCallback(SubtitleFile(tr.optString("label").ifBlank { "Subtitle" }, file))
                } catch (e: Exception) { }
            }
        }

        return any
    }

    private suspend fun handleDailymotion(
        videoId: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            loadExtractor("https://www.dailymotion.com/video/$videoId", referer, subtitleCallback, callback)
        } catch (e: Exception) { false }
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

    private fun fixStreamUrl(url: String, base: String): String? {
        val u = url.trim()
        if (u.isBlank()) return null
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return "https:$u"
        val host = runCatching { val uri = URI(base); "${uri.scheme}://${uri.host}" }.getOrNull()
        return when {
            u.startsWith("/") -> if (host != null) "$host$u" else "${base.trimEnd('/')}$u"
            else -> if (host != null) "$host/$u" else "${base.trimEnd('/')}/$u"
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val h = hex.trim()
        if (h.length % 2 != 0) return null
        if (!h.all { it in "0123456789abcdefABCDEF" }) return null
        return try {
            ByteArray(h.length / 2) { i ->
                ((Character.digit(h[i * 2], 16) shl 4) + Character.digit(h[i * 2 + 1], 16)).toByte()
            }
        } catch (e: Exception) { null }
    }

    // OpenSSL EVP_BytesToKey (MD5, single iteration) as used by CryptoJS password-based AES.
    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, numBytes: Int): ByteArray? {
        return try {
            val out = ByteArray(numBytes)
            val md = MessageDigest.getInstance("MD5")
            var prev: ByteArray? = null
            var filled = 0
            while (filled < numBytes) {
                md.reset()
                prev?.let { md.update(it) }
                md.update(password)
                md.update(salt)
                val digest = md.digest()
                digest.copyInto(out, filled)
                filled += digest.size
                prev = digest
            }
            out
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
}
