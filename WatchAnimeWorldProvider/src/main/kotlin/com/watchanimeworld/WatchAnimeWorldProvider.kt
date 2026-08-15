package com.watchanimeworld

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import android.util.Base64
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject
import org.json.JSONArray
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.lagradost.nicehttp.NiceResponse

@CloudstreamPlugin
class WatchAnimeWorldPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WatchAnimeWorldProvider())
    }
}

class WatchAnimeWorldProvider : MainAPI() {
    override var mainUrl = "https://watchanimeworld.top"
    override var name = "Anime World India"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private val postHeaders = mapOf(
        "User-Agent" to userAgent,
        "Content-Type" to "application/x-www-form-urlencoded",
        "X-Requested-With" to "XMLHttpRequest"
    )

    // the zephyrix player sets cookies when visited; keep them for the getVideo call
    private val sessionCookies = mutableMapOf<String, String>()

    private fun captureCookies(response: NiceResponse) {
        try {
            response.cookies.forEach { (k, v) -> sessionCookies[k] = v }
        } catch (e: Exception) {
        }
    }

    private suspend fun getPage(url: String, referer: String? = null): NiceResponse? {
        return try {
            val r = app.get(url, headers = browserHeaders, referer = referer, cookies = sessionCookies.toMap())
            captureCookies(r)
            r
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun postPage(url: String, data: Map<String, String>, referer: String? = null): NiceResponse? {
        return try {
            val r = app.post(url, headers = postHeaders, referer = referer, data = data, cookies = sessionCookies.toMap())
            captureCookies(r)
            r
        } catch (e: Exception) {
            null
        }
    }

    private fun fixPoster(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    // ---- catalog cards: <article class="post"> with <a class="lnk-blk"> link,
    // .entry-title title, .post-thumbnail img poster (homepage + search + seasons)
    private fun extractItems(doc: Element): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        for (article in doc.select("article.post")) {
            val a = article.selectFirst("a.lnk-blk") ?: continue
            val url = fixUrlNull(a.attr("href")) ?: continue
            val title = article.selectFirst(".entry-title")?.text()?.trim() ?: continue
            if (title.isBlank()) continue
            val poster = fixPoster(article.selectFirst(".post-thumbnail img")?.attr("src"))
            items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
            })
        }
        return items.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val html = getPage(pageUrl)?.text ?: return newHomePageResponse(emptyList<HomePageList>(), hasNext = false)
        val items = extractItems(Jsoup.parse(html))
        return newHomePageResponse(listOf(HomePageList("Latest", items)), hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = getPage("$mainUrl/?s=${query.trim().replace(" ", "+")}")?.text ?: return emptyList()
        return extractItems(Jsoup.parse(html))
    }

    // ---- detail: /series/<slug>/ with season tabs (data-season) that load
    // episodes via admin-ajax action_select_season
    override suspend fun load(url: String): LoadResponse {
        val html = getPage(url)?.text ?: throw ErrorLoadingException("Failed to load page")
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixPoster(it) }
            ?: doc.selectFirst(".post-thumbnail img")?.attr("src")?.let { fixPoster(it) }

        val plot = doc.selectFirst("div.description p")?.text()?.trim()
            ?: doc.selectFirst("div.description")?.text()?.trim() ?: ""

        val year = doc.selectFirst("span.year")?.text()
            ?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val duration = doc.selectFirst("span.duration")?.text()
            ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val tags = doc.select("a[href*=/category/]")
            .mapNotNull { it.text().trim().ifBlank { null } }
            .filter { t -> t.length > 1 && !listOf("Hindi", "Tamil", "Telugu", "English", "Japanese", "Sub").contains(t, true) }
            .distinct()
            .take(10)

        // season tabs + post id
        val seasonLinks = doc.select(".sel-temp a[data-season]")
        val postId = seasonLinks.firstOrNull()?.attr("data-post")
        val seasons = seasonLinks.mapNotNull { it.attr("data-season").trim().toIntOrNull() }.distinct()

        val episodes = mutableListOf<Episode>()
        if (postId != null && seasons.isNotEmpty()) {
            for (season in seasons) {
                val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php?action=action_select_season&season=$season&post=$postId"
                val seasonHtml = getPage(ajaxUrl, referer = url)?.text ?: continue
                val seasonDoc = Jsoup.parse(seasonHtml)
                var ep = 0
                for (item in seasonDoc.select("article.post")) {
                    val link = item.selectFirst("a.lnk-blk") ?: continue
                    val epUrl = fixUrlNull(link.attr("href")) ?: continue
                    val numEpi = item.selectFirst(".num-epi")?.text()?.trim()
                    var epNum = numEpi?.let {
                        Regex("""(?i)x\s*(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull()
                    }
                    if (epNum == null) {
                        ep++
                        epNum = ep
                    }
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = "S${season}E${if (epNum < 10) "0$epNum" else epNum}"
                            this.season = season
                            this.episode = epNum
                        }
                    )
                }
            }
        }

        // fallback: the default season already rendered on the page
        if (episodes.isEmpty()) {
            for (item in doc.select("#episode_by_temp article.post")) {
                val link = item.selectFirst("a.lnk-blk") ?: continue
                val epUrl = fixUrlNull(link.attr("href")) ?: continue
                val numEpi = item.selectFirst(".num-epi")?.text()?.trim()
                val (s, e) = parseNumEpi(numEpi)
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = "S${s}E${if (e < 10) "0$e" else e}"
                        this.season = s
                        this.episode = e
                    }
                )
            }
        }

        if (episodes.isEmpty()) throw ErrorLoadingException("No episodes found")

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.duration = duration
        }
    }

    private fun parseNumEpi(numEpi: String?): Pair<Int, Int> {
        if (numEpi != null) {
            val m = Regex("""(\d+)\s*x\s*(\d+)""", RegexOption.IGNORE_CASE).find(numEpi)
            if (m != null) {
                return (m.groupValues[1].toIntOrNull() ?: 1) to (m.groupValues[2].toIntOrNull() ?: 1)
            }
        }
        return 1 to 1
    }

    // ---- links: episode page -> play.zephyrix.top/video/<hash> ->
    // POST player/index.php?data=<hash>&do=getVideo -> {hls, videoSource, videoSources, ck}
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val pageUrl = data.substringBefore("#").substringBefore("?")
            val html = getPage(pageUrl)?.text ?: return false
            val doc = Jsoup.parse(html)

            val hashes = doc.select("iframe[src*=zephyrix]").mapNotNull {
                Regex("""zephyrix\.top/video/([0-9a-f]+)""", RegexOption.IGNORE_CASE).find(it.attr("src"))?.groupValues?.get(1)
            }.distinct()

            for (hash in hashes) {
                if (resolveZephyrix(hash, pageUrl, callback)) found = true
            }

            // fallback: the per-language short.icu links embedded in the player1.php iframe
            if (!found) {
                for (iframe in doc.select("iframe[src*=player1.php]")) {
                    val dataB64 = Regex("""data=([A-Za-z0-9+/=]+)""").find(iframe.attr("src"))?.groupValues?.get(1)
                    if (dataB64 == null) continue
                    val decoded = try {
                        String(Base64.decode(dataB64, Base64.DEFAULT))
                    } catch (e: Exception) {
                        continue
                    }
                    val arr = try {
                        JSONArray(decoded)
                    } catch (e: Exception) {
                        continue
                    }
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val lang = obj.optString("language", "").ifBlank { "Anime World" }
                        val link = obj.optString("link", "").replace("\\/", "/")
                        if (link.isNotBlank()) {
                            emitLink(link, lang, callback, "$mainUrl/")
                            found = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return found
    }

    private suspend fun resolveZephyrix(hash: String, pageUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val playerUrl = "https://play.zephyrix.top/video/$hash"
            getPage(playerUrl, referer = pageUrl) // visit for session cookies

            val resp = postPage(
                "https://play.zephyrix.top/player/index.php?data=$hash&do=getVideo",
                mapOf("hash" to hash, "r" to pageUrl),
                referer = playerUrl
            ) ?: return false
            if (!resp.isSuccessful) return false

            val json = try {
                JSONObject(resp.text)
            } catch (e: Exception) {
                return false
            }

            var found = false
            if (json.optBoolean("hls", false)) {
                val src = json.optString("videoSource", "")
                if (src.isNotBlank()) {
                    emitLink(src, "Zephyrix", callback, "https://play.zephyrix.top/")
                    found = true
                }
            } else {
                val ck = json.optString("ck", "")
                val sources = json.optJSONArray("videoSources")
                if (sources != null) {
                    for (i in 0 until sources.length()) {
                        val obj = sources.optJSONObject(i) ?: continue
                        val label = obj.optString("label", "").ifBlank { obj.optString("quality", "").ifBlank { "Zephyrix" } }
                        val enc = obj.optString("file", "")
                        val dec = decryptAes(enc, ck)
                        if (!dec.isNullOrBlank()) {
                            emitLink(dec, label, callback, "https://play.zephyrix.top/")
                            found = true
                        }
                    }
                }
            }
            found
        } catch (e: Exception) {
            false
        }
    }

    // CryptoJSAesJson format: {"ct":"<b64>","iv":"<b64>","s":"<b64>"} encrypted with
    // AES/CBC, key = base64(ck). Decrypts to the plain video url.
    private fun decryptAes(jsonStr: String, ck: String): String? {
        return try {
            if (jsonStr.isBlank()) return null
            val j = JSONObject(jsonStr)
            val keyB64 = if (ck.contains("\\x")) {
                Regex("""\\x([0-9a-fA-F]{2})""").replace(ck) { m -> m.groupValues[1].toInt(16).toChar().toString() }
            } else ck
            val key = Base64.decode(keyB64, Base64.DEFAULT)
            val iv = Base64.decode(j.getString("iv"), Base64.DEFAULT)
            val ct = Base64.decode(j.getString("ct"), Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun emitLink(url: String, label: String, callback: (ExtractorLink) -> Unit, referer: String) {
        val clean = url.trim().trim('"', '\'')
        if (clean.isBlank()) return
        val isM3u8 = clean.contains(".m3u8", true)
        callback(
            newExtractorLink(
                source = name,
                name = label.take(40).ifBlank { name },
                url = clean,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = referer
                this.headers = mapOf("User-Agent" to userAgent, "Referer" to referer)
            }
        )
    }
}
