override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val splitData = data.split("|")
        if (splitData.size < 2) return false

        val malId = splitData[0]
        val epNum = splitData[1]

        if (malId == "0") return false

        var found = false
        val types = listOf("sub", "dub")
        val servers = listOf("megaplay", "filemoon", "streamwish", "vidhide", "mp4upload", "vidplay")
        
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        for (type in types) {
            for (server in servers) {
                try {
                    val apiUrl = "https://api.flikhub.net/$server?mal=$malId&ep=$epNum&type=$type"
                    val apiRes = app.get(
                        apiUrl,
                        headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "Origin" to mainUrl,
                            "Accept" to "*/*", 
                            "User-Agent" to userAgent
                        )
                    ).text

                    // 1. If it returns JSON
                    if (apiRes.trim().startsWith("{")) {
                        val json = JSONObject(apiRes)
                        val sources = json.optJSONArray("sources")
                        
                        if (sources != null && sources.length() > 0) {
                            val fileUrl = sources.getJSONObject(0).optString("file")
                            
                            if (fileUrl.isNotBlank()) {
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = "${server.replaceFirstChar { it.uppercase() }} ${type.uppercase()}",
                                        url = fileUrl,
                                        type = if (fileUrl.contains("m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "https://${server}.buzz/" 
                                        this.quality = Qualities.Unknown.value
                                        this.headers = mapOf(
                                            "Referer" to "https://${server}.buzz/",
                                            "User-Agent" to userAgent
                                        )
                                    }
                                )
                                found = true
                            }
                        }

                        val subtitles = json.optJSONArray("subtitles")
                        if (subtitles != null) {
                            for (i in 0 until subtitles.length()) {
                                val sub = subtitles.getJSONObject(i)
                                val subFile = sub.optString("file")
                                val subLabel = sub.optString("label", "English")
                                
                                if (subFile.isNotBlank()) {
                                    subtitleCallback(newSubtitleFile(subLabel, subFile))
                                }
                            }
                        }
                    } 
                    // 2. If it returns an HTML Player Page (which is what your log shows)
                    else {
                        // Unpack the HTML if it is obfuscated
                        var htmlToSearch = apiRes
                        if (htmlToSearch.contains("eval(function(p,a,c,k,e,d)")) {
                            try {
                                htmlToSearch = getAndUnpack(htmlToSearch) + "\n" + htmlToSearch
                            } catch (e: Exception) {}
                        }

                        // Aggressive Regex to find ANY proxy link or m3u8 link in the HTML
                        val linkRegex = Regex("""https?://[^\s"'<>\\]*?(?:m3u8|mp4)[^\s"'<>\\]*""")
                        val matches = linkRegex.findAll(htmlToSearch).map { it.value }.distinct().toList()

                        for (mediaUrl in matches) {
                            // Skip junk links that might be matched
                            if (mediaUrl.contains("preview") || mediaUrl.contains("poster")) continue

                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "${server.replaceFirstChar { it.uppercase() }} ${type.uppercase()} (Direct)",
                                    url = mediaUrl,
                                    type = if (mediaUrl.contains("m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://${server}.buzz/"
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf(
                                        "Referer" to "https://${server}.buzz/", 
                                        "User-Agent" to userAgent
                                    )
                                }
                            )
                            found = true
                        }

                        // Check for base64 encoded strings just in case
                        val base64Regex = Regex("""["'](aHR0cHM6Ly[a-zA-Z0-9+/=]+)["']""").findAll(htmlToSearch)
                        for (b64 in base64Regex) {
                            try {
                                val decoded = String(android.util.Base64.decode(b64.groupValues[1], android.util.Base64.DEFAULT))
                                if (decoded.contains(".m3u8") || decoded.contains(".mp4")) {
                                    val m3u8Match = linkRegex.find(decoded)
                                    if (m3u8Match != null) {
                                        callback(
                                            newExtractorLink(
                                                source = name,
                                                name = "${server.replaceFirstChar { it.uppercase() }} ${type.uppercase()} (Decoded)",
                                                url = m3u8Match.value,
                                                type = if (m3u8Match.value.contains("m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) {
                                                this.referer = "https://${server}.buzz/"
                                                this.quality = Qualities.Unknown.value
                                            }
                                        )
                                        found = true
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    println("YOMI ERROR: ${e.message}")
                }
            }
        }
        return found
    }
