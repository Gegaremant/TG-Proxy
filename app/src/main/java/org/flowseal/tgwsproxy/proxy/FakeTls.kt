package org.flowseal.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.random.Random

object FakeTls {
    const val TLS_RECORD_HANDSHAKE: Byte = 0x16
    const val TLS_RECORD_CCS: Byte = 0x14
    const val TLS_RECORD_APPDATA: Byte = 0x17

    private const val CLIENT_RANDOM_OFFSET = 11
    private const val CLIENT_RANDOM_LEN = 32
    private const val SESSION_ID_OFFSET = 44
    private const val SESSION_ID_LEN = 32
    private const val TIMESTAMP_TOLERANCE = 120

    private const val TLS_VERSION_12: Byte = 0x03
    private const val TLS_VERSION_13: Byte = 0x04

    private const val EXT_SUPPORTED_VERSIONS = 0x002b
    private const val EXT_KEY_SHARE = 0x0033

    private const val GROUP_X25519 = 0x001d
    private const val GROUP_X25519MLKEM768 = 0x11ec

    private const val X25519_KEY_LEN = 32
    private const val X25519MLKEM768_CLIENT_SHARE_LEN = 1216
    private const val MLKEM768_CIPHERTEXT_LEN = 1088
    private const val TLS_APPDATA_MAX = 16384

    // TLS 1.3 suites in the order a typical server picks them from the client list
    private val CIPHER_PREFERENCE = intArrayOf(0x1301, 0x1303, 0x1302)

    // Bounds for the single ApplicationData record that follows CCS; without a
    // site profile stay near the 64-byte record telemt passes DPI with, far from
    // the 1900-2100 bytes of the classic MTProxy answer.
    private const val APPDATA_MIN = 64
    private const val FALLBACK_APPDATA_MAX = 160

