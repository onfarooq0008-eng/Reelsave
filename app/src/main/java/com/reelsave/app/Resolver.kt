package com.reelsave.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Picks the right provider for a link and falls back to the optional ones the user configured. */
object Resolver {
    fun resolve(ytUrl: String, fsKey: String, cobaltUrl: String, cobaltKey: String, link: String): ApiResult {
        val host = try { URL(link).host.lowercase() } catch (e: Exception) { return ApiResult.Failure("Invalid link.") }
        val errors = mutableListOf<String>()
        fun ok(r: ApiResult): ApiResult? {
            if (r is ApiResult.Failure) { errors.add(r.message); return null }
            return r
        }

        val isTikTok = host.endsWith("tiktok.com")
        val isX = host == "x.com" || host.endsWith(".x.com") || host.endsWith("twitter.com")

        // 0) Your own yt-dlp server (handles most sites, uses its cookies)
        if (ytUrl.isNotBlank()) ok(YtDlpServer.fetch(ytUrl, link))?.let { return it }
        // 1) Built-in providers that need no key
        if (isTikTok) ok(KeylessApis.tiktok(link))?.let { return it }
        if (isX) ok(KeylessApis.twitter(link))?.let { return it }
        if (host.endsWith("instagram.com")) ok(KeylessApis.instagram(link))?.let { return it }
        // 2) Optional providers from Settings
        if (fsKey.isNotBlank()) ok(FastSaverApi.fetch(fsKey, link))?.let { return it }
        if (cobaltUrl.isNotBlank()) ok(CobaltApi.request(cobaltUrl, cobaltKey, link))?.let { return it }

        if (errors.isEmpty()) {
            return ApiResult.Failure(
                "This site needs an extra provider. Open Settings and add a free FastSaver key or a cobalt instance URL. " +
                "TikTok, X/Twitter and Instagram work without any setup."
            )
        }
        return ApiResult.Failure(errors.last())
    }
}

object Http {
    fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 45000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) ReelSave/1.0")
        c.setRequestProperty("Accept", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        val s = if (code in 200..299) c.inputStream else c.errorStream
        return code to (s?.bufferedReader()?.use { it.readText() } ?: "")
    }
}

