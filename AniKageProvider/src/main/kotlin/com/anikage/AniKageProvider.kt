package com.anikage

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.json.JSONObject

class AniKageProvider : MainAPI() {
    override var mainUrl = "https://anikage.cc"
    override var name = "AniKage"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val XOR_KEY = "aproxy2026"
    }

    // Base64 + XOR decryption used by the site for sources/subtitles
    private fun xorDecrypt(enc: String): String? {
        return try {
            val key = XOR_KEY.toByteArray(Charsets.UTF_8)
            val raw = android.util.Base64.decode(enc.trim(), android.util.Base64.DEFAULT)
            val out = ByteArray(raw.size) { i -> (raw[i].toInt() xor key[i % key.size].toInt()).toByte() }
            String(out, Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    private fun extractSectionArray(scriptData: String, key: String): List<SearchResponse> {
        val startStr = "$key:["
        val startIndex = scriptData.indexOf(startStr)
        if (startIndex == -1) return emptyList()
        var bracketCount = 1
        var endIndex = -1
        for (i in (startIndex + startStr.length) until scriptData.length) {
            if (scriptData[i] == '[') bracketCount++
            else if (scriptData[i] == ']') bracketCount--
            if (bracketCount == 0) { endIndex = i; break }
        }
        if (endIndex == -1) return emptyList()
        val arrayStr = scriptData.substring(startIndex, endIndex)
        val parts = arrayStr.split("slug:\"")
        val items = mutableListOf<SearchResponse>()
        for (i in 1 until parts.size) {
            val part = parts[i]
            val slug = part.substringBefore("\"")
            if (slug.isBlank() || slug.length > 200) continue
            val titleBlock = part.substringAfter("title:{", "").substringBefore("}")
            var title = titleBlock.substringAfter("english:\"", "").substringBefore("\"")
            if (title.isBlank() || title == titleBlock) title = titleBlock.substringAfter("userPreferred:\"", "").substringBefore("\"")
            if (title.isBlank() || title == titleBlock) title = titleBlock.substringAfter("romaji:\"", "").substringBefore("\"")
            if (title.isBlank() || title == titleBlock) title = slug
            val coverBlock = part.substringAfter("coverImage:{", "").substringBefore("}")
            var poster = coverBlock.substringAfter("extraLarge:\"", "").substringBefore("\"")
            if (poster.isBlank() || poster == coverBlock) poster = coverBlock.substringAfter("large:\"", "").substringBefore("\"")
            if (poster == coverBlock) poster = ""
            val url = "$mainUrl/anime/info/$slug"
            if (items.none { it.url == url }) {
                items.add(newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster })
            }
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get(mainUrl).text
        val scriptData = Jsoup.parse(html).select("script").html()
        val homeItems = mutableListOf<HomePageList>()
        val sections = listOf(
            "trendinganime" to "Trending",
            "seasonalanime" to "Popular This Season",
            "top10anime" to "Top 10 Anime",
            "popularmovies" to "Popular Movies",
            "upcominganime" to "Coming Soon"
        )
        for ((key, title) in sections) {
            val items = extractSectionArray(scriptData, key)
            if (items.isNotEmpty()) homeItems.add(HomePageList(title, items))
        }
        return newHomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        try {
            val responseText = app.get("$mainUrl/api/media/anime/search?q=$query&limit=20&adult=true", headers = mapOf("Referer" to "$mainUrl/")).text
            val parts = responseText.split(Regex(""""slug"\s*:\s*""""))
            for (i in 1 until parts.size) {
                val part = parts[i]
                val slug = part.substringBefore("\"").replace("\\", "")
                if (slug.isBlank() || slug.length > 200) continue
                val window = part.take(2000)
                var title = ""
                val titleBlock = window.substringAfter("\"title\":", "").substringBefore("}")
                if (titleBlock.contains("\"english\":")) title = titleBlock.substringAfter("\"english\":\"", "").substringBefore("\"")
                if (title.isBlank() || title == titleBlock) title = titleBlock.substringAfter("\"userPreferred\":\"", "").substringBefore("\"")
                if (title.isBlank() || title == titleBlock) title = titleBlock.substringAfter("\"romaji\":\"", "").substringBefore("\"")
                if (title.isBlank() || title == titleBlock) title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
                title = title.replace("\\\"", "\"").replace("\\/", "/")
                var poster = ""
                val coverBlock = window.substringAfter("\"coverImage\":", "").substringBefore("}")
                if (coverBlock.contains("\"extraLarge\":")) poster = coverBlock.substringAfter("\"extraLarge\":\"", "").substringBefore("\"")
                if (poster.isBlank() || poster == coverBlock) poster = coverBlock.substringAfter("\"large\":\"", "").substringBefore("\"")
                if (poster == coverBlock) poster = ""
                poster = poster.replace("\\/", "/")
                val url = "$mainUrl/anime/info/$slug"
                if (items.none { it.url == url }) {
                    items.add(newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster })
                }
            }
        } catch (e: Exception) { }
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast("/")
        val html = app.get(url).text
        val document = Jsoup.parse(html)
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("img[src*='anilistcdn']")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val scriptData = document.select("script").html()
        var plot = scriptData.substringAfter("description:\"", "").substringBefore("\",")
            .replace("\\u003C", "<").replace("\\n", "\n")
        plot = if (plot.isBlank() || plot.length > 5000) {
            document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        } else Jsoup.parse(plot).text()
        val currentEps = scriptData.substringAfter("currentEpisode:", "").substringBefore(",").toIntOrNull() ?: 0
        val totalEps = scriptData.substringAfter("totalEpisodes:", "").substringBefore(",").toIntOrNull() ?: 0
        val availableEpisodes = if (currentEps > 0) currentEps else totalEps
        val episodes = mutableListOf<Episode>()
        for (i in 1..(if (availableEpisodes > 0) availableEpisodes else 1)) {
            episodes.add(newEpisode("$mainUrl/anime/watch/$slug?ep=$i") { name = "Episode $i"; episode = i })
        }
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
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
        val cleanData = data.substringBefore("#")
        val slug = cleanData.substringAfter("/watch/").substringBefore("?").substringBefore("/")
        val ep = Regex("""[?&]ep=(\d+)""").find(cleanData)?.groupValues?.get(1) ?: "1"

        val videoHeaders = mapOf("Referer" to "$mainUrl/", "User-Agent" to UA)
        val seen = hashSetOf<String>()

        suspend fun emit(url: String, label: String, isM3u8: Boolean): Boolean {
            val u = url.replace("\\/", "/").trim()
            if (u.isBlank() || !u.startsWith("http") || !seen.add(u)) return false
            callback(newExtractorLink(source = name, name = label, url = u,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.quality = Qualities.Unknown.value
                this.headers = videoHeaders
            })
            return true
        }

        // Fetch a player/embed page and pull the real stream out of it
        suspend fun resolveEmbedPage(pageUrl: String, label: String): Boolean {
            var ok = false
            try {
                val page = app.get(pageUrl, headers = videoHeaders).text.replace("\\/", "/")
                // direct m3u8/mp4 in page
                for (m in Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""").findAll(page)) {
                    if (emit(m.value, label, m.value.contains("m3u8"))) ok = true
                }
                // encrypted blobs in page (same XOR scheme)
                if (!ok) {
                    for (m in Regex("""["']([A-Za-z0-9+/=]{40,})["']""").findAll(page)) {
                        val dec = xorDecrypt(m.groupValues[1]) ?: continue
                        if (dec.startsWith("http")) {
                            if (dec.contains(".m3u8") || dec.contains(".mp4")) {
                                if (emit(dec, label, dec.contains("m3u8"))) ok = true
                            } else {
                                // one more level of embed
                                ok = resolveEmbedPage(dec, label)
                            }
                            if (ok) break
                        }
                    }
                }
            } catch (e: Exception) { }
            return ok
        }

        val providers = listOf(
            "vibeube", "vidtube", "dib", "neko", "miko", "wave", "koto", "ken",
            "e-koto", "e-neko", "e-ken", "e-wish", "megatube", "megaplay", "vibe", "megg", "kwik", "aniyt"
        )

        val langs = listOf("sub", "dub")

        for (lang in langs) {
            for (provider in providers) {
                try {
                    val responseText = app.get(
                        "$mainUrl/api/media/anime/$slug/episodes/$ep/sources?provider=$provider&lang=$lang",
                        headers = mapOf("Referer" to "$mainUrl/")
                    ).text

                    if (responseText.contains("\"sources\":[]")) continue

                    val label = provider.replaceFirstChar { it.uppercase() } + " (${lang})"
                    val json = JSONObject(responseText)

                    // 1) Decrypt sources[].url
                    val sources = json.optJSONArray("sources")
                    if (sources != null) {
                        for (i in 0 until sources.length()) {
                            val src = sources.optJSONObject(i) ?: continue
                            var u = src.optString("url", "")
                            if (!u.startsWith("http")) u = xorDecrypt(u) ?: ""
                            if (u.isBlank()) continue
                            if (u.contains(".m3u8") || u.contains(".mp4")) {
                                if (emit(u, label, u.contains("m3u8"))) found = true
                            } else {
                                if (resolveEmbedPage(u, label)) found = true
                            }
                        }
                    }

                    // 2) Subtitles (also XOR encrypted)
                    val subs = json.optJSONArray("subtitles")
                    if (subs != null) {
                        for (i in 0 until subs.length()) {
                            val s = subs.optJSONObject(i) ?: continue
                            var f = s.optString("file", "")
                            if (!f.startsWith("http")) f = xorDecrypt(f) ?: ""
                            if (f.isNotBlank()) subtitleCallback(newSubtitleFile(s.optString("label", "Sub"), f))
                        }
                    }

                    // 3) Plain URLs anywhere in the raw response (prox/workers hosts)
                    for (m in Regex("""https?://(?:prox\.anicore\.tv|prox\.anikage\.cc|[^\s"'<>\\]*workers\.dev)/[^\s"'<>\\]+""").findAll(responseText.replace("\\/", "/"))) {
                        val u = m.value
                        if (emit(u, label, true)) found = true
                    }
                } catch (e: Exception) { }
            }
        }

        return found
    }
}