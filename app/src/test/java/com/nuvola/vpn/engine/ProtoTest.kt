package com.nuvola.vpn.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoTest {

    @Test
    fun connKeyPadsToTwelveDigits() {
        assertEquals("c/abcd1234/000000000000", Proto.connKey("c", "abcd1234", 0))
        assertEquals("s/abcd1234/000000000042", Proto.connKey("s", "abcd1234", 42))
        assertEquals("c/deadbeef/999999999999", Proto.connKey("c", "deadbeef", 999_999_999_999L))
    }

    @Test
    fun connIdOfExtractsMiddlePart() {
        assertEquals("abcd1234", Proto.connIdOf("eded311cd9ad19d0/c/abcd1234/000000000001"))
        assertEquals("deadbeef", Proto.connIdOf("eded311cd9ad19d0/s/deadbeef/000000000042"))
        assertEquals("", Proto.connIdOf("nada"))
        assertEquals("", Proto.connIdOf("a/b/xyz/1"))
    }

    @Test
    fun packOpenMatchesPythonFormat() {
        val body = Proto.packOpen("abcd1234", "example.com", 443)
        val expected = """{"id":"abcd1234","host":"example.com","port":443}"""
        assertArrayEquals(expected.toByteArray(), body)
    }

    @Test
    fun packFinEmptyErrMatchesPython() {
        val body = Proto.packFin("abcd1234")
        val expected = """{"id":"abcd1234","err":""}"""
        assertArrayEquals(expected.toByteArray(), body)
    }

    @Test
    fun packFinWithErrEscapesQuotes() {
        val body = Proto.packFin("abcd1234", "fallo: \"boom\"")
        val expected = """{"id":"abcd1234","err":"fallo: \"boom\""}"""
        assertArrayEquals(expected.toByteArray(), body)
    }

    @Test
    fun unpackOpenRoundtrip() {
        val body = Proto.packOpen("abcd1234", "example.com", 8080)
        val m = Proto.unpackJson(body)
        assertEquals("abcd1234", m["id"])
        assertEquals("example.com", m["host"])
        assertEquals("8080", m["port"])
    }

    @Test
    fun unpackFinRoundtrip() {
        val body = Proto.packFin("abcd1234", "timeout")
        val m = Proto.unpackJson(body)
        assertEquals("abcd1234", m["id"])
        assertEquals("timeout", m["err"])
    }

    @Test
    fun unpackHandlesEscapes() {
        val body = Proto.packOpen("abcd1234", "a.b\"c\\d\n", 80)
        val m = Proto.unpackJson(body)
        assertEquals("a.b\"c\\d\n", m["host"])
    }

    @Test
    fun unpackRejectsGarbage() {
        assertTrue(Proto.unpackJson("not json".toByteArray()).isEmpty())
        assertTrue(Proto.unpackJson("{}".toByteArray()).isEmpty())
        assertTrue(Proto.unpackJson("".toByteArray()).isEmpty())
    }
}
