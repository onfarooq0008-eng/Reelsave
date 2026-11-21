package com.reelsave.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class MediaItem(val url: String, val ext: String)

sealed class ApiResult {
    data class Single(val item: MediaItem) : ApiResult()
    data class Picker(val items: List<MediaItem>) : ApiResult()
    data class Failure(val message: String) : ApiResult()
}

/** Client for FastSaverAPI (https://api.fastsaver.io): GET /v1/fetch?url=...  with X-Api-Key header. */
object FastSaverApi {
    private const val BASE = "https://api.fastsaver.io/v1/fetch"

    fun fetch(apiKey: String, link: String): ApiResult {
        return try {
            val conn = URL(BASE + "?url=" + URLEncoder.encode(link, "UTF-8")).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-Api-Key", apiKey.trim())
            conn.setRequestProperty("User-Agent", "ReelSave/1.0")

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

            val json = try { JSONObject(text) } catch (e: Exception) {
                return ApiResult.Failure(httpMessage(code) ?: "Unexpected response (HTTP $code): ${text.take(150)}")
            }

            val failed = code !in 200..299 || (json.has("ok") && !json.optBoolean("ok", true))
            if (failed) {
                val detail = listOf("error", "message", "detail")
                    .map { json.opt(it)?.toString().orEmpty() }
                    .firstOrNull { it.isNotBlank() && it != "null" }
                return ApiResult.Failure(httpMessage(code) ?: ("Error: " + (detail ?: text.take(150))))
            }

            val items = json.optJSONArray("items")
            if (items != null && items.length() > 0) {
                val list = (0 until items.length()).mapNotNull { toItem(items.getJSONObject(it)) }
                return when {
                    list.isEmpty() -> ApiResult.Failure("No downloadable media found.")
                    list.size == 1 -> ApiResult.Single(list[0])
                    else -> ApiResult.Picker(list)
                }
            }
            toItem(json)?.let { ApiResult.Single(it) } ?: ApiResult.Failure("No downloadable media found.")
        } catch (e: Exception) {
            ApiResult.Failure("Network error: ${e.message}")
        }
    }

    private fun toItem(o: JSONObject): MediaItem? {
        val url = o.optString("download_url").ifBlank { return null }
        val type = o.optString("type", "video")
        val ext = if (type == "image" || type == "photo") "jpg" else "mp4"
        return MediaItem(url, ext)
    }

    private fun httpMessage(code: Int): String? = when (code) {
        401, 403 -> "API key rejected. Check your key in Settings."
        402 -> "Out of credits. Check your FastSaver dashboard."
        429 -> "Too many requests. Wait a moment and try again."
        404 -> "Media not found (private or deleted post?)."
        in 500..599 -> "Server error (HTTP $code). Try again later."
        else -> null
    }
}
