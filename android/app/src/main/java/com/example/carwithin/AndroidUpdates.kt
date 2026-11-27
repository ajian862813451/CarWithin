package com.example.carwithin

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal object AndroidUpdates {
    const val REPO = "https://github.com/xmz28/CarWithin"
    private const val API = "https://api.github.com/repos/xmz28/CarWithin/releases"
    private const val MANIFEST = "update.json"
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val RETRY = 15 * 60 * 1000L
    private val safeTag = Regex("v[0-9]+(?:\\.[0-9]+){1,2}")
    private val safeApk = Regex("[a-zA-Z0-9._-]+\\.apk")
    private val digest = Regex("[a-fA-F0-9]{64}")

    data class Release(val tag: String, val name: String, val notes: String) {
        val url get() = "$REPO/releases/tag/$tag"
    }

    data class Update(
        val release: Release,
        val version: String,
        val build: Int,
        val minSdk: Int,
        val notes: String,
        val download: String,
    )

    private data class Candidate(val release: Release, val assets: JSONArray, val published: String)

    fun isDue(context: Context): Boolean {
        val next = context.getSharedPreferences("android_updates", Context.MODE_PRIVATE).getLong("next_check", 0)
        val now = System.currentTimeMillis()
        return next <= now || next - now > DAY
    }

    fun markChecked(context: Context, success: Boolean) {
        context.getSharedPreferences("android_updates", Context.MODE_PRIVATE).edit()
            .putLong("next_check", System.currentTimeMillis() + if (success) DAY else RETRY)
            .apply()
    }

    suspend fun history(): List<Release> = withContext(Dispatchers.IO) {
        candidates().map { it.release }
    }

    suspend fun inspect(): Update? = withContext(Dispatchers.IO) {
        val latest = JSONObject(read("$API/latest", 1024 * 1024))
        candidate(latest)?.let(::metadata)
    }

    private fun candidates(): List<Candidate> {
        val result = mutableListOf<Candidate>()
        for (page in 1..5) {
            val values = JSONArray(read("$API?per_page=30&page=$page", 4 * 1024 * 1024))
            for (index in 0 until values.length()) {
                candidate(values.getJSONObject(index))?.let(result::add)
            }
            if (values.length() < 30) break
        }
        return result.sortedByDescending { it.published }
    }

    private fun candidate(entry: JSONObject): Candidate? {
        val tag = entry.getString("tag_name")
        if (entry.optBoolean("draft") || entry.optBoolean("prerelease") || !safeTag.matches(tag)) return null
        val assets = entry.optJSONArray("assets") ?: return null
        val manifest = assets.asset(MANIFEST) ?: return null
        return Candidate(Release(tag, entry.optString("name", tag), entry.optString("body")),
            assets, maxOf(entry.optString("published_at"), manifest.optString("created_at")))
    }

    private fun metadata(candidate: Candidate): Update {
        val tag = candidate.release.tag
        val manifest = checkNotNull(candidate.assets.asset(MANIFEST))
        val manifestUrl = "$REPO/releases/download/$tag/$MANIFEST"
        check(manifest.getString("browser_download_url") == manifestUrl && manifest.getLong("size") in 1..131_072) {
            "Android 更新清单地址或大小无效"
        }
        val json = JSONObject(read(manifestUrl, 131_072))
        val version = json.getString("versionName")
        val build = json.getInt("versionCode")
        val minSdk = json.optInt("minSdk", 26)
        val apk = json.getString("apkName")
        val sha256 = json.getString("sha256")
        check(tag == "v$version" &&
            build > 0 && minSdk >= 26 && safeApk.matches(apk) && digest.matches(sha256)
        ) { "Android 更新清单内容无效" }
        val download = "$REPO/releases/download/$tag/$apk"
        val asset = checkNotNull(candidate.assets.asset(apk)) { "发布中缺少 APK 附件" }
        val assetDigest = asset.optString("digest")
        val size = asset.getLong("size")
        check(asset.getString("browser_download_url") == download && size > 0 && (!json.has("size") || json.getLong("size") == size) &&
            (assetDigest.isBlank() || assetDigest == "null" || assetDigest.equals("sha256:$sha256", true))
        ) { "APK 附件与更新清单不一致" }
        return Update(candidate.release, version, build, minSdk, json.optString("notes"), download)
    }

    private fun JSONArray.asset(name: String): JSONObject? =
        (0 until length()).map { getJSONObject(it) }.singleOrNull { it.optString("name") == name }

    private fun read(address: String, limit: Int): String {
        val connection = URL(address).openConnection() as HttpsURLConnection
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.setRequestProperty("User-Agent", "CarWithin-Android/" + BuildConfig.VERSION_NAME)
        try {
            check(connection.responseCode == 200) { "GitHub 返回 HTTP " + connection.responseCode }
            val bytes = connection.inputStream.use { stream ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    check(result.size() + read <= limit) { "GitHub 响应超出大小限制" }
                    result.write(buffer, 0, read)
                }
                result.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }
}
