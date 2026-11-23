package com.example.douyinfloat

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class ParseResult(
    val type: String,       // "video" | "images"
    val urls: List<String>,
    val title: String,
    val id: String
)

/**
 * 抖音分享链接 -> 资源直链。
 *
 * 参考社区开源实现（gloryhry/douyinVd、SSSYoung/DouYinKit、521w/douyin-mcp）：
 *   短链 302 -> aweme_id -> iesdouyin 分享页 _ROUTER_DATA -> playwm 换 play / 1080p 端点 -> 最终直链。
 * 只用平台自带 API，不引第三方网络库。
 */
class DouyinParser(private val timeoutMs: Int = 15000) {

    companion object {
        private const val UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

        private val URL_RE = Regex("https?://[^\\s\"'<>()（）\\[\\]{}，。；、]+")
        private val ID_RE = Regex(
            "(?:/share/(?:video|slides|note)/|/video/|/note/|modal_id=|aweme_id=|item_ids=)([0-9]{8,25})"
        )
    }

    private data class Resp(
        val status: Int,
        val location: String?,
        val body: String,
        val cookies: List<String>
    )

    private fun request(
        urlStr: String,
        extraHeaders: Map<String, String> = emptyMap(),
        followRedirects: Boolean = false
    ): Resp {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = followRedirects
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }

