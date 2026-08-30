package org.flowseal.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * Resolves hostnames to IPv4 addresses independently of the system DNS.
 *
 * The carrier/network DNS may refuse to resolve (or poison) lookups for the
 * Cloudflare-fronted proxy domains (kws{dc}.{domain}.co.uk). Many networks
 * still accept direct UDP queries to public/ISP DNS servers, which is enough
 * to get the real Cloudflare IP and connect to it directly.
 *
 * Strategy:
 *  1. system DNS via InetAddress (fast path, works for normal hosts);
 *  2. direct UDP DNS (A) query over a list of servers, order matters
 *     (ISP-provided servers first, then 1.1.1.1 / 8.8.8.8 / 9.9.9.9);
 *  3. results are cached briefly to avoid re-querying per connection.
 */
object DnsResolver {

    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    /**
     * DNS servers tried in order for manual resolution. First match wins.
     * Defaults: user/ISP servers (known to work on tested networks) plus
     * common public resolvers.
     */
    val DEFAULT_SERVERS = listOf(
        "111.88.96.50", "111.88.96.51",
        "1.1.1.1", "8.8.8.8", "9.9.9.9"
    )

    @Volatile
    var servers: List<String> = DEFAULT_SERVERS

    private data class Entry(val ip: String, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * Returns an IPv4 address string for [host], or null if unresolvable.
     */
    fun resolve(host: String, timeoutMs: Int = 3000): String? {
        if (host.isBlank()) return null

        cache[host]?.let {
            if (System.currentTimeMillis() - it.at < CACHE_TTL_MS) return it.ip
            cache.remove(host)
        }

        // 1) System DNS first.
        try {
            val all = InetAddress.getAllByName(host)
            val a = all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
            val ip = a?.hostAddress
            if (ip != null && ip.isNotEmpty()) {
                cache[host] = Entry(ip, System.currentTimeMillis())
                return ip
            }
        } catch (_: Exception) {}

        // 2) Direct UDP queries.
        for (server in servers) {
            try {
                val ip = queryA(server, host, timeoutMs)
                if (ip != null) {
                    cache[host] = Entry(ip, System.currentTimeMillis())
                    return ip
                }
            } catch (_: Exception) {
                // Try next server.
            }
        }

        // 3) DNS over HTTPS (JSON) fallback. UDP 53 is often blocked/poisoned
        // on captive/blocking networks, but HTTPS works. Mirrors the reference
        // proxy's resolve_doh(). IPv4 answers are preferred.
        val doh = resolveDoh(host, timeoutMs)
        if (doh != null) {
            cache[host] = Entry(doh, System.currentTimeMillis())
            return doh
        }

        return null
    }

    private val DOH_ENDPOINTS = listOf(
        // (endpoint, pin-IP fallback: hostname of DoH server may itself be
        // unresolvable on blocking networks, so we pin known public IPs and
        // connect with SNI=hostname, like the reference proxy does for GitHub).
        "https://cloudflare-dns.com/dns-query" to "1.1.1.1",
        "https://dns.google/resolve" to "8.8.8.8",
        "https://dns.quad9.net/dns-query" to "9.9.9.9",
        "https://dns.adguard-dns.com/dns-query" to "94.140.14.14",
        "https://xbox-dns.ru/dns-query" to "111.88.96.56"
    )

    // Returns the first IPv4 answer from any DoH endpoint, or null.
    private fun resolveDoh(host: String, timeoutMs: Int): String? {
        val deadline = System.currentTimeMillis() + 3000L
        for ((endpoint, pinIp) in DOH_ENDPOINTS) {
            if (System.currentTimeMillis() > deadline) break
            try {
                val ip = queryDohJson(endpoint, pinIp, host, timeoutMs.coerceAtMost(1500))
                if (ip != null) return ip
            } catch (_: Exception) {
                // Try next endpoint.
            }
        }
        return null
    }

    private fun queryDohJson(endpoint: String, pinIp: String, host: String, timeoutMs: Int): String? {
        val sep = if (endpoint.contains('?')) '&' else '?'
        val url = URL("$endpoint${sep}name=$host&type=A")

        // 1) Try a pinned-IP raw TLS connection (works even when system DNS
        //    cannot resolve the DoH server's own hostname).
        try {
            return dohOverSocket(url, pinIp, host, timeoutMs)
        } catch (_: Exception) {
            // fall through to HttpURLConnection / system DNS
        }

        // 2) Fallback: system-DNS based HTTPS via HttpURLConnection.
        return try {
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/dns-json")
                if (conn.responseCode != 200) null else parseDohAnswer(readAll(conn.inputStream))
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            null
        }
    }

    private val dohSslContext: javax.net.ssl.SSLContext by lazy {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
        })
        javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(null, trustAll, java.security.SecureRandom())
        }
    }

    // Raw HTTPS GET to a pinned IP with SNI=hostname, returning parsed JSON body.
    private fun dohOverSocket(url: URL, pinIp: String, host: String, timeoutMs: Int): String? {
        val raw = java.net.Socket()
        try {
            raw.connect(java.net.InetSocketAddress(pinIp, 443), timeoutMs)
            raw.soTimeout = timeoutMs
            val ssl = dohSslContext.socketFactory.createSocket(
                raw, host, 443, true
            ) as javax.net.ssl.SSLSocket
            ssl.startHandshake()

            val pathAndQuery = url.path + if (url.query != null) "?${url.query}" else ""
            val req = "GET $pathAndQuery HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "Accept: application/dns-json\r\n" +
                    "Connection: close\r\n\r\n"
            ssl.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
            ssl.getOutputStream().flush()

            val buf = ByteArray(8192)
            val out = ByteArrayOutputStream()
            val input = ssl.getInputStream()
            var n: Int
            try {
                while (input.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                }
            } catch (_: Exception) {
                // stream may close after headers; partial body is fine
            }
            ssl.close()

            val full = String(out.toByteArray(), Charsets.UTF_8)
            val idx = full.indexOf("\r\n\r\n")
            if (idx < 0) return null
            val header = full.substring(0, idx).lowercase()
            if (!header.startsWith("http/1.1 200") && !header.startsWith("http/1.0 200")) return null
            val body = full.substring(idx + 4)
            return parseDohAnswer(body)
        } finally {
            try { raw.close() } catch (_: Exception) {}
        }
    }

    private fun readAll(input: InputStream): String {
        return input.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    // Parses an RFC 8484-style JSON DoH response and returns the first A record.
    private fun parseDohAnswer(body: String): String? {
        val root = org.json.JSONObject(body)
        if (root.optInt("Status", -1) != 0) return null
        val answers = root.optJSONArray("Answer") ?: return null
        for (i in 0 until answers.length()) {
            val ans = answers.getJSONObject(i)
            if (ans.optInt("type", 0) == 1) { // A record
                val data = ans.optString("data").trim()
                if (data.isNotEmpty() && data.all { it.isDigit() || it == '.' }) {
                    return data
                }
            }
        }
        return null
    }

    fun clearCache() {
        cache.clear()
    }

    // --- raw DNS wire format (RFC 1035) ---

    private fun queryA(server: String, host: String, timeoutMs: Int): String? {
        val socket = DatagramSocket()
        socket.soTimeout = timeoutMs
        try {
            val txid = ThreadLocalRandom.current().nextInt(0x10000)
            val query = buildQuery(txid, host)
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(512)
            val response = DatagramPacket(buf, buf.size)
            socket.receive(response)
            return parseA(buf, response.length, txid)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun buildQuery(txid: Int, host: String): ByteArray {
        val out = ByteArrayOutputStream()

        // Header: ID, flags=0x0100 (RD), QDCOUNT=1, others 0.
        putShort(out, txid and 0xFFFF)
        putShort(out, 0x0100)
        putShort(out, 1)
        putShort(out, 0)
        putShort(out, 0)
        putShort(out, 0)

        // QNAME
        for (label in host.split(".")) {
            if (label.isEmpty()) continue
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)

        putShort(out, 1) // QTYPE = A
        putShort(out, 1) // QCLASS = IN

        return out.toByteArray()
    }

    private fun parseA(data: ByteArray, len: Int, expectedTxId: Int): String? {
        if (len < 12) return null

        val tx = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        if (tx != expectedTxId) return null

        val qd = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val an = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)

        var off = 12
        for (i in 0 until qd) {
            off = skipName(data, off, len) ?: return null
            off += 4 // QTYPE + QCLASS
        }

        for (i in 0 until an) {
            off = skipName(data, off, len) ?: return null
            if (off + 10 > len) return null

            val type = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
            val clazz = ((data[off + 2].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)
            val rdlen = ((data[off + 8].toInt() and 0xFF) shl 8) or (data[off + 9].toInt() and 0xFF)
            off += 10

            if (type == 1 && clazz == 1 && rdlen == 4 && off + 4 <= len) {
                val ip = "${data[off].toInt() and 0xFF}.${data[off + 1].toInt() and 0xFF}." +
                        "${data[off + 2].toInt() and 0xFF}.${data[off + 3].toInt() and 0xFF}"
                return ip
            }

            off += rdlen
        }

        return null
    }

    /**
     * Skips a domain name (possibly a compression pointer), returns the offset
     * just past it, or null on malformed data.
     */
    private fun skipName(data: ByteArray, start: Int, len: Int): Int? {
        var off = start
        while (off < len) {
            val b = data[off].toInt() and 0xFF
            when {
                b == 0 -> return off + 1
                b and 0xC0 == 0xC0 -> return off + 2
                b and 0xC0 == 0 -> {
                    off += 1 + b
                    if (off > len) return null
                }
                else -> return null
            }
        }
        return null
    }

    private fun putShort(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }
}