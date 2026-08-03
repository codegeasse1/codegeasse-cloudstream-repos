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
        return newHomePageResponse("Recent Anime", emptyList())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title")?.text() ?: "Unknown Title"
        val poster = fixUrlNull(document.selectFirst("img.poster")?.attr("src"))

        val episodes = mutableListOf<Episode>()
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName = title
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        document.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            
            when {
                src.contains("megaplay.buzz") || src.contains("vidplay") -> {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
                
                src.contains("artplayer.php") || src.contains("player.php") -> {
                    val playerHtml = app.get(src).text
                    
                    val m3u8Regex = Regex("file:\\s*['\"](.*?(?:m3u8|index\\.txt).*?)['\"]")
                    val match = m3u8Regex.find(playerHtml)
                    
                    match?.groupValues?.get(1)?.let { playlistUrl ->
                        val fixedPlaylistUrl = fixUrl(playlistUrl)
                        
                        // FIX: Replaced ExtractorLink with newExtractorLink
                        callback.invoke(
                            newExtractorLink(
                                name = "ZH CDN",
                                url = fixedPlaylistUrl,
                                referer = src,
                                quality = Qualities.Unknown.value,
                                isM3u8 = true 
                            )
                        )
                    }
                }
            }
        }
        return true
    }
}