        val status = conn.responseCode
        val location = conn.getHeaderField("Location")
        val cookies = conn.headerFields["Set-Cookie"]?.filterNotNull() ?: emptyList()
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val body = try {
            if (stream == null) "" else BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } catch (_: Exception) {
            ""
        }
        conn.disconnect()
        return Resp(status, location, body, cookies)
    }

    private fun firstUrl(text: String): String? =
        URL_RE.find(text)?.value?.trimEnd('.', ',', ';', '!', '?', '，', '。', '；', '！', '？', '、')

    private fun idOf(vararg candidates: String?): String? {
        for (s in candidates) {
            if (s.isNullOrEmpty()) continue
            val m = ID_RE.find(s) ?: continue
            return m.groupValues[1]
        }
        return null
    }

    /** 手动跟随短链，只读 Location，避免把巨大的分享页 HTML 拉下来 */
    private fun expandShortLink(input: String, maxHops: Int = 6): Pair<String?, String> {
        var current = input
        var jar = ""
        val seen = HashSet<String>()
        repeat(maxHops) {
            if (!seen.add(current)) return Pair(idOf(current), jar)
            val r = try {
                request(current)
            } catch (_: Exception) {
                return Pair(idOf(current), jar)
            }
            if (!r.cookies.isEmpty()) {
                jar = r.cookies.joinToString("; ") { it.substringBefore(';') }
            }
            val loc = r.location ?: return Pair(idOf(current), jar)
            // 抖音短链经常给相对 Location，必须按当前地址 resolve，不能当绝对 URL 直接 new
            val next = URI(current).resolve(loc).toString()
            val id = idOf(next) ?: idOf(loc)
            if (id != null) return Pair(id, jar)
            current = next
        }
        return Pair(idOf(current), jar)
    }

    /** 分享页首次常只回 ttwid Cookie、不回数据，必须带 Cookie 再请求一次 */
    private fun sharePage(id: String, jar: String): String {
        val url = "https://www.iesdouyin.com/share/video/$id/"
        val headers = mutableMapOf("Referer" to "https://www.douyin.com/")
        val first = request(url, headers)
        if (first.body.contains("_ROUTER_DATA")) return first.body

        val cookie = if (jar.isNotEmpty()) {
            jar
        } else {
            first.cookies.joinToString("; ") { it.substringBefore(';') }
        }
        if (cookie.isNotEmpty()) headers["Cookie"] = cookie
        val second = request(url, headers)
        return second.body
    }

    /** 花括号配对截取，字符串里的 } 不参与计数（作品文案里经常带括号） */
    private fun routerData(html: String): JSONObject? {
        val anchor = html.indexOf("window._ROUTER_DATA")
        if (anchor < 0) return null
        val start = html.indexOf('{', anchor)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var quote = ' '
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                if (escaped) { escaped = false; continue }
                if (c == '\\') { escaped = true; continue }
                if (c == quote) inString = false
                continue
            }
            if (c == '"' || c == '\'') {
                inString = true
                quote = c
                continue
            }
            if (c == '{') {
                depth += 1
            } else if (c == '}') {
                depth -= 1
                if (depth == 0) {
                    return try {
                        JSONObject(html.substring(start, i + 1))
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
        return null
    }

    private fun findItem(node: Any?, depth: Int = 0): JSONObject? {
        if (node == null || depth > 7) return null
        when (node) {
            is JSONObject -> {
                val arr = node.optJSONArray("item_list")
                if (arr != null && arr.length() > 0) {
                    val first = arr.optJSONObject(0)
                    if (first != null) return first
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val hit = findItem(node.opt(keys.next()), depth + 1)
                    if (hit != null) return hit
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until node.length()) {
                    val hit = findItem(node.opt(i), depth + 1)
                    if (hit != null) return hit
                }
            }
        }
        return null
    }

    private fun itemFrom(router: JSONObject?, id: String): JSONObject? {
        val ld = router?.optJSONObject("loaderData") ?: return null
        val keys = ld.keys()
        var preferred: JSONObject? = null
        var fallback: JSONObject? = null
        while (keys.hasNext()) {
            val k = keys.next()
            val v = ld.optJSONObject(k) ?: continue
            if (k.contains("video_$id/page") || k.contains("note_$id/page")) {
                preferred = v
                break
            }
            if ((k.startsWith("video_") || k.startsWith("note_")) && fallback == null) fallback = v
        }
        return findItem(preferred ?: fallback ?: ld) ?: findItem(router)
    }

    private fun videoUrls(item: JSONObject): List<String> {
        val play = item.optJSONObject("video")?.optJSONObject("play_addr") ?: return emptyList()
        val list = ArrayList<String>()
        val arr = play.optJSONArray("url_list")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val u = arr.optString(i, "")
                if (u.startsWith("http")) list.add(u)
            }
        }
        var vid = play.optString("uri", "")
        if (vid.isEmpty() && list.isNotEmpty()) {
            vid = Regex("video_id=([^&]+)").find(list[0])?.groupValues?.get(1) ?: ""
        }
        val out = LinkedHashSet<String>()
        if (vid.isNotEmpty()) {
            out.add("https://aweme.snssdk.com/aweme/v1/play/?video_id=$vid&ratio=1080p&line=0")
        }
        for (u in list) out.add(u.replace("playwm", "play"))
        return out.toList()
    }

    private fun imageUrls(item: JSONObject): List<String> {
        val images = item.optJSONArray("images") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until images.length()) {
            val im = images.optJSONObject(i) ?: continue
            val arr = im.optJSONArray("url_list") ?: continue
            if (arr.length() > 0) {
                val u = arr.optString(arr.length() - 1, "")
                if (u.startsWith("http")) out.add(u)
            }
        }
        return out
    }

    /** 跟随一次重定向拿签名 CDN 直链；没重定向就把接口地址本身当下载地址 */
    private fun resolveFinal(url: String): String {
        val r = try {
            request(url)
        } catch (_: Exception) {
            return url
        }
        val loc = r.location
        return if (loc != null && loc.startsWith("http")) loc else url
    }

    fun parse(raw: String): ParseResult {
        val input = firstUrl(raw) ?: throw IllegalArgumentException("没找到链接")
        if (!Regex("douyin\\.com|iesdouyin\\.com|snssdk\\.com", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            throw IllegalArgumentException("不是抖音链接")
        }

        val (id, jar) = expandShortLink(input)
        if (id == null) throw IllegalArgumentException("链接里没识别出作品 ID")

        val item = itemFrom(routerData(sharePage(id, jar)), id)
            ?: throw IllegalArgumentException("分享页没返回作品数据")

        val title = item.optString("desc", "")
        val images = imageUrls(item)
        val candidates = videoUrls(item)
        if (candidates.isEmpty()) {
            if (images.isNotEmpty()) return ParseResult("images", images, title, id)
            throw IllegalArgumentException("没取到视频地址")
        }
        return ParseResult("video", listOf(resolveFinal(candidates[0])), title, id)
    }
}
