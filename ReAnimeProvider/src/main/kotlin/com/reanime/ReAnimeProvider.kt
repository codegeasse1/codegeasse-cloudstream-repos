package com.example.reanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

class ReAnime : MainAPI() {
    override var mainUrl = "https://reanime.to"
    override var name = "ReAnime"
    override val supportedTypes = setOf(TvType.Anime)

    // AES decryption helper
    private fun decryptFlixCloudResponse(encryptedBase64: String, keyBase64: String, ivStr: String): String {
        val encrypted = Base64.getDecoder().decode(encryptedBase64)
        val key = Base64.getDecoder().decode(keyBase64)
        
        // Pad IV to 16 bytes
        val ivBytes = ivStr.toByteArray(Charsets.UTF_8)
        val iv16 = ByteArray(16)
        System.arraycopy(ivBytes, 0, iv16, 0, ivBytes.size)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv16))

        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }

    override suspend fun getLoadMoreUrls(
        page: Int,
        url: String,
        data: String?
    ): LoadMoreResponse? {
        return null
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
        // data should be the FlixCloud dataLink, e.g.:
        // "https://flixcloud.cc/e/orl3rf51u46c?v=2"
        val dataLink = data

        // Extract FlixCloud ID from dataLink
        val flixId = dataLink.substringAfter("/e/").substringBefore("?")
        val m3u8ApiUrl = "https://flixcloud.cc/api/m3u8/$flixId"

        // Headers to mimic browser request
        val flixHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.4951.67 Safari/537.36",
            "Referer" to dataLink,
            "Origin" to "https://flixcloud.cc",
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Accept-Language" to "en-GB,en;q=0.6",
            "sec-ch-ua" to "\"Not=A?Brand\";v=\"99\", \"Brave\";v=\"151\", \"Chromium\";v=\"151\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-fetch-dest" to "empty",
            "sec-fetch-mode" to "cors",
            "sec-fetch-site" to "same-origin",
            "sec-gpc" to "1"
        )

        try {
            // Get encrypted FlixCloud response
            val encryptedRes = app.get(m3u8ApiUrl, headers = flixHeaders).text
            val json = JSONObject(encryptedRes)

            // Extract encrypted data, key, and IV
            val encryptedData = json.getString("534ef3cdb6")
            val keyB64 = json.getString("09060b5bc3")
            val ivStr = json.getString("a9q6k1bogd5")

            // Decrypt the response
            val decryptedJsonStr = decryptFlixCloudResponse(encryptedData, keyB64, ivStr)
            val decryptedJson = JSONObject(decryptedJsonStr)

            // Check if decryption was successful
            if (decryptedJson.optBoolean("success", false)) {
                val sources = decryptedJson.optJSONArray("sources")
                sources?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val src = arr.getJSONObject(i)
                        val url = src.optString("file", "")
                        val type = src.optString("type", "").lowercase()

                        if (url.isNotBlank() && (type == "hls" || url.contains(".m3u8"))) {
                            // Add M3U8 link
                            callback(
                                newExtractorLink(
                                    source = "FlixCloud",
                                    name = "FlixCloud HLS",
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

                // Handle subtitles
                val tracks = decryptedJson.optJSONArray("tracks")
                tracks?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val track = arr.getJSONObject(i)
                        val subUrl = track.optString("file", "")
                        val label = track.optString("label", "Unknown")
                        val kind = track.optString("kind", "captions")

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
