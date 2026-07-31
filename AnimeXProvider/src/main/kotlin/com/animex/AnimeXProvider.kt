package com.animex

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.json.JSONArray

class AnimeXProvider : MainAPI() {
    override var mainUrl = "https://animex.one"
    override var name = "AnimeX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private fun extractSectionArray(scriptData: String, key: String): List<SearchResponse> {
        val match = Regex("""["']?$key["']?\s*:\s*\[""").find(scriptData) ?: return emptyList()
        val startIndex = match.range.first
        
        var bracketCount = 0
        var arrayStart = scriptData.indexOf('[', startIndex)
        if (arrayStart == -1) return emptyList()
        
        var endIndex = -1
        for (i in arrayStart until scriptData.length) {
            if (scriptData[i] == '[') bracketCount++
            else if (scriptData[i] == ']') bracketCount--
            
            if (bracketCount == 0) {
                endIndex = i + 1
                break
            }
        }
        
        if (endIndex == -1) return emptyList()
        
        val arrayStr = scriptData.substring(arrayStart, endIndex)
        val parts = arrayStr.split(Regex("""["']?slug["']?\s*:\s*["']"""))
        val items = mutableListOf<SearchResponse>()
        
        for (i in 1 until parts.size) {
            val part = parts[i]
            val slug = part.substringBefore("\"").substringBefore("'")
            if (slug.isBlank() || slug.length > 200) continue
            
            val window = part.take(1500).replace("\\/", "/")
            
            var title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?name["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?romaji["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
            
            // Fix for Serveproxy and Anilist images
            var poster = Regex("""["']?extraLarge["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""["']?large["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""https?://serveproxy\.com[^"'\s]+""").find(window)?.value ?: ""
            if (poster.isBlank()) poster = Regex("""https?://s4\.anilist\.co[^"'\s]+""").find(window)?.value ?: ""
            
            // TMDB fallback just in case
            if (poster.isBlank()) {
                val relPath = Regex("""["']?poster_path["']?\s*:\s*["'](/[^"'\s]+)""").find(window)?.groupValues?.get(1)
                if (relPath != null) poster = "https://image.tmdb.org/t/p/original$relPath"
            }
            
            val url = "$mainUrl/anime/$slug"
            if (items.none { it.url == url }) {
                items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        }
        return items
    }

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get("$mainUrl/home").text
        val scriptData = Jsoup.parse(html).select("script").html()
        
        val homeItems = mutableListOf<HomePageList>()
        
        val sections = listOf(
            "trendingAnime" to "Trending",
            "seasonalAnime" to "Popular This Season",
            "popularMovies" to "Popular Movies",
            "upcomingAnime" to "Upcoming",
            "allTimePopular" to "All Time Popular"
        )
        
        for ((key, title) in sections) {
            val items = extractSectionArray(scriptData, key)
            if (items.isNotEmpty()) {
                homeItems.add(HomePageList(title, items))
            }
        }
        
        return newHomePageResponse(homeItems)
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.get("$mainUrl/catalog?search=$query").text
        val scriptData = Jsoup.parse(html).select("script").html()
        
        val parts = scriptData.split(Regex("""["']?slug["']?\s*:\s*["']"""))
        val items = mutableListOf<SearchResponse>()
        
        for (i in 1 until parts.size) {
            val part = parts[i]
            val slug = part.substringBefore("\"").substringBefore("'")
            if (slug.isBlank() || slug.length > 200) continue
            
            val window = part.take(1500).replace("\\/", "/")
            
            var title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?name["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?romaji["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
            
            var poster = Regex("""["']?extraLarge["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""["']?large["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""https?://serveproxy\.com[^"'\s]+""").find(window)?.value ?: ""
            if (poster.isBlank()) poster = Regex("""https?://s4\.anilist\.co[^"'\s]+""").find(window)?.value ?: ""
            
            if (poster.isBlank()) {
                val relPath = Regex("""["']?poster_path["']?\s*:\s*["'](/[^"'\s]+)""").find(window)?.groupValues?.get(1)
                if (relPath != null) poster = "https://image.tmdb.org/t/p/original$relPath"
            }
            
            val url = "$mainUrl/anime/$slug"
            if (items.none { it.url == url }) {
                items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        }
        return items
    }

    // ---------------------------------------------------------------
    // LOAD — reads the real embedded JSON fields directly (titleEnglish,
    // coverImage.extraLarge, bannerImage, synopsis, genres) instead of
    // fragile slug-based string splitting. Episodes come from the
    // confirmed pp.animex.one episodes API with correct field names
    // (titles.en, img — not the old title/thumbnail/slug names).
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfter("/anime/").substringBefore("?")
        val html = app.get(url).text
        val document = Jsoup.parse(html)

        val title = Regex(""""titleEnglish"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex(""""romajiTitle"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

        var poster = Regex(""""coverImage"\s*:\s*\{[^}]*?"extraLarge"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        if (poster.isNullOrBlank()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        }

        var banner = Regex(""""bannerImage"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        if (banner.isNullOrBlank()) banner = poster

        var plot = Regex(""""synopsis"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(html)?.groupValues?.get(1)
            ?.replace("\\u003C", "<")?.replace("\\/", "/")
        if (!plot.isNullOrBlank()) {
            plot = Jsoup.parse(plot).text()
        } else {
            plot = document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")
        }

        val genresBlock = Regex(""""genres"\s*:\s*\[(.*?)\]""").find(html)?.groupValues?.get(1)
        val genres = genresBlock?.let {
            Regex(""""name"\s*:\s*"([^"]+)"""").findAll(it).map { m -> m.groupValues[1] }.toList()
        } ?: emptyList()

        val episodes = mutableListOf<Episode>()

        try {
            val epApiUrl = "https://pp.animex.one/rest/api/episodes?id=$slug"
            val apiHeaders = mapOf(
                "Accept" to "application/json",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )
            val epJson = app.get(epApiUrl, headers = apiHeaders).text
            val jsonArray = JSONArray(epJson)
            for (i in 0 until jsonArray.length()) {
                val epObj = jsonArray.getJSONObject(i)
                val epNum = epObj.optInt("number", -1)
                if (epNum == -1) continue

                val titlesObj = epObj.optJSONObject("titles")
                val epTitle = titlesObj?.optString("en", "")?.ifBlank { null } ?: "Episode $epNum"

                val thumbnail = epObj.optString("img", "")
                val epUrl = "$mainUrl/anime/$slug?epNum=$epNum"

                episodes.add(
                    newEpisode(epUrl) {
                        name = epTitle
                        episode = epNum
                        posterUrl = thumbnail.ifBlank { poster }
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback: scrape real /watch/ links straight from the page HTML
        if (episodes.isEmpty()) {
            document.select("a[href*=/watch/]").forEach { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@forEach
                val epNum = Regex("""-episode-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""/(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                val epName = a.text().replace(Regex("""^\d+\.\s*"""), "").trim().ifEmpty { "Episode $epNum" }
                episodes.add(
                    newEpisode(epHref) {
                        name = epName
                        episode = epNum
                        posterUrl = poster
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.plot = plot
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.episode })
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS — the real player URL is streamed directly in the
    // same watch-page response (SvelteKit deferred-data resolve block),
    // NOT from a separate pp.animex.one servers/sources API.
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val cleanData = data.substringBefore("#")

        try {
            val html = app.get(cleanData).text

            val playerUrls = Regex(""""player_url"\s*:\s*"([^"]+)"""")
                .findAll(html)
                .map { it.groupValues[1].replace("\\/", "/") }
                .distinct()
                .toList()

            for (playerUrl in playerUrls) {
                try {
                    if (loadExtractor(playerUrl, cleanData, subtitleCallback, callback)) {
                        found = true
                    }
                } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback: any iframe embedded directly in the DOM
        if (!found) {
            try {
                val html = app.get(cleanData).text
                val document = Jsoup.parse(html)
                document.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && src.startsWith("http") && loadExtractor(src, cleanData, subtitleCallback, callback)) {
                        found = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return found
    }
}
