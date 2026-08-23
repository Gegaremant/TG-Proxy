package org.flowseal.tgwsproxy.proxy

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MTProto proxy server that bridges Telegram traffic through WebSocket.
 */
class ProxyServer(
    private val host: String = "127.0.0.1",
    private val port: Int = 1080,
    private val secret: String,
    private val fakeTlsDomain: String,
    private val dcOpt: Map<Int, String> = mapOf(2 to "149.154.167.220", 4 to "149.154.167.220"),
    private val poolSize: Int = 4,
    private val bufKb: Int = 256,
    private val onLog: ((String) -> Unit)? = null
) {
    private val TAG = "ProxyServer"

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val wsPool = WsPool(poolSize)
    val stats: Stats get() = wsPool.stats

    private val wsBlacklist = ConcurrentHashMap.newKeySet<Pair<Int, Boolean>>()
    private val dcFailUntil = ConcurrentHashMap<Pair<Int, Boolean>, Long>()
    private val DC_FAIL_COOLDOWN = 30_000L
    private val WS_FAIL_TIMEOUT = 2000

    private val bufSize = bufKb * 1024
    
    private val secretBytes: ByteArray

    init {
        val s = secret.trim().lowercase()
        val hex = if (s.startsWith("dd") || s.startsWith("ee")) s.substring(2, 34) else s
        secretBytes = decodeHex(hex)
    }

    private fun decodeHex(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    fun start() {
        if (running.getAndSet(true)) return

        executor = Executors.newCachedThreadPool { r ->
            Thread(r).apply {
                isDaemon = true
                name = "proxy-worker"
            }
        }

        Thread({
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(host, port))
                }
                log("MTProto WS Bridge Proxy listening on $host:$port")
                if (fakeTlsDomain.isNotEmpty()) {
                    log("Fake TLS domain: $fakeTlsDomain")
                }

                wsPool.warmup(dcOpt)

                while (running.get()) {
                    try {
                        val client = serverSocket!!.accept()
                        client.tcpNoDelay = true
                        client.keepAlive = true
                        client.setSendBufferSize(bufSize)
                        client.setReceiveBufferSize(bufSize)
                        executor?.execute { handleClient(client) }
                    } catch (e: Exception) {
                        if (running.get()) {
                            Log.e(TAG, "Accept error: $e")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start error: $e")
                log("Failed to start: $e")
            }
        }, "proxy-server").start()
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        wsPool.shutdown()
        executor?.shutdownNow()
        serverSocket = null
        executor = null
        log("Proxy stopped")
    }

    val isRunning: Boolean get() = running.get()

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog?.invoke(msg)
    }

    private fun handleClient(client: Socket) {
        stats.connectionsTotal.incrementAndGet()
        val label = "${client.inetAddress.hostAddress}:${client.port}"

        try {
            val rawInput = client.getInputStream()
            val rawOutput = client.getOutputStream()

            val firstByte = ByteArray(1)
            if (rawInput.read(firstByte) == -1) {
                client.close()
                return
            }

            var input: InputStream = rawInput
            var output: OutputStream = rawOutput
            var handshake: ByteArray

            if (firstByte[0] == FakeTls.TLS_RECORD_HANDSHAKE && fakeTlsDomain.isNotEmpty()) {
                val hdrRest = readExactly(rawInput, 4)
                val recLen = ByteBuffer.wrap(hdrRest, 2, 2).short.toInt() and 0xFFFF
                val recBody = readExactly(rawInput, recLen)
                
                val clientHello = ByteArray(5 + recLen)
                clientHello[0] = firstByte[0]
                System.arraycopy(hdrRest, 0, clientHello, 1, 4)
                System.arraycopy(recBody, 0, clientHello, 5, recLen)
                
                val tlsResult = FakeTls.verifyClientHello(clientHello, secretBytes)
                if (tlsResult == null) {
                    Log.d(TAG, "[$label] Fake TLS verify failed")
                    client.close()
                    return
                }
                
                val serverHello = FakeTls.buildServerHello(secretBytes, tlsResult.clientRandom, tlsResult.sessionId)
                rawOutput.write(serverHello)
                rawOutput.flush()
                
                val fakeTlsStream = FakeTlsStream(rawInput, rawOutput)
                input = fakeTlsStream
                output = object : OutputStream() {
                    override fun write(b: Int) { throw UnsupportedOperationException() }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        val chunk = b.copyOfRange(off, off + len)
                        fakeTlsStream.write(chunk)
                    }
                    override fun flush() { rawOutput.flush() }
                }
                
                handshake = readExactly(input, 64)
            } else if (firstByte[0] == 0x05.toByte()) {
                // SOCKS5 compatibility (optional, dropping connection for strict MTProto)
                Log.d(TAG, "[$label] SOCKS5 connection rejected (MTProto only mode)")
                client.close()
                return
            } else {
                val rest = readExactly(rawInput, 63)
                handshake = ByteArray(64)
                handshake[0] = firstByte[0]
                System.arraycopy(rest, 0, handshake, 1, 63)
            }
            
            // Decrypt handshake
            val decPrekey = handshake.copyOfRange(8, 40)
            val decIv = handshake.copyOfRange(40, 56)
            val decKey = CryptoHelper.sha256(decPrekey + secretBytes)
            
            val decryptor = CryptoHelper.createAesCtr(decKey, decIv, javax.crypto.Cipher.ENCRYPT_MODE)
            val decrypted = decryptor.update(handshake)
            
            val protoTag = decrypted.copyOfRange(56, 60)
            val protoInt = ByteBuffer.wrap(protoTag).order(ByteOrder.LITTLE_ENDIAN).int
            val validProtos = listOf(0xefefefef.toInt(), 0xeeeeeeee.toInt(), 0xdddddddd.toInt())
            if (protoInt !in validProtos) {
                Log.d(TAG, "[$label] Invalid proto: 0x${protoInt.toUInt().toString(16)}")
                client.close()
                return
            }
            
            val dcIdx = ByteBuffer.wrap(decrypted, 60, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            val dc = kotlin.math.abs(dcIdx)
            val isMedia = dcIdx < 0
            
            val relayInit = CryptoHelper.generateRelayInit(protoTag, dcIdx)
            val ctx = CryptoHelper.buildCryptoCtx(handshake.copyOfRange(8, 56), secretBytes, relayInit)

            if (dc !in dcOpt) {
                Log.w(TAG, "[$label] unknown DC$dc")
                client.close()
                return
            }

            val dcKey = dc to isMedia
            val now = System.currentTimeMillis()
            val mediaTag = if (isMedia) " media" else ""

            if (dcKey in wsBlacklist) {
                Log.d(TAG, "[$label] DC$dc$mediaTag WS blacklisted")
                client.close() // No TCP fallback for now to keep it simple, or we can add it later
                return
            }

            val failUntil = dcFailUntil[dcKey] ?: 0L
            val wsTimeout = if (now < failUntil) WS_FAIL_TIMEOUT else 10000

            val domains = TelegramDC.wsDomains(dc, isMedia)
            val target = dcOpt[dc]!!
            var ws: RawWebSocket? = null
            var wsFailedRedirect = false
            var allRedirects = true

            ws = wsPool.get(dc, isMedia, target, domains)
            if (ws != null) {
                log("[$label] DC$dc$mediaTag -> pool hit via $target")
            } else {
                for (domain in domains) {
                    val url = "wss://$domain/apiws"
                    log("[$label] DC$dc$mediaTag -> $url via $target")
                    try {
                        ws = RawWebSocket.connect(target, domain, timeoutMs = wsTimeout)
                        allRedirects = false
                        break
                    } catch (e: WsHandshakeError) {
                        stats.wsErrors.incrementAndGet()
                        if (e.isRedirect) {
                            wsFailedRedirect = true
                            Log.w(TAG, "[$label] DC$dc$mediaTag got ${e.statusCode} from $domain -> ${e.location}")
                            continue
                        } else {
                            allRedirects = false
                            Log.w(TAG, "[$label] DC$dc$mediaTag WS handshake: ${e.statusLine}")
                        }
                    } catch (e: Exception) {
                        stats.wsErrors.incrementAndGet()
                        allRedirects = false
                        Log.w(TAG, "[$label] DC$dc$mediaTag WS connect failed: $e")
                    }
                }
            }

            if (ws == null) {
                if (wsFailedRedirect && allRedirects) {
                    wsBlacklist.add(dcKey)
                    Log.w(TAG, "[$label] DC$dc$mediaTag blacklisted for WS (all 302)")
                } else {
                    dcFailUntil[dcKey] = now + DC_FAIL_COOLDOWN
                    log("[$label] DC$dc$mediaTag WS cooldown for ${DC_FAIL_COOLDOWN / 1000}s")
                }
                client.close()
                return
            }

            dcFailUntil.remove(dcKey)
            stats.connectionsWs.incrementAndGet()

            var splitter: MsgSplitter? = null
            try {
                splitter = MsgSplitter(relayInit, protoInt)
                Log.d(TAG, "[$label] MsgSplitter activated for proto 0x${protoInt.toUInt().toString(16)}")
            } catch (_: Exception) {}

            ws.send(relayInit)
            bridgeWsReencrypt(input, output, ws, label, client, dc, isMedia, splitter, ctx)

        } catch (e: Exception) {
            Log.d(TAG, "[$label] error: $e")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun bridgeWsReencrypt(
        clientIn: InputStream, clientOut: OutputStream,
        ws: RawWebSocket, label: String, clientSocket: Socket,
        dc: Int, isMedia: Boolean,
        splitter: MsgSplitter?, ctx: CryptoCtx
    ) {
        val dcTag = "DC$dc${if (isMedia) "m" else ""}"
        val startTime = System.currentTimeMillis()
        var upBytes = 0L
        var downBytes = 0L

        val t1 = Thread({
            try {
                val buf = ByteArray(65536)
                while (true) {
                    val n = clientIn.read(buf)
                    if (n == -1) {
                        val flushed = splitter?.flush()
                        if (flushed != null && flushed.isNotEmpty()) {
                            if (flushed.size > 1) ws.sendBatch(flushed) else ws.send(flushed[0])
                        }
                        break
                    }
                    val chunk = buf.copyOfRange(0, n)
                    stats.bytesUp.addAndGet(n.toLong())
                    upBytes += n

                    val plain = ctx.cltDec.update(chunk)
                    val enc = ctx.tgEnc.update(plain)

                    if (splitter != null) {
                        val parts = splitter.split(enc)
                        if (parts.isEmpty()) continue
                        if (parts.size > 1) ws.sendBatch(parts) else ws.send(parts[0])
                    } else {
                        ws.send(enc)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { ws.close() } catch (_: Exception) {}
                try { clientSocket.close() } catch (_: Exception) {}
            }
        }, "ws-c2s-$label")

        val t2 = Thread({
            try {
                while (true) {
                    val data = ws.recv() ?: break
                    stats.bytesDown.addAndGet(data.size.toLong())
                    downBytes += data.size
                    
                    val plain = ctx.tgDec.update(data)
                    val enc = ctx.cltEnc.update(plain)

                    clientOut.write(enc)
                    clientOut.flush()
                }
            } catch (_: Exception) {
            } finally {
                try { clientSocket.close() } catch (_: Exception) {}
                try { ws.close() } catch (_: Exception) {}
            }
        }, "ws-s2c-$label")

        t1.start()
        t2.start()
        t1.join()
        t2.join()

        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        log("[$label] $dcTag WS closed: ^${Stats.humanBytes(upBytes)} v${Stats.humanBytes(downBytes)} in %.1fs".format(elapsed))
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(buf, offset, n - offset)
            if (read == -1) throw java.io.EOFException("Connection closed reading $n bytes at offset $offset")
            offset += read
        }
        return buf
    }
}
