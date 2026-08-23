package org.flowseal.tgwsproxy.proxy

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

    private val SERVER_HELLO_TEMPLATE = byteArrayOf(
        0x16, 0x03, 0x03, 0x00, 0x7a,
        0x02, 0x00, 0x00, 0x76,
        0x03, 0x03
    ) + ByteArray(32) { 0 } + byteArrayOf(
        0x20
    ) + ByteArray(32) { 0 } + byteArrayOf(
        0x13, 0x01, 0x00,
        0x00, 0x2e,
        0x00, 0x33, 0x00, 0x24, 0x00, 0x1d, 0x00, 0x20
    ) + ByteArray(32) { 0 } + byteArrayOf(
        0x00, 0x2b, 0x00, 0x02, 0x03, 0x04
    )

    private val CCS_FRAME = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)

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

    fun buildServerHello(secret: ByteArray, clientRandom: ByteArray, sessionId: ByteArray): ByteArray {
        val sh = SERVER_HELLO_TEMPLATE.copyOf()
        System.arraycopy(sessionId, 0, sh, 44, 32)
        System.arraycopy(Random.nextBytes(32), 0, sh, 89, 32)

        val encryptedSize = Random.nextInt(1900, 2100)
        val encryptedData = Random.nextBytes(encryptedSize)
        val appRecord = ByteBuffer.allocate(5 + encryptedSize)
        appRecord.put(0x17)
        appRecord.put(0x03)
        appRecord.put(0x03)
        appRecord.putShort(encryptedSize.toShort())
        appRecord.put(encryptedData)

        val response = ByteBuffer.allocate(sh.size + CCS_FRAME.size + appRecord.capacity())
        response.put(sh)
        response.put(CCS_FRAME)
        response.put(appRecord.array())

        val hmacInput = ByteArray(clientRandom.size + response.capacity())
        System.arraycopy(clientRandom, 0, hmacInput, 0, clientRandom.size)
        System.arraycopy(response.array(), 0, hmacInput, clientRandom.size, response.capacity())
        
        val serverRandom = hmacSha256(secret, hmacInput)
        
        val finalRes = response.array()
        System.arraycopy(serverRandom, 0, finalRes, 11, 32)
        
        return finalRes
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

