package com.chikianimation

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

class ChikiAnimationProvider : MainAPI() {
    override var mainUrl = "https://chikianimation.online"
    override var name = "ChikiAnimation"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

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

        val title = linkEl.attr("title").ifBlank {
            this.selectFirst("div.tt")?.text()
        }?.trim() ?: return null

        val rawPoster = this.selectFirst("img")?.attr("data-lazy-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("data-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("src")

        val posterUrl = fixUrlNull(rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
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

        var poster = fixUrlNull(rawPoster?.substringBefore("?")?.replace(Regex("https?://i\\d+\\.wp\\.com/"), "https://"))

        if (poster.isNullOrBlank()) {
            val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
            if (ogImage != null && !ogImage.contains("logo", true) && !ogImage.contains("banner", true)) {
                poster = fixUrlNull(ogImage)
            }
        }

        val synopsis = document.selectFirst(".entry-content, .synp .entry-content, #synopsis, .desc")?.text()
        val genres = document.select("a[href*=/genres/], .genxed a").map { it.text() }

        fun parseEpisodeGrid(doc: Document, currentUrl: String): List<Episode> {
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
            val firstEpLink = document.selectFirst(
                ".epcurfirst a, .epcurlast a, .inepcx a, .bxcl a, a:matchesOwn((?i)watch)"
            )?.attr("href")
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

    private fun fixRelativeUrl(url: String?, baseUrl: String): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> {
                runCatching {
                    val uri = URI(baseUrl)
                    "${uri.scheme}://${uri.host}$trimmed"
                }.getOrNull() ?: trimmed
            }
            else -> {
                runCatching {
                    val uri = URI(baseUrl)
                    val path = uri.path.substringBeforeLast("/", "")
                    "${uri.scheme}://${uri.host}$path/${trimmed.removePrefix("./")}"
                }.getOrNull() ?: trimmed
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        val response = try {
            app.get(data)
        } catch (e: Exception) {
            null
        }

        if (response == null) return false

        val document = response.document
        val pageHtml = response.text

        // 1. Direct Dailymotion Embed
        val dmPatterns = listOf(
            Regex("""geo\.dailymotion\.com/player\.html\?video=([a-zA-Z0-9_]+)"""),
            Regex("""dailymotion\.com/embed/video/([a-zA-Z0-9_]+)"""),
            Regex("""dailymotion\.com/video/([a-zA-Z0-9_]+)""")
        )

        for (pattern in dmPatterns) {
            val match = pattern.find(pageHtml)
            if (match != null) {
                val videoId = match.groupValues[1]
                try {
                    if (loadExtractor("https://www.dailymotion.com/video/$videoId", data, subtitleCallback, callback)) {
                        found = true
                        break
                    }
                } catch (_: Exception) { }
            }
        }

        // 2. Process all mirror options
        val options = document.select("select#serverlist option, select option, select[name*='server'] option")
        
        for (option in options) {
            val value = option.attr("value")
            if (value.isBlank()) continue

            try {
                var decoded = try {
                    String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
                } catch (e: Exception) {
                    try {
                        URLDecoder.decode(value, "UTF-8")
                    } catch (e: Exception) {
                        value
                    }
                }

                val srcPatterns = listOf(
                    Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                    Regex("""["']([^"']*\.(?:html|php)[^"']*)["']"""),
                    Regex("""(https?://[^\s"'<>]+)""")
                )

                var src: String? = null
                for (pattern in srcPatterns) {
                    val match = pattern.find(decoded)
                    if (match != null) {
                        src = match.groupValues[1]
                        if (src.contains("http")) break
                    }
                }

                if (src.isNullOrBlank()) continue
                src = fixRelativeUrl(src, data) ?: continue

                when {
                    src.contains("dailymotion", ignoreCase = true) -> {
                        val vidPatterns = listOf(
                            Regex("""(?:video/|video=|embed/|player\.html\?video=)([a-zA-Z0-9_]+)"""),
                            Regex("""/video/([a-zA-Z0-9_]+)""")
                        )
                        
                        for (pattern in vidPatterns) {
                            val vidMatch = pattern.find(src)
                            if (vidMatch != null) {
                                val vid = vidMatch.groupValues[1]
                                if (loadExtractor("https://www.dailymotion.com/video/$vid", data, subtitleCallback, callback)) {
                                    found = true
                                    break
                                }
                            }
                        }
                    }

                    src.contains("videoplayerst.online", ignoreCase = true) -> {
                        val code = Regex("""code=([a-zA-Z0-9_]+)""").find(src)?.groupValues?.get(1)
                        if (code != null) {
                            if (loadExtractor("https://www.dailymotion.com/video/$code", data, subtitleCallback, callback)) {
                                found = true
                            }
                        }
                    }

                    src.contains("rumble.com", ignoreCase = true) -> {
                        if (loadExtractor(src, data, subtitleCallback, callback)) {
                            found = true
                        }
                    }

                    src.contains("rpmstream.live", ignoreCase = true) -> {
                        val fragmentId = src.substringAfterLast("#").ifBlank {
                            Regex("""[?&]id=([^&]+)""").find(src)?.groupValues?.get(1)
                        }
                        
                        if (fragmentId != null) {
                            val apiUrl = "https://chiki.rpmstream.live/api/v1/video?id=$fragmentId&w=1280&h=800&r=chikianimation.online"
                            try {
                                val apiResponse = app.get(apiUrl, headers = mapOf("Referer" to data)).text

                                val m3u8Patterns = listOf(
                                    Regex(""""(?:url|file|src|m3u8|playlist)"\s*:\s*"([^"]+\.m3u8[^"]*)""""),
                                    Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                                )

                                for (pattern in m3u8Patterns) {
                                    val m3u8Match = pattern.find(apiResponse)
                                    if (m3u8Match != null) {
                                        val m3u8Url = m3u8Match.value.replace("\\/", "/")
                                        try {
                                            M3u8Helper.generateM3u8(
                                                source = this.name,
                                                streamUrl = m3u8Url,
                                                referer = apiUrl
                                            ).forEach { link ->
                                                callback(link)
                                                found = true
                                            }
                                        } catch (_: Exception) { }
                                        break
                                    }
                                }
                            } catch (_: Exception) { }
                        }
                    }

                    else -> {
                        try {
                            val res = app.get(src, headers = mapOf("Referer" to data))
                            val doc = res.document
                            val embedHtml = res.text

                            doc.select("track").forEach { track ->
                                val trackSrc = track.attr("src").ifBlank { track.attr("data-src") }
                                val label = track.attr("label").ifBlank { 
                                    track.attr("srclang").ifBlank { "Subtitle" }
                                }
                                val subUrl = fixRelativeUrl(trackSrc, src)
                                if (!subUrl.isNullOrBlank()) {
                                    subtitleCallback(SubtitleFile(label, subUrl))
                                }
                            }

                            var foundStream = false
                            
                            doc.select("source, video").forEach { element ->
                                val streamUrl = fixRelativeUrl(element.attr("src"), src)
                                if (!streamUrl.isNullOrBlank() && 
                                    (streamUrl.contains(".m3u8") || 
                                     streamUrl.contains(".mp4") || 
                                     element.attr("type").contains("mpegurl", true) ||
                                     element.attr("type").contains("mp4", true))) {
                                    
                                    if (streamUrl.contains(".m3u8")) {
                                        try {
                                            M3u8Helper.generateM3u8(
                                                source = this.name,
                                                streamUrl = streamUrl,
                                                referer = src
                                            ).forEach { link ->
                                                callback(link)
                                                foundStream = true
                                            }
                                        } catch (_: Exception) { }
                                    } else {
                                        callback(
                                            newExtractorLink(
                                                source = this.name,
                                                name = "Server",
                                                url = streamUrl,
                                                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
                                            ) {
                                                this.referer = src
                                                this.quality = Qualities.Unknown.value
                                            }
                                        )
                                        foundStream = true
                                    }
                                }
                            }

                            if (!foundStream) {
                                val streamPatterns = listOf(
                                    Regex("""https?://[^\s"'<>]+\.m3u8(?:\?[^\s"'<>]*)?"""),
                                    Regex("""https?://[^\s"'<>]+\.mp4(?:\?[^\s"'<>]*)?"""),
                                    Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                                )

                                for (pattern in streamPatterns) {
                                    val matches = pattern.findAll(embedHtml)
                                    for (match in matches) {
                                        val streamUrl = match.value
                                        if (streamUrl.contains(".m3u8")) {
                                            try {
                                                M3u8Helper.generateM3u8(
                                                    source = this.name,
                                                    streamUrl = streamUrl,
                                                    referer = src
                                                ).forEach { link ->
                                                    callback(link)
                                                    foundStream = true
                                                }
                                            } catch (_: Exception) { }
                                        } else {
                                            callback(
                                                newExtractorLink(
                                                    source = this.name,
                                                    name = "Server",
                                                    url = streamUrl,
                                                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
                                                ) {
                                                    this.referer = src
                                                    this.quality = Qualities.Unknown.value
                                                }
                                            )
                                            foundStream = true
                                        }
                                        if (foundStream) break
                                    }
                                    if (foundStream) break
                                }
                            }

                            if (foundStream) found = true

                        } catch (_: Exception) { }
                    }
                }

                if (found) break

            } catch (_: Exception) { }
        }

        if (!found) {
            val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            val iframes = iframePattern.findAll(pageHtml)
            
            for (iframe in iframes) {
                val src = fixRelativeUrl(iframe.groupValues[1], data) ?: continue
                
                try {
                    if (src.contains("dailymotion", ignoreCase = true)) {
                        val vidPatterns = listOf(
                            Regex("""(?:video/|video=|embed/|player\.html\?video=)([a-zA-Z0-9_]+)"""),
                            Regex("""/video/([a-zA-Z0-9_]+)""")
                        )
                        
                        for (pattern in vidPatterns) {
                            val vidMatch = pattern.find(src)
                            if (vidMatch != null) {
                                val vid = vidMatch.groupValues[1]
                                if (loadExtractor("https://www.dailymotion.com/video/$vid", data, subtitleCallback, callback)) {
                                    found = true
                                    break
                                }
                            }
                        }
                    } else {
                        if (loadExtractor(src, data, subtitleCallback, callback)) {
                            found = true
                            break
                        }
                    }
                } catch (_: Exception) { }
                
                if (found) break
            }
        }

        return found
    }
}