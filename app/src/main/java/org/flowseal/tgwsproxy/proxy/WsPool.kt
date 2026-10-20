package org.flowseal.tgwsproxy.proxy

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket connection pool, keeping idle connections per DC.
 * Port of the Python _WsPool class.
 */
class WsPool(
    private val poolSize: Int = 4,
    private val maxAgeMs: Long = 120_000L,
    private val onLog: ((String) -> Unit)? = null,
    private val socks5Proxy: String? = null
) {

    private val TAG = "WsPool"

    companion object {
        // Exponential refill backoff: a blocked DC IP must not be hammered with
        // 8-second connect attempts on every client connection.
        private const val REFILL_BACKOFF_INITIAL_MS = 1_000L
        private const val REFILL_BACKOFF_MAX_MS = 60_000L
    }

    private data class PoolEntry(val ws: RawWebSocket, val createdAt: Long)

    private val idle = ConcurrentHashMap<Pair<Int, Boolean>, LinkedBlockingDeque<PoolEntry>>()
    private val refilling = ConcurrentHashMap.newKeySet<Pair<Int, Boolean>>()
    private val refillFailures = ConcurrentHashMap<Pair<Int, Boolean>, Int>()
    private val refillAfter = ConcurrentHashMap<Pair<Int, Boolean>, Long>()

    val stats = Stats()

    fun get(dc: Int, isMedia: Boolean, targetIp: String, domains: List<String>): RawWebSocket? {
        if (poolSize <= 0) return null
        val key = dc to isMedia
        val bucket = idle[key] ?: return null.also {
            stats.poolMisses.incrementAndGet()
            scheduleRefill(key, targetIp, domains)
        }

        val now = System.currentTimeMillis()
        while (true) {
            val entry = bucket.pollFirst() ?: break
            val age = now - entry.createdAt
            if (age > maxAgeMs || entry.ws.isClosed) {
                closeQuietly(entry.ws)
                continue
            }
            stats.poolHits.incrementAndGet()
            Log.d(TAG, "Pool hit DC$dc${if (isMedia) "m" else ""} age=${age}ms left=${bucket.size}")
            scheduleRefill(key, targetIp, domains)
            return entry.ws
        }

        stats.poolMisses.incrementAndGet()
        scheduleRefill(key, targetIp, domains)
        return null
    }

    private fun scheduleRefill(key: Pair<Int, Boolean>, targetIp: String, domains: List<String>) {
        if (poolSize <= 0) return
        // Respect per-key backoff after failed refill cycles.
        val after = refillAfter[key]
        if (after != null && System.currentTimeMillis() < after) return
        if (!refilling.add(key)) return
        Thread({
            try {
                refill(key, targetIp, domains)
            } finally {
                refilling.remove(key)
            }
        }, "ws-pool-refill-DC${key.first}${if (key.second) "m" else ""}").start()
    }

    private fun refill(key: Pair<Int, Boolean>, targetIp: String, domains: List<String>) {
        val bucket = idle.getOrPut(key) { LinkedBlockingDeque() }
        val needed = poolSize - bucket.size
        if (needed <= 0) return

        var connected = 0
        for (i in 0 until needed) {
            val ws = connectOne(targetIp, domains)
            if (ws != null) {
                bucket.addLast(PoolEntry(ws, System.currentTimeMillis()))
                connected++
            } else {
                // All domains failed for this attempt; further attempts in this
                // cycle would repeat the same slow timeouts. Stop early and let
                // the backoff schedule the next cycle.
                break
            }
        }

        if (connected > 0) {
            refillFailures.remove(key)
            refillAfter.remove(key)
        } else {
            val failures = (refillFailures[key] ?: 0) + 1
            refillFailures[key] = failures
            val backoff = (REFILL_BACKOFF_INITIAL_MS shl (failures - 1).coerceAtMost(6))
                .coerceAtMost(REFILL_BACKOFF_MAX_MS)
            refillAfter[key] = System.currentTimeMillis() + backoff
            Log.d(TAG, "Pool refill DC${key.first}${if (key.second) "m" else ""} failed ($failures cycle(s)), retry in ${backoff}ms")
            return
        }
        Log.d(TAG, "Pool refilled DC${key.first}${if (key.second) "m" else ""}: ${bucket.size} ready")
    }

    private fun connectOne(targetIp: String, domains: List<String>): RawWebSocket? {
        // Try EVERY candidate domain: one dead endpoint must not abort the
        // whole attempt (this was the old behaviour and it left the pool empty).
        for (domain in domains) {
            try {
                return RawWebSocket.connect(targetIp, domain, timeoutMs = 8000, socks5Proxy = socks5Proxy)
            } catch (e: WsHandshakeError) {
                // Try the next domain in any case (redirect OR error): one dead
                // endpoint must not abort the whole attempt.
                onLog?.invoke("Pool connect $domain via $targetIp -> HTTP ${e.statusCode} ${e.statusLine}")
            } catch (e: Exception) {
                onLog?.invoke("Pool connect $domain via $targetIp failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return null
    }

    fun warmup(dcOpt: Map<Int, String>) {
        for ((dc, targetIp) in dcOpt) {
            for (isMedia in listOf(false, true)) {
                val domains = TelegramDC.wsDomains(dc, isMedia)
                val key = dc to isMedia
                scheduleRefill(key, targetIp, domains)
            }
        }
        Log.i(TAG, "Pool warmup started for ${dcOpt.size} DC(s)")
    }

    fun shutdown() {
        for ((_, bucket) in idle) {
            while (true) {
                val entry = bucket.pollFirst() ?: break
                closeQuietly(entry.ws)
            }
        }
        idle.clear()
    }

    private fun closeQuietly(ws: RawWebSocket) {
        try { ws.close() } catch (_: Exception) {}
    }
}