object KeylessApis {
    /** Instagram public posts/reels/carousels via the public embed page (no login, no key). */
    fun instagram(link: String): ApiResult = try {
        val code = Regex("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)").find(link)?.groupValues?.get(1)
            ?: return ApiResult.Failure("Instagram: open the post/reel link (instagram.com/reel/...). Stories and share links are not supported.")
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        val (status, html) = Http.get(
            "https://www.instagram.com/p/$code/embed/captioned/",
            mapOf("User-Agent" to ua, "Accept" to "text/html,application/xhtml+xml", "Accept-Language" to "en-US,en;q=0.9")
        )
        if (html.isBlank()) ApiResult.Failure("Instagram: empty response (HTTP $status).")
        else {
            val items = mutableListOf<MediaItem>()

            // 1) Structured data: "contextJSON":"<escaped json>" -> shortcode_media
            try {
                val m = Regex("\"contextJSON\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(html)
                if (m != null) {
                    val inner = JSONObject("{\"v\":\"" + m.groupValues[1] + "\"}").getString("v")
                    val root = JSONObject(inner)
                    val sm = root.optJSONObject("gql_data")?.optJSONObject("shortcode_media")
                        ?: root.optJSONObject("shortcode_media")
                    if (sm != null) {
                        fun add(n: JSONObject) {
                            if (n.optBoolean("is_video") && n.optString("video_url").isNotBlank())
                                items.add(MediaItem(n.getString("video_url"), "mp4"))
                            else if (n.optString("display_url").isNotBlank())
                                items.add(MediaItem(n.getString("display_url"), "jpg"))
                        }
                        val edges = sm.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
                        if (edges != null && edges.length() > 0)
                            for (i in 0 until edges.length()) add(edges.getJSONObject(i).getJSONObject("node"))
                        else add(sm)
                    }
                }
            } catch (e: Exception) { /* fall through to regex */ }

            // 2) Fallback: look for raw video_url / display_url in the page
            if (items.isEmpty()) {
                val clean = html.replace("\\\"", "\"").replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                Regex("\"video_url\":\"(https:[^\"]+)\"").find(clean)?.let { items.add(MediaItem(it.groupValues[1], "mp4")) }
                if (items.isEmpty())
                    Regex("<video[^>]+src=\"(https:[^\"]+)\"").find(clean)?.let { items.add(MediaItem(it.groupValues[1], "mp4")) }
                if (items.isEmpty())
                    Regex("\"display_url\":\"(https:[^\"]+)\"").find(clean)?.let { items.add(MediaItem(it.groupValues[1], "jpg")) }
                if (items.isEmpty())
                    Regex("class=\"EmbeddedMediaImage\"[^>]+src=\"(https:[^\"]+)\"").find(clean)?.let { items.add(MediaItem(it.groupValues[1], "jpg")) }
            }

            when {
                items.isEmpty() -> ApiResult.Failure("Instagram: couldn't read this post. It may be private, age-restricted, or Instagram is limiting this connection.")
                items.size == 1 -> ApiResult.Single(items[0])
                else -> ApiResult.Picker(items)
            }
        }
    } catch (e: Exception) { ApiResult.Failure("Instagram: ${e.message}") }

    /** TikTok via tikwm.com (free, no key, no watermark). */
    fun tiktok(link: String): ApiResult = try {
        val (code, text) = Http.get("https://tikwm.com/api/?hd=1&url=" + URLEncoder.encode(link, "UTF-8"))
        val json = JSONObject(text)
        if (json.optInt("code", -1) != 0) {
            val msg = json.optString("msg")
            ApiResult.Failure(if (msg.contains("limit", true)) "TikTok service is busy (rate limit). Wait a few seconds and retry."
                              else "TikTok: ${msg.ifBlank { "HTTP $code" }}")
        } else {
            val d = json.getJSONObject("data")
            val imgs = d.optJSONArray("images")
            if (imgs != null && imgs.length() > 0) {
                val list = (0 until imgs.length()).map { MediaItem(imgs.getString(it), "jpg") }
                if (list.size == 1) ApiResult.Single(list[0]) else ApiResult.Picker(list)
            } else {
                val u = d.optString("hdplay").ifBlank { d.optString("play") }
                if (u.isBlank()) ApiResult.Failure("TikTok: no video found.")
                else ApiResult.Single(MediaItem(if (u.startsWith("/")) "https://www.tikwm.com$u" else u, "mp4"))
            }
        }
    } catch (e: Exception) { ApiResult.Failure("TikTok: ${e.message}") }

    /** X / Twitter via api.fxtwitter.com (free, no key). */
    fun twitter(link: String): ApiResult = try {
        val id = Regex("status(?:es)?/(\\d+)").find(link)?.groupValues?.get(1)
            ?: return ApiResult.Failure("X: could not find the post id in this link.")
        val (code, text) = Http.get("https://api.fxtwitter.com/status/$id")
        val tweet = JSONObject(text).optJSONObject("tweet")
        if (tweet == null) ApiResult.Failure("X: post not found (HTTP $code).")
        else {
            val media = tweet.optJSONObject("media")
            val items = mutableListOf<MediaItem>()
            media?.optJSONArray("videos")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).optString("url").takeIf { it.isNotBlank() }?.let { items.add(MediaItem(it, "mp4")) }
            }
            if (items.isEmpty()) media?.optJSONArray("photos")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).optString("url").takeIf { it.isNotBlank() }?.let { items.add(MediaItem(it, "jpg")) }
            }
            when {
                items.isEmpty() -> ApiResult.Failure("X: this post has no video or photo.")
                items.size == 1 -> ApiResult.Single(items[0])
                else -> ApiResult.Picker(items)
            }
        }
    } catch (e: Exception) { ApiResult.Failure("X: ${e.message}") }
}

/** Optional: any cobalt-compatible instance (URL and key come from Settings). */
object CobaltApi {
    fun request(baseUrl: String, apiKey: String, link: String): ApiResult = try {
        val conn = URL(baseUrl.trim().trimEnd('/') + "/").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.connectTimeout = 20000; conn.readTimeout = 60000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/json")
        if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Api-Key ${apiKey.trim()}")
        val body = JSONObject().put("url", link).put("videoQuality", "1080").put("downloadMode", "auto")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        val json = JSONObject(text)
        fun fix(u: String) = if (u.startsWith("http")) u else "https://" + u.trimStart('/')
        when (json.optString("status")) {
            "tunnel", "redirect" -> ApiResult.Single(MediaItem(fix(json.getString("url")), "mp4"))
            "picker" -> {
                val a = json.getJSONArray("picker")
                ApiResult.Picker((0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    MediaItem(fix(o.getString("url")), if (o.optString("type") == "photo") "jpg" else "mp4")
                })
            }
            else -> ApiResult.Failure("Cobalt: " + (json.optJSONObject("error")?.optString("code") ?: text.take(120)))
        }
    } catch (e: Exception) { ApiResult.Failure("Cobalt: ${e.message}") }
}

