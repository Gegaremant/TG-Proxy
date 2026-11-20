package org.flowseal.tgwsproxy.ui

import java.net.HttpURLConnection
import java.net.URL

/**
 * Availability probe used on startup.
 *
 * The user-facing target is https://t.me/YaProFun — a real Telegram channel.
 * We only need to know whether HTTPS traffic to Telegram's web front end works,
 * so a normal GET with a short deadline is enough. Redirects are followed
 * (t.me often redirects to the logged-out/preview page).
 */
object ChannelChecker {
    private const val CHANNEL_URL = "https://t.me/YaProFun"
    private const val USER_AGENT = "TgProxy/1.2.11 (Android)"

    fun isAvailable(timeoutMs: Int = 8000): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(CHANNEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            val code = connection.responseCode
            // Consume at least one byte so the response is really delivered,
            // not only the status line.
            connection.inputStream.use { it.read() }
            code in 200..399
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}
