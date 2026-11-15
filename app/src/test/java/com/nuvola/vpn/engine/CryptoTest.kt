package com.nuvola.vpn.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CryptoTest {

    private val psk = "YyHO6lYLUpfUDaTClxYe2cTmEKv1Cd3vX-B1oFFf8wU"
    private val chan = "eded311cd9ad19d0"
    private val crypto = Crypto(psk, chan)

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    @Test
    fun hkdfMatchesRfc5869() {
        // Test vector 1 de RFC 5869 (SHA-256)
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val okm = Crypto.hkdfSha256(ikm, salt, info, 42)
        val expected = "3cb25f25faacd57a90434f64d0362f2a" +
                       "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                       "34007208d5b887185865"
        assertEquals(expected, okm.toHex())
    }

    @Test
    fun sealThenUnsealRoundtrip() {
        val path = "$chan/c/abcd1234/000000000001"
        val body = "hola mundo".toByteArray()
        val blob = crypto.seal(1L, path, mtype = 2, body = body)
        assertArrayEquals(Crypto.MAGIC, blob.copyOfRange(0, 3))
        assertEquals(Crypto.MIN_BLOB + body.size, blob.size)
        val (mtype, pt) = crypto.unseal(blob, path)
        assertEquals(2, mtype)
        assertArrayEquals(body, pt)
    }

    @Test
    fun unsealFailsWithWrongAad() {
        val path = "$chan/c/abcd1234/000000000001"
        val wrongPath = "$chan/c/abcd1234/000000000002"
        val blob = crypto.seal(1L, path, 2, "x".toByteArray())
        assertThrows(CryptoError::class.java) {
            crypto.unseal(blob, wrongPath)
        }
    }

    @Test
    fun unsealFailsWithWrongKey() {
        val path = "$chan/c/abcd1234/000000000001"
        val blob = crypto.seal(1L, path, 2, "x".toByteArray())
        val other = Crypto("OtraPSKdistinta1234567890abcdef", chan)
        assertThrows(CryptoError::class.java) {
            other.unseal(blob, path)
        }
    }

    @Test
    fun differentSequencesProduceDifferentBlobs() {
        val path = "$chan/c/abcd1234/000000000001"
        val body = "mismo payload".toByteArray()
        val a = crypto.seal(1L, path, 2, body)
        val b = crypto.seal(2L, path, 2, body)
        assertNotEquals(a.toHex(), b.toHex())
    }

    @Test
    fun unsealRejectsShortBlob() {
        assertThrows(CryptoError::class.java) {
            crypto.unseal(ByteArray(10), "path")
        }
    }

    @Test
    fun unsealRejectsBadMagic() {
        val blob = crypto.seal(1L, "path", 2, "x".toByteArray())
        blob[0] = 'X'.code.toByte()
        assertThrows(CryptoError::class.java) {
            crypto.unseal(blob, "path")
        }
    }
}
