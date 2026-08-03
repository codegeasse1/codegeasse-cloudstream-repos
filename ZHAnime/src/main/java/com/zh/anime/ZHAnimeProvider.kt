package com.zh.anime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class ZHAnimeProvider : MainAPI() {
    override var mainUrl = "https://zhanime.online" // Update with the actual base URL
    override var name = "ZH Anime"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // TODO: Implement homepage scraping using app.get(mainUrl).document
        return newHomePageResponse("Recent Anime", emptyList())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // TODO: Implement search scraping
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title")?.text() ?: "Unknown Title"
        val poster = fixUrlNull(document.selectFirst("img.poster")?.attr("src"))

        // TODO: Parse episode list from the DOM
        val episodes = mutableListOf<Episode>()
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName = title
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String, // The episode URL
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Find all server iframes based on our previous analysis
        document.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            
            when {
                // Pass standard extractor URLs to Cloudstream's built-in extractors
                src.contains("megaplay.buzz") || src.contains("vidplay") -> {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
                
                // Handle the custom Artplayer / local M3U8 stream
                src.contains("artplayer.php") || src.contains("player.php") -> {
                    // Fetch the player page to find the M3U8 playlist link
                    val playerHtml = app.get(src).text
                    
                    // Regex to find the index.txt / m3u8 file inside the player script
                    val m3u8Regex = Regex("file:\\s*['\"](.*?(?:m3u8|index\\.txt).*?)['\"]")
                    val match = m3u8Regex.find(playerHtml)
                    
                    match?.groupValues?.get(1)?.let { playlistUrl ->
                        val fixedPlaylistUrl = fixUrl(playlistUrl)
                        
                        callback.invoke(
                            ExtractorLink(
                                source = "ZH CDN",
                                name = "ZH CDN",
                                url = fixedPlaylistUrl,
                                referer = src,
                                quality = Qualities.Unknown.value,
                                isM3u8 = true // Set to true for M3U8 / index.txt playlists
                            )
                        )
                    }
                }
            }
        }
        return true
    }
}
