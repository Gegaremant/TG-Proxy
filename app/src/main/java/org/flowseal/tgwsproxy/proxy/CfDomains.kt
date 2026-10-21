package org.flowseal.tgwsproxy.proxy

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

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
    private const val PROBE_PARALLELISM = 6

    // How long the endpoint must keep the MTProto stream alive to count as
    // usable (a working endpoint never drops the connection right away).
    private const val PROBE_HOLD_MS = 2000

    // Protocol tag of the abridged MTProto transport.
    private val ABRIDGED_PROTO_TAG = byteArrayOf(0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte())

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
     * Domains verified by the last availability check. When set, the hourly
     * GitHub refresh keeps only these — a domain that does not answer must
     * not come back into the rotation just because the list was refetched.
     */
    @Volatile
    private var verified: Set<String> = emptySet()

    /**
     * The domain that last worked for a given DC. Kept so every new client
     * connection starts with an endpoint that is known to be alive instead of
     * a random (possibly dead) one.
     */
    private val pinned = ConcurrentHashMap<Int, String>()

    /** Domains ordered for a DC: the pinned one first, then the rest. */
    fun orderedDomains(dc: Int): List<String> {
        val pool = healthyDomains()
        val current = pinned[dc]
        if (current == null || current !in pool) return pool
        return listOf(current) + pool.filter { it != current }
    }

    /** Remember the domain that worked for [dc]. */
    fun pin(dc: Int, domain: String) {
        if (domain.isNotBlank()) pinned[dc] = domain
    }

    /** Forget the pinned domain for [dc] (it just failed). */
    fun unpin(dc: Int, domain: String) {
        if (pinned[dc] == domain) pinned.remove(dc)
    }

    /**
     * Check whether a domain really carries Telegram traffic.
     *
     * A plain WebSocket handshake is not enough: half of the public domains
     * accept the upgrade and then drop the MTProto stream right after the
     * init packet, which looks exactly like "the proxy is not working".
     * So we send a proper relay-init and watch the connection: if the
     * endpoint sends data or keeps the stream open, it is usable.
     */
    suspend fun probeDomain(domain: String, dc: Int = 2, timeoutMs: Int = 4000): Boolean =
        withContext(Dispatchers.IO) {
            val host = "kws$dc.$domain"
            var ws: RawWebSocket? = null
            try {
                ws = RawWebSocket.connect(host, host, timeoutMs = timeoutMs)
                // Same init the proxy sends to the endpoint.
                ws.send(CryptoHelper.generateRelayInit(ABRIDGED_PROTO_TAG, dc))

                // Watch the stream: data or a still-open connection means the
                // endpoint works, an immediate close means it drops us.
                val gotData = java.util.concurrent.atomic.AtomicBoolean(false)
                val closed = java.util.concurrent.atomic.AtomicBoolean(false)
                val watcher = Thread({
                    try {
                        val data = ws?.recv()
                        if (data != null) gotData.set(true) else closed.set(true)
                    } catch (_: Exception) {
                        // EOF / read error: the endpoint hung up on us.
                        closed.set(true)
                    }
                }, "cf-probe-$host")
                watcher.isDaemon = true
                watcher.start()
                watcher.join(PROBE_HOLD_MS.toLong())

                when {
                    gotData.get() -> true
                    closed.get() -> {
                        Log.i(TAG, "probe $host: endpoint dropped the MTProto stream")
                        false
                    }
                    else -> true // still open, no data yet - the endpoint is alive
                }
            } catch (e: Exception) {
                Log.i(TAG, "probe $host failed: ${e.javaClass.simpleName}: ${e.message}")
                false
            } finally {
                try { ws?.close() } catch (_: Exception) {}
            }
        }

    /**
     * Probe the whole pool and keep only the domains that answer.
     * Returns the working domains; an empty result means "keep everything"
     * (a failed check must not black out the pool).
     */
    suspend fun filterWorkingDomains(
        dc: Int = 2,
        onProgress: (checked: Int, total: Int, domain: String, ok: Boolean) -> Unit =
            { _, _, _, _ -> }
    ): List<String> = withContext(Dispatchers.IO) {
        val pool = domains
        if (pool.isEmpty()) return@withContext emptyList()
        val working = Collections.synchronizedList(mutableListOf<String>())
        val gate = Semaphore(PROBE_PARALLELISM)
        coroutineScope {
            pool.mapIndexed { index, domain ->
                async {
                    gate.withPermit {
                        val ok = probeDomain(domain, dc)
                        if (ok) working.add(domain)
                        onProgress(index + 1, pool.size, domain, ok)
                    }
                }
            }.awaitAll()
        }
        working.toList()
    }

    /**
     * Keep only the domains that answered the availability check.
     * The hourly refresh keeps this set, so a dead domain stays out.
     */
    fun applyWorkingDomains(working: List<String>) {
        if (working.isEmpty()) {
            Log.w(TAG, "Availability check found no working domain, keeping the current pool")
            return
        }
        verified = working.toSet()
        domains = working
        health.keys.retainAll(verified)
        pinned.values.retainAll(verified)
        Log.i(TAG, "CF proxy pool narrowed to ${working.size} working domain(s)")
    }

    // ---- Domain health tracking -------------------------------------------
    // A domain whose connections keep dying shortly after being established is
    // temporarily deprioritized: two strikes put it on cooldown with an
    // exponential backoff (1 min -> 10 min), and cooled-down domains are tried
    // only after all healthy ones. Success resets the strike counter.

    private class Health {
        var failures = 0
        var cooldownUntil = 0L
    }

    private val health = ConcurrentHashMap<String, Health>()
    private const val FAILURE_STRIKES = 2
    private const val COOLDOWN_BASE_MS = 60_000L
    private const val COOLDOWN_MAX_MS = 600_000L

    /**
     * Domains ordered for connection attempts: healthy ones first (shuffled to
     * spread load), cooled-down ones last as a last resort — never dropped
     * entirely, so a burst of network blips cannot black out the whole pool.
     */
    fun healthyDomains(): List<String> {
        val now = System.currentTimeMillis()
        val fresh = mutableListOf<String>()
        val cooled = mutableListOf<String>()
        for (d in domains) {
            val h = health[d]
            if (h != null && now < h.cooldownUntil) cooled += d else fresh += d
        }
        fresh.shuffle()
        cooled.shuffle()
        return fresh + cooled
    }

    /** Report that a connection through [domain] failed early (or handshake failed). */
    fun reportFailure(domain: String) {
        if (domain.isBlank()) return
        val h = health.getOrPut(domain) { Health() }
        synchronized(h) {
            h.failures++
            if (h.failures >= FAILURE_STRIKES) {
                val shift = (h.failures - FAILURE_STRIKES).coerceAtMost(4)
                val cooldown = (COOLDOWN_BASE_MS shl shift).coerceAtMost(COOLDOWN_MAX_MS)
                h.cooldownUntil = System.currentTimeMillis() + cooldown
                Log.w(TAG, "CF domain $domain unhealthy (failures=${h.failures}), cooldown ${cooldown / 1000}s")
            }
        }
    }

    /** Report a healthy, long-lived connection through [domain]. */
    fun reportSuccess(domain: String) {
        if (domain.isBlank()) return
        health.remove(domain)
    }

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
        // Keep the domains verified by the availability check: a domain that
        // did not answer must not silently come back into the rotation.
        val filtered = if (verified.isNotEmpty()) {
            val kept = decoded.filter { it in verified }
            if (kept.size >= MIN_VALID_DOMAINS) kept else decoded
        } else {
            decoded
        }
        if (filtered.size >= MIN_VALID_DOMAINS) {
            domains = filtered
            health.keys.retainAll(filtered.toSet())
            pinned.values.retainAll(filtered.toSet())
            Log.i(TAG, "CF proxy domain pool updated from GitHub (${filtered.size} domains)")
            return filtered.size
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