    private val CCS_FRAME = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)

    // Client Hello replay protection: a hello accepted once stays refused for
    // a while, so a recorded flight cannot be answered twice.
    private val replayWindowMs: Long = (2 * TIMESTAMP_TOLERANCE + 1).toLong() * 1000
    private const val REPLAY_CACHE_SIZE = 65536
    private val replayCache = HashMap<String, Long>()

    class ClientHelloInfo(
        val cipherSuites: Set<Int>,
        val keyShares: Map<Int, Int>
    )

    private fun clientRandomKey(clientRandom: ByteArray): String =
        clientRandom.joinToString("") { "%02x".format(it) }

    fun firstClientHelloSeen(clientRandom: ByteArray): Boolean {
        val now = System.currentTimeMillis()
        val key = clientRandomKey(clientRandom)
        val expiry = replayCache[key]
        if (expiry != null && expiry > now) return false
        if (replayCache.size >= REPLAY_CACHE_SIZE) {
            val oldest = replayCache.keys.toList().firstOrNull() ?: return true
            replayCache.remove(oldest)
        }
        replayCache[key] = now + replayWindowMs
        return true
    }

    fun appdataLen(): Int {
        val profiled = profiledAppdataLen
        if (profiled != null) return profiled
        val span = FALLBACK_APPDATA_MAX - APPDATA_MIN + 1
        return APPDATA_MIN + Random.nextInt(span)
    }

    fun setProfiledAppdataLen(length: Int?) {
        profiledAppdataLen = if (length == null) null else maxOf(APPDATA_MIN, minOf(length, TLS_APPDATA_MAX))
    }

    private var profiledAppdataLen: Int? = null

    class TlsVerifyResult(
        val clientRandom: ByteArray,
        val sessionId: ByteArray,
        val timestamp: Long
    )

    fun hmacSha256(secret: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun verifyClientHello(data: ByteArray, secret: ByteArray): TlsVerifyResult? {
        if (data.size < 43) return null
        if (data[0] != TLS_RECORD_HANDSHAKE) return null
        if (data[5] != 0x01.toByte()) return null

        val clientRandom = data.copyOfRange(CLIENT_RANDOM_OFFSET, CLIENT_RANDOM_OFFSET + CLIENT_RANDOM_LEN)

        val zeroed = data.copyOf()
        for (i in 0 until CLIENT_RANDOM_LEN) {
            zeroed[CLIENT_RANDOM_OFFSET + i] = 0
        }

        val expected = hmacSha256(secret, zeroed)

        for (i in 0 until 28) {
            if (expected[i] != clientRandom[i]) return null
        }

        val tsXor = ByteArray(4)
        for (i in 0 until 4) {
            tsXor[i] = (clientRandom[28 + i].toInt() xor expected[28 + i].toInt()).toByte()
        }
        val timestamp = ByteBuffer.wrap(tsXor).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        
        val now = System.currentTimeMillis() / 1000
        if (abs(now - timestamp) > TIMESTAMP_TOLERANCE) {
            return null
        }

        var sessionId = ByteArray(SESSION_ID_LEN)
        if (data.size >= SESSION_ID_OFFSET + SESSION_ID_LEN && data[43] == 0x20.toByte()) {
            sessionId = data.copyOfRange(SESSION_ID_OFFSET, SESSION_ID_OFFSET + SESSION_ID_LEN)
        }

        return TlsVerifyResult(clientRandom, sessionId, timestamp)
    }

    fun parseClientHello(data: ByteArray): ClientHelloInfo? {
        try {
            if (data.size < 43) return null
            var pos = 5 + 4 + 2 + CLIENT_RANDOM_LEN
            pos += 1 + (data[pos].toInt() and 0xFF)
            val suitesLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            val suitesEnd = pos + suitesLen
            val suites = mutableSetOf<Int>()
            var suiteOffset = pos
            while (suiteOffset + 1 < suitesEnd) {
                val suite = ((data[suiteOffset].toInt() and 0xFF) shl 8) or (data[suiteOffset + 1].toInt() and 0xFF)
                suites.add(suite)
                suiteOffset += 2
            }
            pos = suitesEnd
            pos += 1 + (data[pos].toInt() and 0xFF)
            val extTotal = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            val extEnd = minOf(pos + extTotal, data.size)
            val keyShares = HashMap<Int, Int>()
            var extOffset = pos
            while (extOffset + 4 <= extEnd) {
                val extType = ((data[extOffset].toInt() and 0xFF) shl 8) or (data[extOffset + 1].toInt() and 0xFF)
                val extLen = ((data[extOffset + 2].toInt() and 0xFF) shl 8) or (data[extOffset + 3].toInt() and 0xFF)
                extOffset += 4
                if (extType == EXT_KEY_SHARE && extOffset + 2 <= extEnd) {
                    val sharesLen = ((data[extOffset].toInt() and 0xFF) shl 8) or (data[extOffset + 1].toInt() and 0xFF)
                    val sharesEnd = minOf(extOffset + 2 + sharesLen, extEnd)
                    var shareOffset = extOffset + 2
                    while (shareOffset + 4 <= sharesEnd) {
                        val group = ((data[shareOffset].toInt() and 0xFF) shl 8) or (data[shareOffset + 1].toInt() and 0xFF)
                        val keyLen = ((data[shareOffset + 2].toInt() and 0xFF) shl 8) or (data[shareOffset + 3].toInt() and 0xFF)
                        shareOffset += 4 + keyLen
                        if (shareOffset <= sharesEnd) {
                            keyShares[group] = keyLen
                        }
                    }
                }
                extOffset += extLen
            }
            return ClientHelloInfo(suites, keyShares)
        } catch (e: Exception) {
            return null
        }
    }

    fun buildServerHello(
        secret: ByteArray,
        clientRandom: ByteArray,
        sessionId: ByteArray,
        info: ClientHelloInfo? = null
    ): ByteArray {
        val group: Int
        val keyExchange: ByteArray
        val offeredMlKem = info?.keyShares?.get(GROUP_X25519MLKEM768)
        if (offeredMlKem != null && offeredMlKem == X25519MLKEM768_CLIENT_SHARE_LEN) {
            group = GROUP_X25519MLKEM768
            keyExchange = ByteArray(MLKEM768_CIPHERTEXT_LEN + X25519_KEY_LEN)
            Random.nextBytes(keyExchange)
        } else {
            group = GROUP_X25519
            keyExchange = ByteArray(X25519_KEY_LEN)
            Random.nextBytes(keyExchange)
        }

        var cipher = CIPHER_PREFERENCE[0]
        val suites = info?.cipherSuites
        if (suites != null) {
            for (suite in CIPHER_PREFERENCE) {
                if (suites.contains(suite)) {
                    cipher = suite
                    break
                }
            }
        }

        val body = ByteArrayOutputStream()
        body.write(TLS_VERSION_12.toInt())
        body.write(TLS_VERSION_12.toInt())
        repeat(32) { body.write(0) }
        body.write(sessionId.size)
        for (b in sessionId) {
            body.write(b.toInt())
        }
        body.write((cipher ushr 8).toInt())
        body.write((cipher and 0xFF).toInt())
        body.write(0)
        val extensionsLen = 14 + keyExchange.size
        body.write((extensionsLen ushr 8).toInt())
        body.write((extensionsLen and 0xFF).toInt())
        body.write((EXT_SUPPORTED_VERSIONS ushr 8).toInt())
        body.write((EXT_SUPPORTED_VERSIONS and 0xFF).toInt())
        body.write(0)
        body.write(2)
        body.write(0x03)
        body.write(0x04)
        body.write((EXT_KEY_SHARE ushr 8).toInt())
        body.write((EXT_KEY_SHARE and 0xFF).toInt())
        body.write((4 + keyExchange.size ushr 8).toInt())
        body.write((4 + keyExchange.size and 0xFF).toInt())
        body.write((group ushr 8).toInt())
        body.write((group and 0xFF).toInt())
        body.write((keyExchange.size ushr 8).toInt())
        body.write((keyExchange.size and 0xFF).toInt())
        for (b in keyExchange) {
            body.write(b.toInt())
        }

        val bodyBytes = body.toByteArray()
        val message = ByteArray(4 + bodyBytes.size)
        message[0] = 0x02.toByte()
        message[1] = ((bodyBytes.size ushr 16) and 0xFF).toByte()
        message[2] = ((bodyBytes.size ushr 8) and 0xFF).toByte()
        message[3] = (bodyBytes.size and 0xFF).toByte()
        System.arraycopy(bodyBytes, 0, message, 4, bodyBytes.size)

        val serverHello = ByteArray(5 + message.size)
        serverHello[0] = TLS_RECORD_HANDSHAKE
        serverHello[1] = TLS_VERSION_12
        serverHello[2] = TLS_VERSION_12
        serverHello[3] = ((message.size ushr 8) and 0xFF).toByte()
        serverHello[4] = (message.size and 0xFF).toByte()
        System.arraycopy(message, 0, serverHello, 5, message.size)

        val appDataLen = appdataLen()
        val appRecord = ByteArray(5 + appDataLen)
        appRecord[0] = TLS_RECORD_APPDATA
        appRecord[1] = TLS_VERSION_12
        appRecord[2] = TLS_VERSION_12
        appRecord[3] = ((appDataLen ushr 8) and 0xFF).toByte()
        appRecord[4] = (appDataLen and 0xFF).toByte
        Random.nextBytes(appRecord, 5, appDataLen)

        val response = ByteArray(serverHello.size + CCS_FRAME.size + appRecord.size)
        var responseOffset = 0
        System.arraycopy(serverHello, 0, response, responseOffset, serverHello.size)
        responseOffset += serverHello.size
        System.arraycopy(CCS_FRAME, 0, response, responseOffset, CCS_FRAME.size)
        responseOffset += CCS_FRAME.size
        System.arraycopy(appRecord, 0, response, responseOffset, appRecord.size)

        val hmacInput = ByteArray(clientRandom.size + response.size)
        System.arraycopy(clientRandom, 0, hmacInput, 0, clientRandom.size)
        System.arraycopy(response, 0, hmacInput, clientRandom.size, response.size)
        val serverRandom = hmacSha256(secret, hmacInput)
        System.arraycopy(serverRandom, 0, response, CLIENT_RANDOM_OFFSET, 32)
        return response
    }

    fun wrapTlsRecord(data: ByteArray): ByteArray {
        val out = ByteBuffer.allocate((data.size / 16384 + 1) * 5 + data.size)
        var offset = 0
        while (offset < data.size) {
            val chunkLen = minOf(16384, data.size - offset)
            out.put(0x17)
            out.put(0x03)
            out.put(0x03)
            out.putShort(chunkLen.toShort())
            out.put(data, offset, chunkLen)
            offset += chunkLen
        }
        val res = ByteArray(out.position())
        out.flip()
        out.get(res)
        return res
    }
}

