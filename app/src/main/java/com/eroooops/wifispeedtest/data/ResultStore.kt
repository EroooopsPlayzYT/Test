package com.eroooops.wifispeedtest.data

import android.content.Context
import com.eroooops.wifispeedtest.net.TestConfig
import com.eroooops.wifispeedtest.net.Transport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A completed test, kept so runs can be compared over time and across networks. */
data class TestResult(
    val timestamp: Long,
    val transport: Transport,
    /** SSID on Wi-Fi, carrier name on mobile data. */
    val networkLabel: String?,
    /** Band and channel on Wi-Fi, radio technology on mobile data. */
    val detail: String?,
    val signalDbm: Int?,
    /** Negotiated Wi-Fi link rate; null on mobile data. */
    val linkSpeedMbps: Int?,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val pingMs: Double,
    val jitterMs: Double,
    val bytesUsed: Long = 0L,
)

private const val HISTORY_FILE = "history.json"
private const val MAX_HISTORY = 50
private const val PREFS = "settings"

/** Persists test history to a small JSON file and the test config to prefs. */
class ResultStore(context: Context) {

    private val appContext = context.applicationContext
    private val file: File get() = File(appContext.filesDir, HISTORY_FILE)
    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<TestResult> {
        val raw = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getJSONObject(it).toResult() }
        }.getOrDefault(emptyList())
    }

    fun save(results: List<TestResult>) {
        val array = JSONArray()
        results.take(MAX_HISTORY).forEach { array.put(it.toJson()) }
        runCatching { file.writeText(array.toString()) }
    }

    fun loadConfig(): TestConfig = with(prefs) {
        TestConfig(
            downloadUrl = getString("downloadUrl", null) ?: TestConfig.DEFAULT_DOWNLOAD_URL,
            uploadUrl = getString("uploadUrl", null) ?: TestConfig.DEFAULT_UPLOAD_URL,
            connections = getInt("connections", 4).coerceIn(TestConfig.CONNECTION_RANGE),
            downloadSeconds = getInt("downloadSeconds", 10).coerceIn(TestConfig.DURATION_RANGE),
            uploadSeconds = getInt("uploadSeconds", 10).coerceIn(TestConfig.DURATION_RANGE),
            pingCount = getInt("pingCount", 10),
        )
    }

    fun saveConfig(config: TestConfig) {
        prefs.edit()
            .putString("downloadUrl", config.downloadUrl)
            .putString("uploadUrl", config.uploadUrl)
            .putInt("connections", config.connections)
            .putInt("downloadSeconds", config.downloadSeconds)
            .putInt("uploadSeconds", config.uploadSeconds)
            .putInt("pingCount", config.pingCount)
            .apply()
    }

    fun loadTransport(): Transport = Transport.fromName(prefs.getString("transport", null))

    fun saveTransport(transport: Transport) {
        prefs.edit().putString("transport", transport.name).apply()
    }
}

private fun TestResult.toJson(): JSONObject = JSONObject().apply {
    put("timestamp", timestamp)
    put("transport", transport.name)
    put("networkLabel", networkLabel ?: JSONObject.NULL)
    put("detail", detail ?: JSONObject.NULL)
    put("signalDbm", signalDbm ?: JSONObject.NULL)
    put("linkSpeedMbps", linkSpeedMbps ?: JSONObject.NULL)
    put("downloadMbps", downloadMbps)
    put("uploadMbps", uploadMbps)
    put("pingMs", pingMs)
    put("jitterMs", jitterMs)
    put("bytesUsed", bytesUsed)
}

private fun JSONObject.toResult(): TestResult = TestResult(
    timestamp = optLong("timestamp"),
    transport = Transport.fromName(optStringOrNull("transport")),
    // "ssid"/"band"/"rssiDbm" are the pre-mobile-data key names; reading them
    // keeps history written by an earlier build intact.
    networkLabel = optStringOrNull("networkLabel") ?: optStringOrNull("ssid"),
    detail = optStringOrNull("detail") ?: optStringOrNull("band"),
    signalDbm = optIntOrNull("signalDbm") ?: optIntOrNull("rssiDbm"),
    linkSpeedMbps = optIntOrNull("linkSpeedMbps"),
    downloadMbps = optDouble("downloadMbps", 0.0),
    uploadMbps = optDouble("uploadMbps", 0.0),
    pingMs = optDouble("pingMs", 0.0),
    jitterMs = optDouble("jitterMs", 0.0),
    bytesUsed = optLong("bytesUsed", 0L),
)

private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (isNull(name)) null else optInt(name)
