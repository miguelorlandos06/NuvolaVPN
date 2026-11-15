package com.nuvola.vpn.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CompressTest {

    @Test
    fun smallBodyIsNotCompressed() {
        val body = ByteArray(100) { 'a'.code.toByte() }
        val c = Compress.compress(body)
        assertEquals(0x00, c[0].toInt() and 0xFF)
        assertArrayEquals(body, c.copyOfRange(1, c.size))
    }

    @Test
    fun largeRepetitiveBodyIsCompressed() {
        val body = ByteArray(4096) { 'a'.code.toByte() }
        val c = Compress.compress(body)
        assertEquals(0x01, c[0].toInt() and 0xFF)
        assertTrue("zlib debe reducir al menos 4x", c.size < body.size / 4)
        assertArrayEquals(body, Compress.decompress(c))
    }

    @Test
    fun incompressibleBodyFallsBackToRaw() {
        val rnd = Random(42)
        val body = ByteArray(2048) { rnd.nextInt(256).toByte() }
        val c = Compress.compress(body)
        assertEquals(0x00, c[0].toInt() and 0xFF)
        assertArrayEquals(body, Compress.decompress(c))
    }

    @Test
    fun emptyBodyRoundtrips() {
        val c = Compress.compress(ByteArray(0))
        assertEquals(0x00, c[0].toInt() and 0xFF)
        assertArrayEquals(ByteArray(0), Compress.decompress(c))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zstdFlagIsRejected() {
        Compress.decompress(byteArrayOf(0x02, 1, 2, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownFlagIsRejected() {
        Compress.decompress(byteArrayOf(0x7F, 1, 2, 3))
    }

    @Test
    fun decompressMatchesServerJsExpectation() {
        val original = "hola mundo ".repeat(64).toByteArray()
        val compressed = Compress.compress(original)
        assertEquals(0x01, compressed[0].toInt() and 0xFF)
        assertArrayEquals(original, Compress.decompress(compressed))
    }
}
