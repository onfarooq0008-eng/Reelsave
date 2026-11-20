package com.reelsave.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PickerItem(val type: String, val url: String)

sealed class ApiResult {
    data class Single(val url: String, val filename: String?) : ApiResult()
    data class Picker(val items: List<PickerItem>) : ApiResult()
    data class Failure(val message: String) : ApiResult()
}

object CobaltApi {
    fun request(baseUrl: String, apiKey: String, link: String, audioOnly: Boolean): ApiResult {
        return try {
            val conn = URL(baseUrl.trim().trimEnd('/') + "/").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "ReelSave/1.0")
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Api-Key ${apiKey.trim()}")

            val body = JSONObject()
                .put("url", link)
                .put("videoQuality", "1080")
                .put("downloadMode", if (audioOnly) "audio" else "auto")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (text.isBlank()) return ApiResult.Failure("Empty response from server (HTTP $code)")
            val json = try { JSONObject(text) } catch (e: Exception) {
                return ApiResult.Failure("Server did not return JSON (HTTP $code): ${text.take(120)}")
            }

            when (json.optString("status")) {
                "tunnel", "redirect" -> ApiResult.Single(fixUrl(baseUrl, json.getString("url")), json.optString("filename").ifBlank { null })
                "picker" -> {
                    val arr = json.getJSONArray("picker")
                    val items = (0 until arr.length()).map {
                        val o = arr.getJSONObject(it)
                        PickerItem(o.optString("type", "video"), fixUrl(baseUrl, o.getString("url")))
                    }
                    ApiResult.Picker(items)
                }
                "error" -> {
                    val c = json.optJSONObject("error")?.optString("code") ?: "unknown"
                    ApiResult.Failure(friendly(c))
                }
                else -> ApiResult.Failure("Unexpected response: ${text.take(150)}")
            }
        } catch (e: Exception) {
            ApiResult.Failure("Network error: ${e.message}")
        }
    }

    private fun friendly(code: String): String = when {
        code.contains("auth") -> "This server needs an API key. Add it in Settings."
        code.contains("content.post.private") -> "This post is private."
        code.contains("content.video.unavailable") -> "Video unavailable."
        code.contains("link.unsupported") || code.contains("link.invalid") -> "This link is not supported."
        code.contains("fetch.fail") || code.contains("fetch.empty") -> "Could not fetch this media. Try again later."
        code.contains("rate") -> "Too many requests. Wait a moment."
        else -> "Error: $code"
    }

    // Cobalt builds tunnel links from its own API_URL setting. If that is missing the
    // scheme (or points at localhost) the link is unusable, so rebuild it on our base URL.
    private fun fixUrl(baseUrl: String, raw: String): String {
        val base = baseUrl.trim().trimEnd('/')
        var u = raw.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = if (u.startsWith("/")) base + u else "https://$u"
        }
        return try {
            val parsed = URL(u)
            val b = URL(base)
            if (parsed.host != b.host && parsed.path.startsWith("/tunnel")) {
                base + parsed.file
            } else if (parsed.protocol == "http" && parsed.host == b.host) {
                "https://" + parsed.host + parsed.file
            } else u
        } catch (e: Exception) { u }
    }
}
