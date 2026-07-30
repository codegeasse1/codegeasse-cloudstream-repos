import org.json.JSONArray

// ---------- Updated load method ----------
override suspend fun load(url: String): LoadResponse {
    val slug = url.substringAfter("/anime/").substringBefore("?")
    val html = app.get(url).text
    val document = Jsoup.parse(html)
    val scriptData = document.select("script").html()

    var animeBlock = scriptData.substringAfter("slug:\"$slug\"", "")
    if (animeBlock.isBlank()) animeBlock = scriptData
    val cleanBlock = animeBlock.replace("\\/", "/")

    // Title
    var title = document.selectFirst("h1")?.text()?.trim()
    if (title.isNullOrEmpty()) {
        title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(cleanBlock)?.groupValues?.get(1) ?: ""
        if (title.isBlank()) title = Regex("""["']?name["']?\s*:\s*["']([^"']+)""").find(cleanBlock)?.groupValues?.get(1) ?: ""
        if (title.isBlank()) title = slug.replace("-", " ").replaceFirstChar { it.uppercase() }
    }

    // Poster & Banner
    var poster = Regex("""["']?extraLarge["']?\s*:\s*["']([^"']+)""").find(cleanBlock)?.groupValues?.get(1) ?: ""
    if (poster.isBlank()) poster = Regex("""https?://serveproxy\.com[^"'\s]+""").find(cleanBlock)?.value ?: ""
    if (poster.isBlank()) poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""

    var banner = Regex("""["']?bannerImage["']?\s*:\s*["']([^"']+)""").find(cleanBlock)?.groupValues?.get(1) ?: ""
    if (banner.isBlank()) {
        val relPath = Regex("""["']?backdrop_path["']?\s*:\s*["'](/[^"'\s]+)""").find(cleanBlock)?.groupValues?.get(1)
        if (relPath != null) banner = "https://image.tmdb.org/t/p/original$relPath"
    }
    if (banner.isBlank()) banner = poster

    // Plot
    var plot = Regex("""["']?(?:description|synopsis|overview)["']?\s*:\s*["']([^"'\\]+)""").find(cleanBlock)?.groupValues?.get(1) ?: ""
    if (plot.isBlank()) {
        plot = document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content") ?: ""
    } else {
        plot = Jsoup.parse(plot.replace("\\n", "\n").replace("\\u003C", "<")).text()
    }

    val episodes = mutableListOf<Episode>()

    // ---------- Fetch episodes from API with thumbnails ----------
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
            val epNum = epObj.optInt("number", epObj.optInt("epNum", -1))
            if (epNum == -1) continue
            val epTitle = epObj.optString("title", "Episode $epNum")
            val thumbnail = epObj.optString("thumbnail", "")
            val epSlug = epObj.optString("slug", "") // if available, can construct direct watch URL
            val epUrl = if (epSlug.isNotBlank()) "$mainUrl/watch/$epSlug"
                        else "$mainUrl/anime/$slug?epNum=$epNum"

            episodes.add(
                newEpisode(epUrl) {
                    name = epTitle
                    episode = epNum
                    posterUrl = thumbnail.ifBlank { poster } // fallback to series poster
                }
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // Fallback to HTML scraping if API fails
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
                    posterUrl = poster // series poster as fallback
                }
            )
        }
    }

    return newAnimeLoadResponse(title, url, TvType.Anime) {
        this.posterUrl = poster
        this.backgroundPosterUrl = banner
        this.plot = plot
        addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.episode })
    }
}

// ---------- Updated loadLinks method ----------
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var found = false
    val cleanData = data.substringBefore("#")
    val slug = cleanData.substringAfter("/anime/").substringBefore("?")
    val ep = Regex("""[?&]epNum=(\d+)""").find(cleanData)?.groupValues?.get(1)
        ?: Regex("""[?&]ep=(\d+)""").find(cleanData)?.groupValues?.get(1)
        ?: Regex("""-episode-(\d+)""").find(cleanData)?.groupValues?.get(1)
        ?: "1"

    val apiHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    try {
        // ---------- Step 1: Get server list ----------
        val serversUrl = "https://pp.animex.one/rest/api/servers?id=$slug&epNum=$ep"
        val serversJson = app.get(serversUrl, headers = apiHeaders).text
        val serversArray = JSONArray(serversJson)

        for (i in 0 until serversArray.length()) {
            val serverObj = serversArray.getJSONObject(i)
            val providerId = serverObj.optString("providerId", "") ?: continue
            val type = serverObj.optString("type", "sub")
            val serverName = serverObj.optString("serverName", providerId.replaceFirstChar { it.uppercase() })

            // ---------- Step 2: Get sources ----------
            val sourcesUrl = "https://pp.animex.one/rest/api/sources?id=$slug&epNum=$ep&type=$type&providerId=$providerId"
            val rawSources = app.get(sourcesUrl, headers = apiHeaders).text
                .replace("\\/", "/") // unescape

            val sourcesArray = JSONArray(rawSources)

            for (j in 0 until sourcesArray.length()) {
                val sourceObj = sourcesArray.getJSONObject(j)
                val file = sourceObj.optString("file", "")

                if (file.isNotBlank()) {
                    // Handle direct video links
                    if (file.startsWith("http") && (file.endsWith(".m3u8") || file.endsWith(".mp4"))) {
                        val isM3u8 = file.endsWith(".m3u8")
                        callback(
                            newExtractorLink(
                                source = "AnimeX",
                                name = "$serverName ($type)",
                                url = file,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.referer = "$mainUrl/"
                            }
                        )
                        found = true
                    } else if (file.startsWith("/uwu/") || file.startsWith("/media/")) {
                        // Obfuscated path – may need client-side decryption, skip for now
                        // You can attempt to decode if you reverse-engineer the site's JS
                    }
                }

                // Check for iframe fallback
                val iframe = sourceObj.optString("iframe", "")
                if (iframe.isNotBlank() && iframe.startsWith("http")) {
                    // Attempt extraction via CloudStream's built-in extractor
                    if (loadExtractor(iframe, cleanData, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }

            // Also extract subtitles from the raw sources text
            // (we already parse each source object; subtitles are usually in the same array)
            for (j in 0 until sourcesArray.length()) {
                val sourceObj = sourcesArray.getJSONObject(j)
                val subFile = sourceObj.optString("file", "")
                val label = sourceObj.optString("label", "")
                if (subFile.endsWith(".vtt") && label.isNotBlank() && subFile != file) {
                    subtitleCallback(SubtitleFile(label, subFile))
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // Fallback: DOM for iframes embedded in the watch page itself
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