/**
 * Client for a self-hosted yt-dlp web server (health check: {"status":"ok","yt_dlp":...,"ffmpeg":true}).
 * Its routes aren't documented, so this tries the common conventions and reports exactly what each returned.
 */
object YtDlpServer {
    private val GET_PATHS = listOf("/download", "/info", "/extract", "/api/download", "/api/info", "/get", "/video", "/fetch")
    private val POST_PATHS = listOf("/download", "/info", "/extract", "/api/download", "/api/info", "/")

    fun fetch(base: String, link: String): ApiResult {
        val b = base.trim().trimEnd('/')
        val enc = URLEncoder.encode(link, "UTF-8")
        val tried = mutableListOf<String>()

        for (path in GET_PATHS) {
            val u = "$b$path?url=$enc"
            call("GET", u, null, b, tried, path)?.let { return it }
        }
        val body = JSONObject().put("url", link).toString()
        for (path in POST_PATHS) {
            call("POST", "$b$path", body, b, tried, "POST $path")?.let { return it }
        }
        return ApiResult.Failure("Server: " + tried.take(6).joinToString(" | "))
    }

    private fun call(method: String, u: String, body: String?, base: String, tried: MutableList<String>, label: String): ApiResult? {
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 20000; c.readTimeout = 150000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "ReelSave/1.0")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val ct = (c.contentType ?: "").lowercase()
            if (code in 200..299) {
                if (method == "GET" && (ct.startsWith("video/") || ct.startsWith("audio/") || ct.startsWith("image/") || ct.contains("octet-stream"))) {
                    c.disconnect()
                    return ApiResult.Single(MediaItem(u, if (ct.startsWith("audio/")) "mp3" else if (ct.startsWith("image/")) "jpg" else "mp4"))
                }
                val text = c.inputStream.bufferedReader().use { it.readText() }
                parse(base, text)?.let { return it }
                tried.add("$label: 200 but unknown reply ${text.take(70)}")
            } else {
                val text = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code != 404 && code != 405) tried.add("$label: $code ${text.take(90)}")
            }
            null
        } catch (e: Exception) { tried.add("$label: ${e.message}"); null }
    }

    private fun abs(base: String, u: String) = when {
        u.startsWith("http") -> u
        u.startsWith("/") -> base + u
        else -> "$base/$u"
    }

    private fun extOf(o: JSONObject, url: String): String {
        val e = o.optString("ext").lowercase()
        if (e in listOf("mp4", "mp3", "jpg", "jpeg", "png", "webm", "m4a", "gif")) return e
        val m = Regex("\\.(mp4|mp3|jpg|jpeg|png|webm|m4a|gif)(?:\\?|$)", RegexOption.IGNORE_CASE).find(url)
        return m?.groupValues?.get(1)?.lowercase() ?: "mp4"
    }

    private fun parse(base: String, text: String): ApiResult? {
        val o = try { JSONObject(text) } catch (e: Exception) { return null }

        for (k in listOf("download_url", "direct_url", "video_url", "file_url", "download", "file", "link", "url")) {
            val v = o.opt(k)
            if (v is String && v.isNotBlank() && (v.startsWith("http") || v.startsWith("/")))
                return ApiResult.Single(MediaItem(abs(base, v), extOf(o, v)))
        }
        val formats = o.optJSONArray("formats")
        if (formats != null && formats.length() > 0) {
            var best: JSONObject? = null; var bestH = -1
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val u = f.optString("url")
                if (!u.startsWith("http")) continue
                val both = f.optString("vcodec", "none") != "none" && f.optString("acodec", "none") != "none"
                val h = f.optInt("height", 0)
                if (both && h >= bestH) { best = f; bestH = h }
            }
            if (best == null) for (i in formats.length() - 1 downTo 0) {
                val f = formats.getJSONObject(i)
                if (f.optString("url").startsWith("http")) { best = f; break }
            }
            best?.let { return ApiResult.Single(MediaItem(it.getString("url"), extOf(it, it.getString("url")))) }
        }
        for (k in listOf("entries", "items", "medias", "results", "files")) {
            val a = o.optJSONArray(k) ?: continue
            val list = (0 until a.length()).mapNotNull { i ->
                val e = a.optJSONObject(i) ?: return@mapNotNull null
                val u = listOf("download_url", "url", "video_url", "direct_url").map { e.optString(it) }
                    .firstOrNull { it.startsWith("http") || it.startsWith("/") } ?: return@mapNotNull null
                MediaItem(abs(base, u), extOf(e, u))
            }
            if (list.size == 1) return ApiResult.Single(list[0])
            if (list.size > 1) return ApiResult.Picker(list)
        }
        return null
    }
}