class FakeTlsStream(private val input: InputStream, private val output: OutputStream) : InputStream() {
    private val readBuf = ByteArray(65536)
    private var readPos = 0
    private var readLimit = 0
    private var readLeft = 0

    private fun readExactly(n: Int): ByteArray {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(buf, offset, n - offset)
            if (read == -1) throw java.io.EOFException()
            offset += read
        }
        return buf
    }

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        if (n == -1) return -1
        return b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (readPos < readLimit) {
            val avail = readLimit - readPos
            val toRead = minOf(len, avail)
            System.arraycopy(readBuf, readPos, b, off, toRead)
            readPos += toRead
            return toRead
        }

        readPos = 0
        readLimit = 0

        while (true) {
            if (readLeft > 0) {
                val toRead = minOf(len, readLeft)
                val read = input.read(b, off, toRead)
                if (read == -1) return -1
                readLeft -= read
                return read
            }

            try {
                val hdr = readExactly(5)
                val rtype = hdr[0]
                val recLen = ByteBuffer.wrap(hdr, 3, 2).short.toInt() and 0xFFFF

                if (rtype == FakeTls.TLS_RECORD_CCS) {
                    if (recLen > 0) readExactly(recLen)
                    continue
                }

                if (rtype != FakeTls.TLS_RECORD_APPDATA) {
                    return -1
                }

                val toReadNow = minOf(recLen, 65536)
                val chunk = ByteArray(toReadNow)
                var offset = 0
                while (offset < toReadNow) {
                    val r = input.read(chunk, offset, toReadNow - offset)
                    if (r == -1) return -1
                    offset += r
                }
                
                val remaining = recLen - toReadNow
                if (remaining > 0) readLeft = remaining

                val returnLen = minOf(len, toReadNow)
                System.arraycopy(chunk, 0, b, off, returnLen)
                if (toReadNow > len) {
                    System.arraycopy(chunk, returnLen, readBuf, 0, toReadNow - returnLen)
                    readLimit = toReadNow - returnLen
                }
                return returnLen

            } catch (e: Exception) {
                return -1
            }
        }
    }

    fun write(data: ByteArray) {
        output.write(FakeTls.wrapTlsRecord(data))
        output.flush()
    }
}

