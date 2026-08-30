package org.flowseal.tgwsproxy.proxy

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Cloudflare-fronted proxy domain pool, mirroring the desktop build.
 *
 * The desktop client refreshes its CF proxy domain list from
 *   https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt
 * every hour. Each line is an obfuscated ".com" name decoded into a real
 * ".co.uk" domain via [decodeName]. See CFPROXY_DOMAINS_URL / _dd in proxy/config.py.
 */
object CfDomains {

    private const val TAG = "CfDomains"

    private const val GITHUB_URL =
        "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"
    private const val GITHUB_FALLBACK_IP = "185.199.109.133"
    private const val MIN_VALID_DOMAINS = 3
    private const val REFRESH_INTERVAL_MS = 3600_000L
    private const val TIMEOUT_MS = 10_000

    // Obfuscated names, same as _CFPROXY_ENC in proxy/config.py.
    private val BUILTIN_ENCODED: List<String> = listOf(
        "virkgj.com", "vmmzovy.com", "mkuosckvso.com", "zaewayzmplad.com",
        "twdmbzcm.com", "awzwsldi.com", "clngqrflngqin.com", "tjacxbqtj.com",
        "bxaxtxmrw.com", "dmohrsgmohcrwb.com", "vwbmtmoi.com", "khgrre.com",
        "ulihssf.com", "tmhqsdqmfpmk.com", "xwuwoqbm.com", "orgcnunpj.com",
        "zhkuldz.com", "zypoljnslxa.com", "efabnxaowuzs.com", "zaftuzsftqdq.com",
    )

    private val started = AtomicBoolean(false)

    @Volatile
    var domains: List<String> = BUILTIN_ENCODED.map { decodeName(it) }
        private set

    /**
     * Decode an obfuscated CF proxy domain name (port of proxy/config.py _dd).
     * "kartoshka.com" -> "kartoshka.co.uk" (letters shifted back by name length).
     */
    fun decodeName(encoded: String): String {
        if (!encoded.endsWith(".com")) return encoded
        val base = encoded.dropLast(4)
        val shift = base.count { it.isLetter() }
        val sb = StringBuilder()
        for (ch in base) {
            if (ch.isLetter()) {
                val a = if (ch > '`') 'a' else 'A'
                val idx = ((ch.code - a.code - shift) % 26 + 26) % 26
                sb.append((a.code + idx).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.append(".co.uk").toString()
    }

    /** Port of proxy/config.py _is_valid_domain. */
    private fun isValidDomain(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253) return false
        if (domain.startsWith(".") || domain.endsWith(".")) return false
        val labels = domain.split('.')
        if (labels.size < 2) return false
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (label.first() == '-' || label.last() == '-') return false
            if (!label.all { it.isLetterOrDigit() || it == '-' }) return false
        }
        val tld = labels.last()
        return tld.length >= 2 && tld.any { it.isLetter() }
    }

    /**
     * Kick off the periodic GitHub refresh in a background thread.
     * No-op if already running.
     */
    fun startAutoRefresh() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            refresh()
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(REFRESH_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                refresh()
            }
        }, "cfproxy-domains-refresh").start()
    }

    /**
     * Fetch and apply the latest GitHub domain list. Returns the number of
     * applied domains, 0 if the payload was too small, or -1 on failure.
     */
    fun refresh(): Int {
        val raw = fetchViaHttps() ?: fetchViaPinnedIp() ?: run {
            Log.w(TAG, "CF proxy domain refresh failed or empty response; keeping current pool")
            return -1
        }
        val decoded = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { decodeName(it) }
            .filter { isValidDomain(it) }
            .distinct()
            .toList()
        if (decoded.size >= MIN_VALID_DOMAINS) {
            domains = decoded
            Log.i(TAG, "CF proxy domain pool updated from GitHub (${decoded.size} domains)")
            return decoded.size
        }
        Log.w(TAG, "Ignoring fetched CF proxy domains due to low-quality payload (valid=${decoded.size}); keeping current pool")
        return 0
    }

    private fun fetchViaHttps(): String? {
        return try {
            val url = URL("$GITHUB_URL?" + randomToken())
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "tg-ws-proxy")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "GitHub HTTP ${conn.responseCode}")
                    return null
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "GitHub fetch failed: $e")
            null
        }
    }

    /**
     * Fallback: connect to GitHub's pinned CDN IP with SNI/Host overridden
     * (mirrors build_github_opener in proxy/utils.py).
     */
    private fun fetchViaPinnedIp(): String? {
        return try {
            val address = InetAddress.getByName(GITHUB_FALLBACK_IP)
            val rawSocket = Socket()
            rawSocket.soTimeout = TIMEOUT_MS
            try {
                rawSocket.connect(InetSocketAddress(address, 443), TIMEOUT_MS)
                val socket = SSLContext.getDefault().socketFactory
                    .createSocket(rawSocket, "raw.githubusercontent.com", 443, true) as SSLSocket
                socket.soTimeout = TIMEOUT_MS
                val params = socket.sslParameters
                params.serverNames = listOf(SNIHostName("raw.githubusercontent.com"))
                socket.sslParameters = params
                socket.startHandshake()

                val request = "GET /.github/cfproxy-domains.txt?${randomToken()} HTTP/1.1\r\n" +
                    "Host: raw.githubusercontent.com\r\n" +
                    "User-Agent: tg-ws-proxy\r\n" +
                    "Accept: */*\r\n" +
                    "Connection: close\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray())
                socket.getOutputStream().flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val text = reader.readText()
                val body = text.substringAfter("\r\n\r\n")
                if (!text.startsWith("HTTP/1.1 200")) {
                    Log.w(TAG, "GitHub pinned HTTP status: ${text.substringBefore("\r\n")}")
                    return null
                }
                socket.close()
                body
            } finally {
                rawSocket.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "GitHub pinned fetch failed: $e")
            null
        }
    }

    private val tokenChars = "abcdefghijklmnopqrstuvwxyz"

    private fun randomToken(): String {
        val sb = StringBuilder(7)
        repeat(7) { sb.append(tokenChars.random()) }
        return sb.toString()
    }
}