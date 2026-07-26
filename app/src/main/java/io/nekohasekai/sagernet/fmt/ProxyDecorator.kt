package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ProxyDecorator {

    // Thread-safe caches to prevent redundant network lookups across batch runs
    private val flagCache = ConcurrentHashMap<String, String>()
    private val geminiCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Constructs an OkHttpClient configured to route all traffic strictly
     * through the proxy's local inbound port.
     */
    private fun getProxyHttpClient(localProxyPort: Int): OkHttpClient {
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", localProxyPort)))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Resolves the proxy exit node geolocation via ip-api.com and prepends
     * the corresponding country flag emoji to the proxy display name.
     */
    suspend fun decorateWithFlag(
        proxy: ProxyEntity,
        localProxyPort: Int = 10808
    ) = withContext(Dispatchers.IO) {
        try {
            val serverKey = proxy.server.trim()
            if (serverKey.isEmpty()) return@withContext

            // Check in-memory cache first
            val flagEmoji = flagCache[serverKey] ?: run {
                val client = getProxyHttpClient(localProxyPort)

                // Querying ip-api.com through the proxy reveals the exact exit node IP & country
                val request = Request.Builder()
                    .url("http://ip-api.com/json/?fields=status,countryCode")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@run null
                    val bodyString = response.body?.string() ?: return@run null
                    val json = JSONObject(bodyString)

                    if (json.optString("status") != "success") return@run null

                    val countryCode = json.optString("countryCode")
                    val resolvedFlag = countryCodeToEmojiFlag(countryCode)
                    if (resolvedFlag.isNotEmpty()) {
                        flagCache[serverKey] = resolvedFlag
                        resolvedFlag
                    } else null
                }
            }

            if (!flagEmoji.isNullOrEmpty() && !proxy.displayName.contains(flagEmoji)) {
                proxy.displayName = "$flagEmoji ${proxy.displayName}"
            }
        } catch (_: Exception) {
            // Fail silently on timeout or network error; keep original displayName
        }
    }

    /**
     * Probes Gemini API endpoint through the proxy and appends [Gemini] tag
     * if the node successfully reaches the service.
     */
    suspend fun decorateWithGemini(
        proxy: ProxyEntity,
        localProxyPort: Int = 10808
    ) = withContext(Dispatchers.IO) {
        try {
            val serverKey = proxy.server.trim()
            if (serverKey.isEmpty()) return@withContext

            val isGeminiAvailable = geminiCache[serverKey] ?: run {
                val client = getProxyHttpClient(localProxyPort)

                // Lightweight REST API endpoint for Gemini model discovery
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/\$discovery/rest?version=v1beta")
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                    .build()

                client.newCall(request).execute().use { response ->
                    val isSuccess = response.isSuccessful || response.code == 200
                    geminiCache[serverKey] = isSuccess
                    isSuccess
                }
            }

            if (isGeminiAvailable) {
                val marker = "[Gemini]"
                if (!proxy.displayName.contains(marker)) {
                    proxy.displayName = "${proxy.displayName} $marker"
                }
            }
        } catch (_: Exception) {
            // Fail silently on rate-limit or connection drop
        }
    }

    /**
     * Converts a 2-letter ISO country code (e.g., "US", "JP") into regional indicator flag emojis.
     */
    private fun countryCodeToEmojiFlag(countryCode: String): String {
        if (countryCode.length != 2) return ""
        val uppercase = countryCode.uppercase()
        val firstChar = Character.toChars(0x1F1E6 + uppercase[0].code - 'A'.code)
        val secondChar = Character.toChars(0x1F1E6 + uppercase[1].code - 'A'.code)
        return String(firstChar) + String(secondChar)
    }
}
