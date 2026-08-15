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
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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

    private data class GdTokens(
        val pd: String,
        val ps: String,
        val qsx: String,
        val kaken: String,
        val apx: String,
    )

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

        // The embed page carries an inline JSFuck program that, once decoded, is a
        // packr() call containing the per-page tokens (qsx, kaken, pd, ps, apx).
        val tokens = decodeGdTokens(page) ?: return false

        // 1) api-config: GET atob(apx) + qsx + "?p=" + ps -> encrypted blob -> dcx(pd) -> apiURL
        val apiConfigBase = String(Base64.decode(tokens.apx, Base64.DEFAULT), Charsets.UTF_8)
        val configRes = try {
            app.get(
                "$apiConfigBase${tokens.qsx}?p=${tokens.ps}",
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to embedUrl,
                    "Accept" to "text/plain, */*; q=0.01",
                )
            ).text
        } catch (e: Exception) { return false }
        val configPlain = dcx(configRes.trim(), tokens.pd) ?: return false
        val pConf = try { JSONObject(configPlain) } catch (e: Exception) { return false }
        val apiURL = pConf.optString("apiURL").ifBlank { pConf.optString("baseURL") }
        if (apiURL.isBlank()) return false
        val fixedApi = fixStreamUrl(apiURL, gxBase) ?: return false

        // 2) api: POST apiURL + "api/?p=" + ps with body=kaken, content-type text/plain.
        //    The response is encrypted with dcx(pd) and then JSON-parsed.
        val apiHeaders = mapOf(
            "User-Agent" to UA,
            "Referer" to embedUrl,
            "Origin" to gxBase,
            "Accept" to "text/plain, */*; q=0.01",
            "Content-Type" to "text/plain;charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
        )
        val apiBody = try {
            app.post(
                "${fixedApi.trimEnd('/')}/api/?p=${tokens.ps}",
                headers = apiHeaders,
                requestBody = tokens.kaken.toRequestBody("text/plain;charset=UTF-8".toMediaType()),
            ).text
        } catch (e: Exception) { return false }
        val apiPlain = dcx(apiBody.trim(), tokens.pd)
        val resJson = try {
            if (apiPlain != null) JSONObject(apiPlain)
            else JSONObject(apiBody.trim())
        } catch (e: Exception) { return false }
        if (resJson.optString("status") != "ok") return false
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
                    val links = M3u8Helper.generateM3u8("$name - $label", file, embedUrl)
                    if (links.isEmpty()) {
                        callback(
                            newExtractorLink(source = name, name = label, url = file, type = ExtractorLinkType.M3U8) {
                                this.referer = embedUrl
                                this.quality = quality
                            }
                        )
                    } else {
                        for (l in links) callback(l)
                    }
                } else {
                    callback(
                        newExtractorLink(source = name, name = label, url = file, type = ExtractorLinkType.VIDEO) {
                            this.referer = embedUrl
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

    /**
     * The embed page's inline script is JSFuck. Its string payload is an octal-escape
     * encoded packr() call, i.e. `eval(function(p,a,c,k,e,d){...}('PACKED',38,38,'DICT'.split('|')))`.
     * Decode it to plain JS and pull out the per-page tokens.
     */
    private fun decodeGdTokens(page: String): GdTokens? {
        val jStart = page.indexOf("ﾟωﾟﾉ=")
        if (jStart < 0) return null
        val jEnd = page.indexOf(") (ﾟΘﾟ)) ('_')", jStart)
        if (jEnd < 0) return null
        val jsfuck = page.substring(jStart, jEnd + ") (ﾟΘﾟ)) ('_')".length)

        // Body of the string literal: between `(ﾟεﾟ+/*´∇｀*/` and `) (ﾟΘﾟ)) ('_')`.
        val bStart = jsfuck.indexOf("(ﾟεﾟ+/*´∇｀*/")
        if (bStart < 0) return null
        val bodyStart = bStart + "(ﾟεﾟ+/*´∇｀*/".length
        val body = jsfuck.substring(bodyStart, jEnd)

        // Each char of the packr call is `\` + octal digits. Segments between the
        // `(ﾟДﾟ)[ﾟεﾟ]` markers hold the digits as single-digit arithmetic terms.
        val segs = body.split("(ﾟДﾟ)[ﾟεﾟ]")
        val sb = StringBuilder()
        for (i in 1 until segs.size) {
            val st = stripConstants(segs[i]).trim().trimStart('+').trimEnd('+')
            val digits = StringBuilder()
            for (term in splitTopLevelTerms(st)) {
                val t = term.trim()
                val v = when {
                    t.startsWith("-") -> -(evalArithmetic(t.substring(1)) ?: return null)
                    else -> evalArithmetic(t.trimStart('+'))
                } ?: return null
                if (v < 0 || v > 7) return null
                digits.append(v)
            }
            if (digits.isNotEmpty()) {
                sb.append(digits.toString().toInt(8).toChar())
            }
        }
        val packrCall = sb.toString()

        // Extract the pieces of `eval(function(...){...}('PACKED',38,38,'DICT'.split('|')))`.
        val pStart = packrCall.indexOf("}('")
        if (pStart < 0) return null
        val packedStart = pStart + 3
        val packedEnd = packrCall.indexOf("',", packedStart)
        if (packedEnd < 0) return null
        val packed = packrCall.substring(packedStart, packedEnd)
        val num1Start = packedEnd + 2
        val num1End = packrCall.indexOf(",", num1Start)
        if (num1End < 0) return null
        val a = packrCall.substring(num1Start, num1End).toIntOrNull() ?: return null
        val dictStartRaw = packrCall.indexOf(",'", num1End)
        if (dictStartRaw < 0) return null
        val dictStart = dictStartRaw + 2
        val dictEnd = packrCall.indexOf("'.split", dictStart)
        if (dictEnd < 0) return null
        val dict = packrCall.substring(dictStart, dictEnd).split("|")

        var code = packed
        for (c in a - 1 downTo 0) {
            val k = dict.getOrNull(c) ?: continue
            if (k.isEmpty()) continue
            code = code.replace(Regex("\\b" + packrBase36(c, a) + "\\b"), k)
        }

        fun grab(regex: Regex): String? = regex.find(code)?.groupValues?.get(1)?.trim()
        val pd = grab(Regex("""pd="([^"]+)"""")) ?: return null
        val ps = grab(Regex("""ps="([^"]+)"""")) ?: return null
        val qsx = grab(Regex("""window\.qsx="([^"]+)"""")) ?: return null
        val kaken = grab(Regex("""window\.kaken="([^"]+)"""")) ?: return null
        val apx = grab(Regex("""window\.apx="([^"]+)"""")) ?: return null
        if (pd.isBlank() || ps.isBlank() || qsx.isBlank() || kaken.isBlank() || apx.isBlank()) return null
        return GdTokens(pd, ps, qsx, kaken, apx)
    }

    private fun stripConstants(s: String): String {
        var t = s
        val repl = listOf(
            "(c^_^o)" to "0",
            "(o^_^o)" to "3",
            "(ﾟΘﾟ)" to "1",
            "(ﾟｰﾟ)" to "4",
            "c^_^o" to "0",
            "o^_^o" to "3",
            "ﾟΘﾟ" to "1",
            "ﾟｰﾟ" to "4",
        )
        for ((k, v) in repl) t = t.replace(k, v)
        return t
    }

    // Splits an expression into top-level additive terms, e.g. "1+4+(4+1)" -> ["1","+4","+(4+1)"].
    private fun splitTopLevelTerms(s: String): List<String> {
        val terms = mutableListOf<String>()
        var depth = 0
        var cur = StringBuilder()
        for (ch in s) {
            when (ch) {
                '(' -> { depth++; cur.append(ch) }
                ')' -> { depth--; cur.append(ch) }
                '+', '-' -> {
                    if (depth == 0) {
                        if (cur.isNotBlank()) terms.add(cur.toString())
                        cur = StringBuilder().append(ch)
                    } else cur.append(ch)
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotBlank()) terms.add(cur.toString())
        return terms.filter { it != "+" && it != "-" }
    }

    // Recursive-descent evaluator for expressions like "(4)+(4+1)" (digits, +, -, parens).
    // atom() and expr() are mutually recursive, so they're lambdas behind vars.
    private fun evalArithmetic(s: String): Int? {
        var pos = 0
        fun skip() { while (pos < s.length && s[pos] == ' ') pos++ }
        lateinit var expr: () -> Int?
        val atom: () -> Int? = {
            skip()
            if (pos >= s.length) return@atom null
            val c = s[pos]
            when {
                c == '(' -> {
                    pos++
                    val v = expr() ?: return@atom null
                    skip()
                    if (pos >= s.length || s[pos] != ')') return@atom null
                    pos++
                    v
                }
                c.isDigit() -> {
                    var v = 0
                    while (pos < s.length && s[pos].isDigit()) {
                        v = v * 10 + (s[pos] - '0')
                        pos++
                    }
                    v
                }
                else -> null
            }
        }
        expr = {
            var v = atom() ?: return@expr null
            while (true) {
                skip()
                if (pos >= s.length) return@expr v
                val c = s[pos]
                if (c == '+') { pos++; v += atom() ?: return@expr null }
                else if (c == '-') { pos++; v -= atom() ?: return@expr null }
                else return@expr v
            }
        }
        return expr()
    }

    // packr's e(c): the base-36 identifier for a dict index (e.g. 38 -> "10", 37 -> "B").
    private fun packrBase36(c: Int, a: Int): String {
        val prefix = if (c < a) "" else packrBase36(c / a, a)
        val rem = c % a
        val suffix = if (rem > 35) (rem + 29).toChar().toString() else Character.forDigit(rem, 36).toString()
        return prefix + suffix
    }

    // dcx: PBKDF2-HMAC-SHA256(password, salt, 10000, 48 bytes) -> key[0..32) + iv[32..48),
    // then AES-256-CBC. Matches CryptoJS: keySize 12 words, iterations 0x2710, hasher SHA256.
    private fun dcx(input: String, password: String): String? {
        val data = try { Base64.decode(input.trim(), Base64.DEFAULT) } catch (e: Exception) { return null }
        if (data.size < 16) return null
        val salt = data.copyOfRange(0, 16)
        val ct = data.copyOfRange(16, data.size)
        val derived = pbkdf2Sha256(password.toByteArray(Charsets.UTF_8), salt, 10000, 48) ?: return null
        return aesDecrypt(ct, derived.copyOfRange(0, 32), derived.copyOfRange(32, 48))
    }

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray? {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            val out = ByteArray(dkLen)
            val hLen = 32
            val blocks = (dkLen + hLen - 1) / hLen
            var offset = 0
            for (block in 1..blocks) {
                val u = ByteArray(salt.size + 4)
                salt.copyInto(u)
                val bi = block
                u[salt.size] = ((bi ushr 24) and 0xFF).toByte()
                u[salt.size + 1] = ((bi ushr 16) and 0xFF).toByte()
                u[salt.size + 2] = ((bi ushr 8) and 0xFF).toByte()
                u[salt.size + 3] = (bi and 0xFF).toByte()
                var t = mac.doFinal(u)
                var last = t
                var i = 1
                while (i < iterations) {
                    last = mac.doFinal(last)
                    for (j in t.indices) t[j] = (t[j].toInt() xor last[j].toInt()).toByte()
                    i++
                }
                val n = minOf(hLen, dkLen - offset)
                t.copyInto(out, offset, 0, n)
                offset += n
            }
            out
        } catch (e: Exception) { null }
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

    private fun aesDecrypt(blob: ByteArray, key: ByteArray, iv: ByteArray): String? {
        if (key.size !in intArrayOf(16, 24, 32) || iv.size != 16) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(blob), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }
}
