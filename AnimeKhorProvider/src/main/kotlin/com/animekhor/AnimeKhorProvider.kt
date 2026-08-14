package com.animekhor

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
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

        if (host.contains("dailymotion.com") || host.contains("dai.ly")) {
            val vidId = Regex("""(?:video/|video=|embed/|/video/)([a-zA-Z0-9_]+)""").find(clean)?.groupValues?.get(1)
            if (vidId != null && loadExtractor("https://www.dailymotion.com/video/$vidId", referer, subtitleCallback, callback)) return true
        }

        if (host.contains("ok.ru") || host.contains("odnoklassniki")) {
            if (loadExtractor(clean, referer, subtitleCallback, callback)) return true
        }

        if (host.contains("rumble.com")) {
            if (resolveRumble(clean, label, subtitleCallback, callback)) return true
        }

        if (host.contains("d.tube")) {
            if (resolveDtube(clean, label, subtitleCallback, callback)) return true
        }

        if (host.contains("turbovid") || host.contains("emturbovid") || host.contains("turboviplay")) {
            if (resolveTurbovid(clean, label, referer, subtitleCallback, callback)) return true
        }

        if (host.contains("upns.live") || host.contains("p2pstream.vip")) {
            if (resolveUpns(clean, label, subtitleCallback, callback)) return true
        }

        if (host.contains("bysekoze") || host.contains("byse")) {
            if (resolveByse(clean, label, subtitleCallback, callback)) return true
        }

        if (host.contains("abyssplayer") || host.contains("abyss")) {
            if (resolveAbyss(clean, label, referer, subtitleCallback, callback)) return true
        }

        // generic: native cloudstream extractors (turbovid, vidhide, streamwish, ...)
        try {
            if (loadExtractor(clean, referer, subtitleCallback, callback)) return true
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

    private suspend fun resolveTurbovid(
        embedUrl: String,
        label: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = embedUrl.replace("\\/", "/")
        val html = try { app.get(clean).text } catch (e: Exception) { return false }
        val m3u8 = Regex("""var urlPlay\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-hash=["']([^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)?.value
        if (m3u8 == null) return false
        try {
            val subJson = Regex("""var urlSub\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            if (subJson != null) {
                val subText = app.get(subJson).text
                for (m in Regex("\"file\"\\s*:\\s*\"([^\"]+\\.(?:vtt|srt)[^\"]*)\"").findAll(subText)) {
                    subtitleCallback(
                        newSubtitleFile("English", m.groupValues[1]) {
                            this.headers = mapOf("Referer" to clean, "User-Agent" to USER_AGENT)
                        }
                    )
                }
            }
        } catch (e: Exception) { }
        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = clean
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("Referer" to clean, "User-Agent" to USER_AGENT)
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
