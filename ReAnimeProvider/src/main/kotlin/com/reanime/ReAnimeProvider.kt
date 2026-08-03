package com.reanime

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ReAnimeProvider : MainAPI() {
    override var mainUrl = "https://reanime.to"
    override var name = "ReAnime"
    override val supportedTypes = setOf(TvType.Anime)

    // Fixed AES decryption helper using proper Base64 decoding for IV
    private fun decryptFlixCloudResponse(encryptedBase64: String, keyBase64: String, ivBase64: String): String {
        val encrypted = Base64.decode(encryptedBase64, Base64.DEFAULT)
        val key = Base64.decode(keyBase64, Base64.DEFAULT)
        val iv = Base64.decode(ivBase64, Base64.DEFAULT) 

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }

    override suspend fun load(url: String): LoadResponse? {
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // dataLink is the FlixCloud embed URL
        val dataLink = data
        val flixId = dataLink.substringAfter("/e/").substringBefore("?")

        val flixHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to dataLink,
            "Origin" to "https://flixcloud.cc"
        )

        try {
            // Step 1: Download the embed HTML to dynamically scrape the Key and IV
            val html = app.get(dataLink, headers = flixHeaders).text
            
            // Regex to hunt down the dynamically named kf_ and ivf_ keys in the JSON block
            val keyB64 = Regex("""["']kf_\w+["']\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            val ivB64 = Regex("""["']ivf_\w+["']\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

            if (keyB64 == null || ivB64 == null) return false

            // Step 2: Fetch the Encrypted Payload from the API
            val m3u8ApiUrl = "https://flixcloud.cc/api/m3u8/$flixId"
            val encryptedRes = app.get(m3u8ApiUrl, headers = flixHeaders).text
            val json = JSONObject(encryptedRes)

            // Step 3: Extract the payload. 
            // Since the API uses random keys (e.g. "534ef3cdb6"), we simply grab the longest string, which is always the video data.
            var encryptedData = ""
            json.keys().forEach { k ->
                val v = json.optString(k)
                if (v.length > encryptedData.length) {
                    encryptedData = v
                }
            }

            if (encryptedData.isBlank()) return false

            // Step 4: Decrypt the payload
            val decryptedJsonStr = decryptFlixCloudResponse(encryptedData, keyB64, ivB64)
            val decryptedJson = JSONObject(decryptedJsonStr)

            // Step 5: Parse and push the decrypted M3U8 links
            if (decryptedJson.optBoolean("success", false)) {
                val sources = decryptedJson.optJSONArray("sources")
                sources?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val src = arr.getJSONObject(i)
                        val url = src.optString("file", "")
                        val type = src.optString("type", "").lowercase()

                        if (url.isNotBlank() && (type == "hls" || url.contains(".m3u8"))) {
                            callback(
                                newExtractorLink(
                                    source = "FlixCloud",
                                    name = "FlixCloud",
                                    url = url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.headers = flixHeaders
                                }
                            )
                        }
                    }
                }

                // Handle Subtitles
                val tracks = decryptedJson.optJSONArray("tracks")
                tracks?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val track = arr.getJSONObject(i)
                        val subUrl = track.optString("file", "")
                        val label = track.optString("label", "Unknown")

                        if (subUrl.isNotBlank()) {
                            subtitleCallback(SubtitleFile(label, subUrl))
                        }
                    }
                }

                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return false
    }
}
