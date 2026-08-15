package com.moviesmod

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject
import com.lagradost.nicehttp.NiceResponse
import java.net.URI

@CloudstreamPlugin
class MoviesmodPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MoviesmodProvider())
    }
}

class MoviesmodProvider : MainAPI() {
    override var mainUrl = "https://moviesmod.zone"
    override var name = "MoviesMod"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private val postHeaders = mapOf(
        "User-Agent" to userAgent,
        "Content-Type" to "application/x-www-form-urlencoded"
    )

    // the SID dance and the driveseed/driveleech pages keep session state in cookies,
    // and NiceHttp builds a fresh client per request, so we carry the jar ourselves.
    private val sessionCookies = mutableMapOf<String, String>()

    private fun captureCookies(response: NiceResponse) {
        try {
            response.cookies.forEach { (k, v) -> sessionCookies[k] = v }
        } catch (e: Exception) {
        }
    }

    private suspend fun getPage(url: String, referer: String? = null, extraHeaders: Map<String, String> = emptyMap()): NiceResponse? {
        return try {
            val r = app.get(url, headers = browserHeaders + extraHeaders, referer = referer, cookies = sessionCookies.toMap())
            captureCookies(r)
            r
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun postPage(url: String, data: Map<String, String>, referer: String? = null, extraHeaders: Map<String, String> = emptyMap()): NiceResponse? {
        return try {
            val r = app.post(url, headers = postHeaders + extraHeaders, referer = referer, data = data, cookies = sessionCookies.toMap())
            captureCookies(r)
            r
        } catch (e: Exception) {
            null
        }
    }

    // ---- catalog cards (homepage + search) ----

    private fun extractItems(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()
        for (article in doc.select("article.latestPost.excerpt")) {
            val a = article.selectFirst("h2.title.front-view-title a") ?: continue
            val url = fixUrlNull(a.attr("href")) ?: continue
            val title = a.text().trim()
            if (title.isBlank()) continue
            val poster = article.selectFirst(".featured-thumbnail img")?.attr("src")?.ifBlank { null } ?: ""
            val isSeries = Regex("""(?i)\bseason\b|\bs\d{1,2}\b""").containsMatchIn(title)
            val response = if (isSeries) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            items.add(response)
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val html = getPage(pageUrl)?.text ?: return HomePageResponse(emptyList(), hasNext = false)
        val items = extractItems(html)
        return HomePageResponse(listOf(HomePageList("Latest Movies", items)), hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = getPage("$mainUrl/?s=${query.trim().replace(" ", "+")}")?.text ?: return emptyList()
        return extractItems(html)
    }

    // ---- movie / series detail ----

    private data class QualityGroup(val label: String, val season: Int, val quality: String, val pageUrl: String)

    // each "Episode Links" button on a moviesmod page leads to an episodes.modpro.blog
    // page for one (season, quality) pair; the button sits right below a heading that
    // names the season + quality.
    private fun parseQualityGroups(doc: Element): List<QualityGroup> {
        val groups = mutableListOf<QualityGroup>()
        for (heading in doc.select(".entry-content h2, .entry-content h3")) {
            val headingText = heading.text().trim()
            if (headingText.isBlank()) continue
            val season = Regex("""(?i)\bseason\s*(\d+)""").find(headingText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(?i)\bs(\d{1,2})\b""").find(headingText)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            val quality = Regex("""(?i)\b(\d{3,4}p)\b""").find(headingText)?.groupValues?.get(1)?.uppercase() ?: "HD"
            var el: Element? = heading.nextElementSibling()
            while (el != null) {
                if (el.tagName() == "h2" || el.tagName() == "h3") break
                val btn = el.selectFirst("a.maxbutton-episode-links")
                if (btn != null) {
                    val href = btn.attr("href")
                    if (href.contains("episodes.modpro.blog")) {
                        groups.add(QualityGroup(headingText, season, quality, href))
                    }
                }
                el = el.nextElementSibling()
            }
        }
        if (groups.isEmpty()) {
            for (a in doc.select("a.maxbutton-episode-links")) {
                val href = a.attr("href")
                if (href.contains("episodes.modpro.blog")) {
                    groups.add(QualityGroup(a.text(), 1, "HD", href))
                }
            }
        }
        return groups.distinctBy { it.pageUrl }
    }

    // an episodes.modpro.blog page contains one gateway link per episode
    private fun extractEpisodeLinks(html: String): List<Pair<String, Int>> {
        val doc = Jsoup.parse(html)
        val anchors = doc.select(".entry-content a[href*=unblockedgames.world], .entry-content a[href*=driveseed.org], .entry-content a[href*=driveleech.net]")
        val anchorsAll = if (anchors.isEmpty()) {
            doc.select("a[href*=unblockedgames.world], a[href*=driveseed.org], a[href*=driveleech.net]")
        } else anchors
        val out = mutableListOf<Pair<String, Int>>()
        var idx = 0
        for (a in anchorsAll) {
            val href = a.attr("href").trim()
            val text = a.text().trim()
            if (href.isBlank()) continue
            if (text.contains("batch", true) || text.contains("comment", true)) continue
            idx++
            val num = Regex("""(?i)(?:episode|ep)?\s*:?\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: idx
            out.add(href to num)
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val html = getPage(url)?.text ?: throw ErrorLoadingException("Failed to load page")
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()?.removePrefix("Download ")?.trim() ?: "Unknown"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst(".featured-thumbnail img")?.attr("src")?.ifBlank { null } ?: ""
        val plot = doc.selectFirst("div.imdbwp__teaser")?.text()?.trim()
            ?: doc.selectFirst("#movieSynopsis p")?.text()?.trim()
            ?: ""
        val year = doc.selectFirst("div.imdbwp__header")?.text()?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.selectFirst("div.imdbwp__meta")?.text()
            ?.split("|")?.getOrNull(1)?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

        val groups = parseQualityGroups(doc)
        val pageText = doc.select(".entry-content").text()
        val isSeries = Regex("""(?i)\bseason\s*\d|\bs\d{1,2}\b""").containsMatchIn(pageText) ||
            Regex("""(?i)\bseason\b""").containsMatchIn(title)

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            for (group in groups) {
                val pageHtml = getPage(group.pageUrl, referer = url)?.text ?: continue
                for ((sidUrl, epNum) in extractEpisodeLinks(pageHtml)) {
                    episodes.add(
                        newEpisode("$sidUrl|${group.pageUrl}|${group.label}") {
                            this.name = "S${group.season}E${if (epNum < 10) "0$epNum" else epNum} • ${group.quality}"
                            this.season = group.season
                            this.episode = epNum
                        }
                    )
                }
            }
            if (episodes.isEmpty()) throw ErrorLoadingException("No episodes found")
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    // ---- link resolution ----

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            if (data.contains("|")) {
                // series episode: sidUrl|episodesPage|label (label may itself contain |)
                val sidUrl = data.substringBefore("|")
                val rest = data.substringAfter("|")
                val pageUrl = rest.substringBefore("|")
                val label = rest.substringAfter("|", "")
                if (sidUrl.isNotBlank() && pageUrl.isNotBlank()) {
                    val stream = resolveToStream(sidUrl, pageUrl)
                    if (stream != null) {
                        emitStream(stream, label.take(40).ifBlank { "MoviesMod" }, callback)
                        found = true
                    }
                }
            } else {
                // movie: resolve every quality's episode page
                val movieUrl = data.substringBefore("#").substringBefore("?")
                val html = getPage(movieUrl)?.text ?: return false
                val doc = Jsoup.parse(html)
                for (group in parseQualityGroups(doc)) {
                    try {
                        val pageHtml = getPage(group.pageUrl, referer = movieUrl)?.text ?: continue
                        val sid = extractEpisodeLinks(pageHtml).firstOrNull()?.first ?: continue
                        val stream = resolveToStream(sid, group.pageUrl) ?: continue
                        emitStream(stream, "MoviesMod • ${group.quality}", callback)
                        found = true
                    } catch (e: Exception) {
                        // try the next quality
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return found
    }

    private suspend fun resolveToStream(sidUrl: String, referer: String): String? {
        val driveleech = resolveSidLink(sidUrl, referer) ?: return null
        return resolveDriveleech(driveleech)
    }

    // cloud.unblockedgames.world "sid" gateway:
    //   GET  ?sid=...            -> landing form (_wp_http)
    //   POST action _wp_http     -> verification form (_wp_http2 + token)
    //   POST action _wp_http2/token -> JS page (s_343 cookie + c.setAttribute href)
    //   GET  href with cookie    -> meta refresh -> driveseed/driveleech url
    private suspend fun resolveSidLink(sidUrl: String, referer: String): String? {
        return try {
            val origin = try {
                val u = URI(sidUrl)
                "${u.scheme}://${u.host}"
            } catch (e: Exception) {
                return null
            }

            // step 0: landing form
            val s0 = getPage(sidUrl, referer = referer) ?: return null
            if (!s0.isSuccessful) return null
            val form0 = Jsoup.parse(s0.text).selectFirst("#landing") ?: return null
            val action0 = form0.attr("action")
            val wp0 = form0.selectFirst("input[name=_wp_http]")?.attr("value")
            if (action0.isBlank() || wp0.isNullOrBlank()) return null

            // step 1: submit _wp_http
            val s1 = postPage(action0, mapOf("_wp_http" to wp0), referer = sidUrl) ?: return null
            if (!s1.isSuccessful) return null

            // step 2: the response is either the verification form or already the JS page
            var jsPage = s1.text
            val form1 = Jsoup.parse(jsPage).selectFirst("#landing")
            if (form1 != null) {
                val action1 = form1.attr("action")
                val wp1 = form1.selectFirst("input[name=_wp_http2]")?.attr("value")
                val token = form1.selectFirst("input[name=token]")?.attr("value")
                if (action1.isNotBlank() && !wp1.isNullOrBlank() && !token.isNullOrBlank()) {
                    val s2 = postPage(action1, mapOf("_wp_http2" to wp1, "token" to token), referer = action0) ?: return null
                    if (s2.isSuccessful) jsPage = s2.text
                }
            }

            // step 3: parse the dynamic cookie + final path out of the JS
            val cookieMatch = Regex("""s_\d+\('([^']+)',\s*'([^']+)'""").find(jsPage)
            val linkMatch = Regex("""c\.setAttribute\("href",\s*"([^"]+)"\)""").find(jsPage)
            if (cookieMatch == null || linkMatch == null) return null
            val cookieName = cookieMatch.groupValues[1].trim()
            val cookieValue = cookieMatch.groupValues[2].trim()
            val linkPath = linkMatch.groupValues[1].trim()
            val finalPage = if (linkPath.startsWith("http")) linkPath else origin + linkPath

            // step 4: GET with the dynamic cookie -> meta refresh -> driveseed/driveleech url
            val s3 = getPage(finalPage, referer = null, extraHeaders = mapOf("Cookie" to "$cookieName=$cookieValue")) ?: return null
            if (!s3.isSuccessful) return null
            val meta = Jsoup.parse(s3.text).selectFirst("meta[http-equiv=refresh]")?.attr("content")
            val resolved = meta?.let { Regex("""url=(.*)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim()?.trim('"', '\'') }
            if (resolved.isNullOrBlank()) return null
            if (resolved.startsWith("http")) resolved else origin + resolved
        } catch (e: Exception) {
            null
        }
    }

    // driveseed.org / driveleech.net file page -> a playable direct link
    private suspend fun resolveDriveleech(url: String): String? {
        return try {
            var pageUrl = url
            val origin = try {
                val u = URI(url)
                "${u.scheme}://${u.host}"
            } catch (e: Exception) {
                return null
            }

            var res = getPage(pageUrl, referer = "https://links.modpro.blog/") ?: return null
            if (!res.isSuccessful) return null

            // js redirect to the real file page
            val redirect = Regex("""window\.location\.replace\("([^"]+)"\)""").find(res.text)?.groupValues?.get(1)
            if (redirect != null) {
                pageUrl = if (redirect.startsWith("http")) redirect else origin + redirect
                res = getPage(pageUrl, referer = url) ?: return null
                if (!res.isSuccessful) return null
            }

            // meta refresh fallback
            val metaUrl = Jsoup.parse(res.text).selectFirst("meta[http-equiv=refresh]")?.attr("content")
                ?.let { Regex("""url=(.*)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim()?.trim('"', '\'') }
            if (!metaUrl.isNullOrBlank()) {
                pageUrl = if (metaUrl.startsWith("http")) metaUrl else origin + metaUrl
                res = getPage(pageUrl, referer = url) ?: return null
                if (!res.isSuccessful) return null
            }

            val doc = Jsoup.parse(res.text)
            val finalUrls = mutableListOf<String>()

            // primary: "Resume Cloud" -> "Cloud Resume Download"
            val resumeHref = doc.select("a").firstOrNull { it.text().contains("Resume Cloud", true) }?.attr("href")
            if (!resumeHref.isNullOrBlank()) {
                val resumeUrl = if (resumeHref.startsWith("http")) resumeHref else origin + resumeHref
                val resumeOrigin = try {
                    val u = URI(resumeUrl)
                    "${u.scheme}://${u.host}"
                } catch (e: Exception) {
                    origin
                }
                val r2 = getPage(resumeUrl, referer = "$resumeOrigin/")
                if (r2 != null && r2.isSuccessful) {
                    val dl = Jsoup.parse(r2.text).select("a").firstOrNull { it.text().contains("Cloud Resume Download", true) }?.attr("href")
                    if (!dl.isNullOrBlank()) {
                        val final = if (dl.startsWith("http")) dl else resumeOrigin + dl
                        normalizeDriveUrl(final)?.let { finalUrls.add(it) }
                    }
                }
            }

            // secondary: "Instant Download" -> /api with keys -> direct url
            val instantHref = doc.select("a").firstOrNull { it.text().contains("Instant Download", true) }?.attr("href")
            if (!instantHref.isNullOrBlank() && finalUrls.isEmpty()) {
                val instantOrigin = try {
                    val u = URI(instantHref)
                    "${u.scheme}://${u.host}"
                } catch (e: Exception) {
                    origin
                }
                val keys = try {
                    URI(instantHref).query?.split("&")?.firstOrNull { it.startsWith("url=") }?.substringAfter("=")
                } catch (e: Exception) {
                    null
                }
                if (!keys.isNullOrBlank()) {
                    val apiUrl = instantOrigin + "/api"
                    val host = try { URI(instantHref).host ?: "" } catch (e: Exception) { "" }
                    val r3 = postPage(
                        apiUrl,
                        mapOf("keys" to keys),
                        referer = instantHref,
                        extraHeaders = mapOf("x-token" to host)
                    )
                    if (r3 != null && r3.isSuccessful) {
                        try {
                            val u = JSONObject(r3.text).optString("url")
                            if (u.isNotBlank()) normalizeDriveUrl(u)?.let { finalUrls.add(it) }
                        } catch (e: Exception) {
                        }
                    }
                }
            }

            // last resort: a direct file link straight on the page
            if (finalUrls.isEmpty()) {
                for (a in doc.select("a[href]")) {
                    val href = a.attr("href")
                    if (href.contains("drive.google.com") || Regex("""(?i)\.(mp4|m3u8)(\?.*)?$""").containsMatchIn(href)) {
                        normalizeDriveUrl(href)?.let { finalUrls.add(it) }
                        if (finalUrls.size >= 2) break
                    }
                }
            }

            finalUrls.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    // turn drive.google.com/file/d/<id>/ links into the playable export form
    private fun normalizeDriveUrl(url: String): String? {
        val clean = url.trim().trim('"', '\'')
        if (clean.isBlank()) return null
        val id = Regex("""drive\.google\.com/file/d/([^/]+)""").find(clean)?.groupValues?.get(1)
        if (id != null) return "https://drive.google.com/uc?export=download&id=$id"
        return clean
    }

    private suspend fun emitStream(url: String, label: String, callback: (ExtractorLink) -> Unit) {
        val clean = url.trim().trim('"', '\'')
        if (clean.isBlank()) return
        val isM3u8 = clean.contains(".m3u8", true)
        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = clean,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = ""
                this.headers = mapOf("User-Agent" to userAgent)
            }
        )
    }
}
