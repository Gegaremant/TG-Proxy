package org.flowseal.tgwsproxy.proxy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Holds the 4 AES-CTR stream ciphers used for bridging MTProto traffic.
 */
class CryptoCtx(
    val cltDec: Cipher,
    val cltEnc: Cipher,
    val tgEnc: Cipher,
    val tgDec: Cipher
)

object CryptoHelper {

    private val ZERO_64 = ByteArray(64)
    private val RESERVED_FIRST_BYTES = byteArrayOf(
        0xef.toByte(), 0x48.toByte(), 0x47.toByte(), 0x50.toByte(), 0x16.toByte(), 0x02.toByte(),
        0x01.toByte(), 0x03.toByte(), 0xee.toByte(), 0xdd.toByte()
    )
    private val RESERVED_STARTS = listOf(
        byteArrayOf(0x00, 0x00, 0x00, 0x00), // empty
        byteArrayOf(0x50, 0x4f, 0x53, 0x54), // POST
        byteArrayOf(0x47, 0x45, 0x54, 0x20), // GET
        byteArrayOf(0x48, 0x45, 0x41, 0x44), // HEAD
        byteArrayOf(0x4f, 0x50, 0x54, 0x49)  // OPTI
    )
    private val RESERVED_CONTINUE = byteArrayOf(0x00, 0x00, 0x00, 0x00)

    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    private fun reverse(arr: ByteArray): ByteArray {
        val out = ByteArray(arr.size)
        for (i in arr.indices) {
            out[i] = arr[arr.size - 1 - i]
        }
        return out
    }

    fun createAesCtr(key: ByteArray, iv: ByteArray, mode: Int): Cipher {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher
    }

    fun buildCryptoCtx(clientDecPrekeyIv: ByteArray, secret: ByteArray, relayInit: ByteArray): CryptoCtx {
        // clt_dec
        val cltDecPrekey = clientDecPrekeyIv.copyOfRange(0, 32)
        val cltDecIv = clientDecPrekeyIv.copyOfRange(32, 48)
        val cltDecKey = sha256(cltDecPrekey + secret)
        
        // clt_enc
        val cltEncPrekeyIv = reverse(clientDecPrekeyIv)
        val cltEncKey = sha256(cltEncPrekeyIv.copyOfRange(0, 32) + secret)
        val cltEncIv = cltEncPrekeyIv.copyOfRange(32, 48)

        val cltDec = createAesCtr(cltDecKey, cltDecIv, Cipher.ENCRYPT_MODE)
        val cltEnc = createAesCtr(cltEncKey, cltEncIv, Cipher.ENCRYPT_MODE)

        // Fast forward client decryptor past the 64-byte init
        cltDec.update(ZERO_64)

        // Relay side
        val relayEncKey = relayInit.copyOfRange(8, 40)
        val relayEncIv = relayInit.copyOfRange(40, 56)

        val relayDecPrekeyIv = reverse(relayInit.copyOfRange(8, 56))
        val relayDecKey = relayDecPrekeyIv.copyOfRange(0, 32)
        val relayDecIv = relayDecPrekeyIv.copyOfRange(32, 48)

        val tgEnc = createAesCtr(relayEncKey, relayEncIv, Cipher.ENCRYPT_MODE)
        val tgDec = createAesCtr(relayDecKey, relayDecIv, Cipher.ENCRYPT_MODE)

        tgEnc.update(ZERO_64)

        return CryptoCtx(cltDec, cltEnc, tgEnc, tgDec)
    }

    fun generateRelayInit(protoTag: ByteArray, dcIdx: Int): ByteArray {
        var rnd: ByteArray
        while (true) {
            rnd = Random.nextBytes(64)
            if (rnd[0] in RESERVED_FIRST_BYTES) continue
            val start4 = rnd.copyOfRange(0, 4)
            if (RESERVED_STARTS.any { it.contentEquals(start4) }) continue
            val start4_8 = rnd.copyOfRange(4, 8)
            if (start4_8.contentEquals(RESERVED_CONTINUE)) continue
            break
        }

        val encKey = rnd.copyOfRange(8, 40)
        val encIv = rnd.copyOfRange(40, 56)
        val encryptor = createAesCtr(encKey, encIv, Cipher.ENCRYPT_MODE)

        val dcBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(dcIdx.toShort()).array()
        val tailPlain = protoTag + dcBytes + Random.nextBytes(2)

        val encryptedFull = encryptor.update(rnd)
        val keystreamTail = ByteArray(8)
        for (i in 0 until 8) {
            keystreamTail[i] = (encryptedFull[56 + i].toInt() xor rnd[56 + i].toInt()).toByte()
        }
        val encryptedTail = ByteArray(8)
        for (i in 0 until 8) {
            encryptedTail[i] = (tailPlain[i].toInt() xor keystreamTail[i].toInt()).toByte()
        }

        val result = rnd.copyOf()
        System.arraycopy(encryptedTail, 0, result, 56, 8)
        return result
    }
}

