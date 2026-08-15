package com.reanime

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ReAnimeProvider : MainAPI() {
    override var mainUrl = "https://reanime.to"
    override var name = "Re:Anime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    companion object {
        private const val TAG = "ReAnime"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val FLIX = "https://flixcloud.cc"

        private val SUB_REGEX =
            Regex("""https?://[^\s"'\\<>()\[\]]+\.(?:vtt|srt|ass)[^\s"'\\<>()\[\]]*""")

        private val EMBED_ID_REGEX =
            Regex("""flixcloud\.cc/(?:embed|e|v|watch|player)/([A-Za-z0-9_-]{6,})""")
    }

    private fun String.unesc(): String = this
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun sha256Hex(str: String): String = sha256Hex(str.toByteArray())

    private fun sha3Hex(str: String): String {
        var e = str
        for (i in 0 until 3) e = sha256Hex(e + i.toString())
        return e
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val hLen = 32
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        for (block in 1..blocks) {
            val u0 = ByteArray(salt.size + 4)
            salt.copyInto(u0)
            var bi = block
            for (k in u0.size - 1 downTo u0.size - 4) {
                u0[k] = bi.toByte()
                bi = bi ushr 8
            }
            var u = hmacSha256(password, u0)
            var t = u.copyOf()
            for (r in 1 until iterations) {
                u = hmacSha256(password, u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            t.copyInto(out, (block - 1) * hLen)
        }
        return out.copyOf(dkLen)
    }

    // Minimal WebAssembly interpreter that understands just the instruction subset
    // used by flixcloud's per-page "secure pipeline" module (i32 ops, memory access,
    // block/loop/br control flow). The module's byte transform is minted per page
    // load with random constants, so it must be interpreted at runtime.
    private class WasmRunner(private val bytes: ByteArray) {
        private data class WasmFunc(val nlocals: Int, val instrs: List<IntArray>)

        private val globals = mutableListOf<Int>()
        private val bodies = mutableListOf<WasmFunc>()
        private val exports = mutableMapOf<String, Int>()
        private val typeParams = mutableListOf<Int>()
        private val funcToType = mutableListOf<Int>()

        private fun readULEB(bytes: ByteArray, start: Int): Pair<Int, Int> {
            var result = 0
            var shift = 0
            var pos = start
            while (true) {
                val b = bytes[pos].toInt() and 0xFF
                pos++
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result to pos
        }

        private fun readSLEB(bytes: ByteArray, start: Int): Pair<Int, Int> {
            var result = 0
            var shift = 0
            var pos = start
            var b = 0
            while (true) {
                b = bytes[pos].toInt() and 0xFF
                pos++
                result = result or ((b and 0x7F) shl shift)
                shift += 7
                if (b and 0x80 == 0) break
            }
            if (shift < 32 && b and 0x40 != 0) result = result or (-1 shl shift)
            return result to pos
        }

        init {
            var pos = 8
            while (pos < bytes.size) {
                val id = bytes[pos].toInt() and 0xFF
                pos++
                val size = readULEB(bytes, pos)
                pos = size.second
                val end = pos + size.first
                when (id) {
                    1 -> { // type section
                        val count = readULEB(bytes, pos)
                        pos = count.second
                        repeat(count.first) {
                            pos++ // 0x60 func form
                            val nParams = readULEB(bytes, pos)
                            pos = nParams.second
                            repeat(nParams.first) { pos++ }
                            val nResults = readULEB(bytes, pos)
                            pos = nResults.second
                            repeat(nResults.first) { pos++ }
                            typeParams.add(nParams.first)
                        }
                    }
                    3 -> { // function section: funcidx -> typeidx
                        val count = readULEB(bytes, pos)
                        pos = count.second
                        repeat(count.first) {
                            val t = readULEB(bytes, pos)
                            pos = t.second
                            funcToType.add(t.first)
                        }
                    }
                    6 -> { // global section
                        val count = readULEB(bytes, pos)
                        pos = count.second
                        repeat(count.first) {
                            pos += 2 // valtype + mutability
                            val init = readSLEB(bytes, pos)
                            pos = init.second
                            pos++ // end of init expr
                            globals.add(init.first)
                        }
                    }
                    7 -> { // export section
                        val count = readULEB(bytes, pos)
                        pos = count.second
                        repeat(count.first) {
                            val nameLen = readULEB(bytes, pos)
                            pos = nameLen.second
                            val name = String(bytes, pos, nameLen.first, Charsets.UTF_8)
                            pos += nameLen.first
                            pos++ // kind
                            val idx = readULEB(bytes, pos)
                            pos = idx.second
                            exports[name] = idx.first
                        }
                    }
                    10 -> { // code section
                        val count = readULEB(bytes, pos)
                        pos = count.second
                        repeat(count.first) {
                            val bodySize = readULEB(bytes, pos)
                            pos = bodySize.second
                            val bodyEnd = pos + bodySize.first
                            val nGroups = readULEB(bytes, pos)
                            pos = nGroups.second
                            var nlocals = 0
                            repeat(nGroups.first) {
                                val n = readULEB(bytes, pos)
                                pos = n.second
                                nlocals += n.first
                                pos++ // valtype
                            }
                            val instrs = mutableListOf<IntArray>()
                            var depth = 0
                            while (pos < bodyEnd) {
                                val op = bytes[pos].toInt() and 0xFF
                                pos++
                                var imm = 0
                                when (op) {
                                    0x41 -> { val c = readSLEB(bytes, pos); pos = c.second; imm = c.first }
                                    0x20, 0x21, 0x23, 0x24, 0x0C, 0x0D -> {
                                        val r = readULEB(bytes, pos); pos = r.second; imm = r.first
                                    }
                                    0x2D, 0x3A -> {
                                        readULEB(bytes, pos).let { pos = it.second }
                                        val o = readULEB(bytes, pos); pos = o.second; imm = o.first
                                    }
                                    0x02, 0x03 -> pos++ // blocktype
                                }
                                if (op == 0x0B && depth == 0) {
                                    instrs.add(intArrayOf(0x0F, 0))
                                    break
                                }
                                instrs.add(intArrayOf(op, imm))
                                if (op == 0x02 || op == 0x03) depth++
                                else if (op == 0x0B) depth--
                            }
                            bodies.add(WasmFunc(nlocals, instrs))
                        }
                    }
                }
                pos = end
            }
        }

        private fun findEnd(instrs: List<IntArray>, ip: Int): Int {
            var d = 0
            for (i in ip until instrs.size) {
                when (instrs[i][0]) {
                    0x02, 0x03 -> d++
                    0x0B -> { d--; if (d == 0) return i + 1 }
                }
            }
            return instrs.size
        }

        // Runs one exported function with the given i32 args. Globals persist across
        // calls (needed so `_s` can set the seed used by `_r`).
        fun run(name: String, args: IntArray, memory: ByteArray): Int {
            val idx = exports[name] ?: return 0
            val body = bodies[idx]
            val nParams = typeParams[funcToType[idx]]
            val locals = IntArray(nParams + body.nlocals)
            args.copyInto(locals)
            val stack = ArrayDeque<Int>()
            val ctrl = ArrayDeque<IntArray>() // [kind(0=block,1=loop), targetIp]
            var ip = 0
            val instrs = body.instrs
            while (ip < instrs.size) {
                val op = instrs[ip][0]
                val imm = instrs[ip][1]
                when (op) {
                    0x41 -> stack.addLast(imm)                                    // i32.const
                    0x20 -> stack.addLast(locals[imm])                            // local.get
                    0x21 -> locals[imm] = stack.removeLast()                      // local.set
                    0x23 -> stack.addLast(globals[imm])                           // global.get
                    0x24 -> globals[imm] = stack.removeLast()                     // global.set
                    0x2D -> stack.addLast(memory[(imm + stack.removeLast())].toInt() and 0xFF) // i32.load8_u
                    0x3A -> {                                                     // i32.store8
                        val v = stack.removeLast()
                        memory[imm + stack.removeLast()] = v.toByte()
                    }
                    0x6A -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a + b) }
                    0x6B -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a - b) }
                    0x6C -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a * b) }
                    0x73 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a xor b) }
                    0x74 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a shl b) }
                    0x76 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a ushr b) }
                    0x71 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a and b) }
                    0x72 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a or b) }
                    0x4F -> { // i32.ge_u (unsigned)
                        val b = stack.removeLast(); val a = stack.removeLast()
                        stack.addLast(if ((a.toLong() and 0xFFFFFFFFL) >= (b.toLong() and 0xFFFFFFFFL)) 1 else 0)
                    }
                    0x45 -> stack.addLast(if (stack.removeLast() == 0) 1 else 0)  // i32.eqz
                    0x02 -> ctrl.addLast(intArrayOf(0, findEnd(instrs, ip)))      // block
                    0x03 -> ctrl.addLast(intArrayOf(1, ip + 1))                   // loop
                    0x0C -> {                                                      // br
                        val targetIdx = ctrl.size - 1 - imm
                        val t = ctrl[targetIdx]
                        ip = t[1] - 1
                        if (t[0] == 1) { while (ctrl.size > targetIdx + 1) ctrl.removeLast() }
                        else { while (ctrl.size > targetIdx) ctrl.removeLast() }
                    }
                    0x0D -> {                                                      // br_if
                        if (stack.removeLast() != 0) {
                            val targetIdx = ctrl.size - 1 - imm
                            val t = ctrl[targetIdx]
                            ip = t[1] - 1
                            if (t[0] == 1) { while (ctrl.size > targetIdx + 1) ctrl.removeLast() }
                            else { while (ctrl.size > targetIdx) ctrl.removeLast() }
                        }
                    }
                    0x0B -> if (ctrl.isNotEmpty()) ctrl.removeLast()              // end
                    0x0F -> return stack.removeLastOrNull() ?: 0                   // return
                    else -> throw IllegalArgumentException("unsupported wasm op 0x${op.toString(16)}")
                }
                ip++
            }
            return stack.removeLastOrNull() ?: 0
        }
    }

    // FlixCloud "v2" stream resolution. The embed page carries an obfuscation seed
    // and AES fragments whose field names are derived from the seed via repeated
    // SHA-256. A token API call plus the per-page wasm key-derivation (interpreted
    // above) plus PBKDF2/AES reveal the final m3u8 URL.
    private suspend fun decryptFlixV2(
        page: String,
        referer: String,
        cookieCollector: MutableMap<String, String>
    ): String {
        fun field(name: String): String =
            Regex("""["']?$name["']?\s*:\s*["']([^"']+)""").find(page)?.groupValues?.get(1) ?: ""

        val seed = field("obfuscation_seed")
        if (seed.isBlank()) {
            Log.e(TAG, "flixcloud v2: no seed in embed page (len=${page.length}) snippet=${page.take(160).replace("\n", " ")}")
            return ""
        }

        val eHex = sha3Hex(seed)
        val nHex = sha3Hex(eHex)
        val frag1 = field("kf_" + eHex.substring(8, 16))
        val ivB64 = field("ivf_" + eHex.substring(16, 24))
        val keyFrag2 = field("${nHex.substring(0, 16)}_${nHex.substring(16, 24)}")
        val tokenRef = field("${eHex.substring(48, 64)}_${eHex.substring(56, 64)}")
        val wasmB64 = field("w_payload")
        if (frag1.isBlank() || ivB64.isBlank() || keyFrag2.isBlank() || tokenRef.isBlank() || wasmB64.isBlank()) {
            Log.e(TAG, "flixcloud v2: missing fields seed=$seed kf=$frag1 iv=$ivB64 k2=$keyFrag2 tok=$tokenRef wasm=${wasmB64.isNotBlank()}")
            return ""
        }

        val tokenRes = app.get("$FLIX/api/m3u8/$tokenRef", headers = mapOf(
            "User-Agent" to UA,
            "Referer" to referer,
            "Origin" to FLIX,
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest"
        ))
        val tokenText = tokenRes.text
        cookieCollector.putAll(tokenRes.cookies)
        if (tokenRes.code != 200) {
            Log.e(TAG, "flixcloud v2: token api http ${tokenRes.code} body=${tokenText.take(120)}")
            return ""
        }
        val apiJson = try {
            JSONObject(tokenText)
        } catch (e: Exception) {
            Log.e(TAG, "flixcloud v2: token api not json", e)
            return ""
        }
        val vidKey = sha256Hex(tokenRef + "vid").substring(0, 10)
        val keyKey = sha256Hex(tokenRef + "key").substring(0, 10)
        val blobB64 = apiJson.optString(vidKey, "")
        val frag3B64 = apiJson.optString(keyKey, "")
        if (blobB64.isBlank() || frag3B64.isBlank()) {
            Log.e(TAG, "flixcloud v2: token api missing keys have=${apiJson.keys().asSequence().take(8).toList()} want=$vidKey/$keyKey")
            return ""
        }

        val q = seed.substring(0, 8).toLong(16).toInt()

        val runner = WasmRunner(Base64.decode(wasmB64, Base64.DEFAULT))
        val memory = ByteArray(65536)
        val frag1B = Base64.decode(frag1, Base64.DEFAULT)
        val frag2B = Base64.decode(keyFrag2, Base64.DEFAULT)
        val frag3B = Base64.decode(frag3B64, Base64.DEFAULT)
        val len = frag1B.size
        frag1B.copyInto(memory, 1000)
        frag2B.copyInto(memory, 1000 + len)
        frag3B.copyInto(memory, 1000 + 2 * len)
        runner.run("_s", intArrayOf(q), memory)
        runner.run("_r", intArrayOf(1000, 1000 + len, 1000 + 2 * len, 1000 + 3 * len, len), memory)
        val h = memory.copyOfRange(1000 + 3 * len, 1000 + 4 * len)

        val rt = pbkdf2Sha256(h, seed.toByteArray(), 1000, 32)
        val seedBytes = seed.toByteArray()
        for (o in rt.indices) rt[o] = (rt[o].toInt() xor seedBytes[o % seedBytes.size].toInt()).toByte()
        val aesKey = MessageDigest.getInstance("SHA-256").digest(rt)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            IvParameterSpec(Base64.decode(ivB64, Base64.DEFAULT))
        )
        return String(cipher.doFinal(Base64.decode(blobB64, Base64.DEFAULT)), Charsets.UTF_8)
    }

    private fun extractSectionArray(html: String, key: String): List<SearchResponse> {
        val match = Regex("""["']?$key["']?\s*:\s*\[""").find(html) ?: return emptyList()
        val startIdx = html.indexOf('[', match.range.first)
        if (startIdx == -1) return emptyList()

        var bracketCount = 0
        var endIdx = -1
        for (i in startIdx until html.length) {
            if (html[i] == '[') bracketCount++
            else if (html[i] == ']') bracketCount--
            if (bracketCount == 0) { endIdx = i + 1; break }
        }
        if (endIdx == -1) return emptyList()

        val arrayStr = html.substring(startIdx, endIdx)
        val items = mutableListOf<SearchResponse>()
        val parts = arrayStr.split(Regex("""["']?anime_id["']?\s*:\s*["']"""))

        for (i in 1 until parts.size) {
            val part = parts[i]
            val animeId = part.substringBefore("\"").substringBefore("'")
            if (animeId.isBlank() || animeId.length > 200) continue

            val window = part.take(2000).unesc()

            var title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?user_preferred["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = Regex("""["']?romaji["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (title.isBlank()) title = animeId.replace("-", " ").replaceFirstChar { it.uppercase() }

            var poster = Regex("""["']?extra_large["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""
            if (poster.isBlank()) poster = Regex("""["']?large["']?\s*:\s*["']([^"']+)""").find(window)?.groupValues?.get(1) ?: ""

            val url = "$mainUrl/anime/$animeId"
            if (items.none { it.url == url }) {
                items.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster.unesc()
                })
            }
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get("$mainUrl/home", headers = mapOf("User-Agent" to UA)).text
        val homeItems = mutableListOf<HomePageList>()

        val sections = listOf(
            "latest_aired" to "Latest Episodes",
            "new_on_site" to "New on Site",
            "trending" to "Trending",
            "upcoming" to "Upcoming"
        )

        for ((key, title) in sections) {
            val items = extractSectionArray(html, key)
            if (items.isNotEmpty()) homeItems.add(HomePageList(title, items))
        }

        return newHomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val res = app.get("$mainUrl/api/v1/search?limit=30&q=$query", headers = mapOf("User-Agent" to UA)).text
            val json = JSONObject(res)
            val arr = json.optJSONArray("results") ?: json.optJSONArray("hits") ?: JSONArray()
            if (arr.length() > 0) {
                val items = mutableListOf<SearchResponse>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val slug = o.optString("anime_id")
                    if (slug.isBlank()) continue
                    val t = o.optJSONObject("title")
                    val title = t?.optString("english")?.takeIf { it.isNotBlank() }
                        ?: t?.optString("romaji")?.takeIf { it.isNotBlank() } ?: slug
                    val poster = o.optJSONObject("cover_image")?.optString("large") ?: ""
                    items.add(newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                        this.posterUrl = poster
                    })
                }
                if (items.isNotEmpty()) return items
            }
        } catch (e: Exception) { }

        val html = app.get("$mainUrl/search?q=$query", headers = mapOf("User-Agent" to UA)).text
        return extractSectionArray(html, "results")
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = mapOf("User-Agent" to UA)).text
        val document = Jsoup.parse(html)
        val slug = url.substringAfter("/anime/").substringAfter("/watch/").substringBefore("?")

        var title = Regex("""["']?english["']?\s*:\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
        if (title.isNullOrBlank()) title = document.selectFirst("h1")?.text()?.trim()
            ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

        var poster = Regex("""["']?extra_large["']?\s*:\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
        if (poster.isNullOrBlank()) poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        poster = poster.unesc()

        val plot = Jsoup.parse(
            Regex("""["']?description["']?\s*:\s*["']((?:[^"'\\]|\\.)*)["']""")
                .find(html)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\u003C", "<") ?: ""
        ).text().ifBlank { document.selectFirst("meta[property=og:description]")?.attr("content") ?: "" }

        val episodes = mutableListOf<Episode>()
        try {
            val epsRes = app.get(
                "$mainUrl/api/v1/anime/$slug/episodes?limit=2000",
                headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")
            ).text

            val jsonArray = if (epsRes.trim().startsWith("[")) JSONArray(epsRes)
            else JSONObject(epsRes).optJSONArray("data") ?: JSONArray()

            for (i in 0 until jsonArray.length()) {
                val epObj = jsonArray.getJSONObject(i)
                val epNum = epObj.optInt("episode_number", epObj.optInt("number", -1))
                if (epNum == -1) continue

                episodes.add(
                    newEpisode("$mainUrl/watch/$slug?ep=$epNum") {
                        name = epObj.optString("title", "").ifBlank { "Episode $epNum" }
                        episode = epNum
                        posterUrl = epObj.optString("thumbnail", "").ifBlank { poster }
                    }
                )
            }
        } catch (e: Exception) { }

        if (episodes.isEmpty()) {
            for (a in document.select("a[href*=/watch/]")) {
                val epHref = fixUrlNull(a.attr("href")) ?: continue
                val epNum = Regex("""[?&]ep=(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                episodes.add(newEpisode(epHref) { name = "Episode $epNum"; episode = epNum; posterUrl = poster })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.episode })
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
        val slug = cleanData.substringAfter("/watch/").substringBefore("?")
        val epNum = Regex("""[?&]ep=(\d+)""").find(cleanData)?.groupValues?.get(1) ?: "1"
        val wantDub = cleanData.contains("lang=dub")

        val apiHeaders = mapOf(
            "User-Agent" to UA,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "application/json, text/plain, */*"
        )

        val seenLinks = hashSetOf<String>()

        suspend fun push(
            url: String,
            label: String,
            isM3u8: Boolean,
            referer: String,
            cookieHeader: String = ""
        ): Boolean {
            val u = url.unesc().trim().trimEnd(',', ';', ')')
            if (u.isBlank() || u.startsWith("blob:") || !seenLinks.add(u)) return false
            val headers = mutableMapOf<String, String>(
                "User-Agent" to UA,
                "Referer" to referer,
                "Origin" to FLIX,
                "Accept" to "*/*"
            )
            if (cookieHeader.isNotBlank()) headers["Cookie"] = cookieHeader
            callback(
                newExtractorLink(source = name, name = label, url = u,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        }

        // ---------------------------------------------------------------
        // 1) Get AniList / TMDB ids (public watch API, no auth needed)
        // ---------------------------------------------------------------
        var anilistId = ""
        var tmdbId = ""
        try {
            val watchRes = app.get(
                "$mainUrl/api/v1/watch/$slug?ep=$epNum&tz=UTC",
                headers = apiHeaders
            ).text
            anilistId = Regex("""/bx(\d+)-""").find(watchRes)?.groupValues?.get(1) ?: ""
            tmdbId = Regex(""""themoviedb_id"\s*:\s*(\d+)""").find(watchRes)?.groupValues?.get(1) ?: ""
        } catch (e: Exception) { }

        if (anilistId.isBlank()) {
            anilistId = Regex("""/bx(\d+)-""").find(
                app.get(cleanData, headers = apiHeaders).text
            )?.groupValues?.get(1) ?: ""
        }

        // ---------------------------------------------------------------
        // 2) Public server list endpoint (works WITHOUT login)
        // ---------------------------------------------------------------
        val flixApiUrl = if (anilistId.isNotBlank())
            "$mainUrl/api/flix/$anilistId/$epNum"
        else
            "$mainUrl/api/flix/0/$epNum?tmdb=$tmdbId&season=1"

        val servers = mutableListOf<Pair<String, String>>() // name -> dataLink
        try {
            val res = app.get(flixApiUrl, headers = apiHeaders).text.unesc()
            val arr = if (res.trim().startsWith("[")) JSONArray(res.trim())
            else JSONObject(res).optJSONArray("servers")

            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val link = o.optString("dataLink", "")
                    val type = o.optString("dataType", "sub")
                    val name = o.optString("serverName", "HD")
                    if (link.isBlank()) continue
                    if (wantDub && !type.contains("dub")) continue
                    if (!wantDub && type.contains("dub")) continue
                    servers.add(name to link)
                }
                // fallback: take everything if filter emptied the list
                if (servers.isEmpty()) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val link = o.optString("dataLink", "")
                        if (link.isNotBlank()) servers.add(o.optString("serverName", "HD") to link)
                    }
                }
            }
        } catch (e: Exception) { }

        // ---------------------------------------------------------------
        // 3) Resolve each FlixCloud embed
        // ---------------------------------------------------------------
        val cookieCollector = mutableMapOf<String, String>()
        for ((serverName, rawLink) in servers) {
            // keep the exact embed URL — flixcloud rejects extra query params (500)
            val link = rawLink.trim()

            var ok = false

            // a) flixcloud v2: fetch the embed page fresh, derive the obfuscated
            //    fields, mint a playback token, run the wasm key-derivation then
            //    PBKDF2/AES to reveal the m3u8 URL.
            if (EMBED_ID_REGEX.find(link) != null) {
                try {
                    val pageRes = app.get(
                        link,
                        headers = mapOf(
                            "User-Agent" to UA,
                            "Referer" to "$mainUrl/",
                            "Accept" to "*/*",
                            "Cache-Control" to "no-cache, no-store",
                            "Pragma" to "no-cache"
                        )
                    )
                    cookieCollector.putAll(pageRes.cookies)
                    val page = pageRes.text.unesc()

                    val m3u8 = decryptFlixV2(page, link, cookieCollector)
                    if (m3u8.startsWith("http")) {
                        val cookieHeader = cookieCollector.entries.joinToString("; ") { "${it.key}=${it.value}" }
                        if (push(m3u8, serverName, true, "$FLIX/", cookieHeader)) ok = true
                    }

                    for (m in SUB_REGEX.findAll(page)) {
                        subtitleCallback(newSubtitleFile("Subtitles", m.value.unesc()))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // b) fallback: let installed extractors handle non-flixcloud embeds
            if (!ok) {
                try {
                    if (loadExtractor(link, cleanData, subtitleCallback, callback)) ok = true
                } catch (e: Exception) { }
            }

            if (ok) found = true
        }

        return found
    }

    // The flixcloud CDN (fetch7.flixcloud.cc) serves a Cloudflare JS challenge to
    // bot-like requests (plain okhttp gets a 403 block page → "manifest malformed"
    // in the player). Returning a CloudflareKiller interceptor makes CloudStream run
    // the challenge inside a WebView and retry with the cf_clearance cookie.
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return try {
            if (extractorLink.url.contains("flixcloud.cc") || extractorLink.referer.contains("flixcloud.cc")) {
                cloudflareKiller
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private val cloudflareKiller: CloudflareKiller by lazy { CloudflareKiller() }
}