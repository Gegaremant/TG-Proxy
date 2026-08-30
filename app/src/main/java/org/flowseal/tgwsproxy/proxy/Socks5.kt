package org.flowseal.tgwsproxy.proxy

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal SOCKS5 (RFC 1928) client used when an upstream proxy is configured
 * (e.g. Hiddify's local SOCKS port 127.0.0.1:2334). Only the no-auth method
 * is supported, which is what local sing-box/outbound listeners expose.
 */
object Socks5 {

    /**
     * Open a TCP connection to [targetHost]:[targetPort] through the SOCKS5
     * proxy at [proxyHost]:[proxyPort]. Returns the connected socket, or null
     * if the handshake failed. Hostnames are passed to the proxy as-is
     * (ATYP=3) so the proxy's own DNS is used.
     */
    fun connect(
        proxyHost: String,
        proxyPort: Int,
        targetHost: String,
        targetPort: Int,
        timeoutMs: Int
    ): Socket? {
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)
            sock.soTimeout = timeoutMs
            sock.tcpNoDelay = true
            val input = sock.getInputStream()
            val output = sock.getOutputStream()

            // Greeting: VER=5, NMETHODS=1, METHOD=0 (no auth)
            output.write(byteArrayOf(5, 1, 0))
            output.flush()
            val greeting = ByteArray(2)
            readFully(input, greeting)
            if (greeting[0].toInt() != 5 || greeting[1].toInt() != 0) {
                sock.close()
                return null
            }

            // CONNECT request
            val hostBytes: ByteArray
            val atyp: Int
            when {
                isIpLiteralV4(targetHost) -> {
                    atyp = 1
                    hostBytes = parseIpV4(targetHost)
                }
                targetHost.contains(':') -> {
                    atyp = 4
                    hostBytes = InetAddress.getByName(targetHost).address
                }
                else -> {
                    val name = targetHost.toByteArray(Charsets.US_ASCII)
                    atyp = 3
                    hostBytes = byteArrayOf(name.size.toByte()) + name
                }
            }

            val request = ByteArray(4 + hostBytes.size + 2)
            request[0] = 5
            request[1] = 1 // CONNECT
            request[2] = 0 // RSV
            request[3] = atyp.toByte()
            System.arraycopy(hostBytes, 0, request, 4, hostBytes.size)
            request[request.size - 2] = ((targetPort shr 8) and 0xFF).toByte()
            request[request.size - 1] = (targetPort and 0xFF).toByte()
            output.write(request)
            output.flush()

            // Reply: VER, REP, RSV, ATYP, BND.ADDR, BND.PORT
            val head = ByteArray(4)
            readFully(input, head)
            if (head[0].toInt() != 5 || head[1].toInt() != 0) {
                sock.close()
                return null
            }
            val boundAddrLen = when (head[3].toInt()) {
                1 -> 4
                4 -> 16
                3 -> {
                    val l = input.read()
                    if (l < 0) throw java.io.EOFException("SOCKS5 reply truncated")
                    l
                }
                else -> 0
            }
            readFully(input, ByteArray(boundAddrLen))
            readFully(input, ByteArray(2)) // BND.PORT
            return sock
        } catch (e: Exception) {
            try { sock.close() } catch (_: Exception) {}
            return null
        }
    }

    /** Parse a "host:port" proxy string. Returns null if malformed. */
    fun parseHostPort(spec: String): Pair<String, Int>? {
        val cleaned = spec.trim().removePrefix("socks5://").removePrefix("socks://")
        val idx = cleaned.lastIndexOf(':')
        if (idx <= 0 || idx == cleaned.length - 1) return null
        val host = cleaned.substring(0, idx).trim()
        val port = cleaned.substring(idx + 1).trim().toIntOrNull() ?: return null
        if (host.isEmpty()) return null
        return host to port
    }

    private fun isIpLiteralV4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } &&
                (p.toIntOrNull()?.let { it in 0..255 } ?: false)
        }
    }

    private fun parseIpV4(s: String): ByteArray = s.split('.').map { it.toInt().toByte() }.toByteArray()

    private fun readFully(input: java.io.InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) throw java.io.EOFException("SOCKS5 stream closed")
            offset += n
        }
    }
}