package dev.kage.manager.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.math.BigInteger

class Ed25519GroupTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `base point encodes to the canonical Ed25519 value`() {
        val encoded = Ed25519Group.encode(Ed25519Group.BASE_POINT)
        assertArrayEquals(
            hex("5866666666666666666666666666666666666666666666666666666666666666"),
            encoded
        )
    }

    @Test
    fun `double base matches the known vector`() {
        val twoBase = Ed25519Group.scalarMultBase(BigInteger.TWO)
        assertArrayEquals(
            hex("c9a3f86aae465f0e56513864510f3997561fa2c9e85ea21dc2292309f3cd6022"),
            Ed25519Group.encode(twoBase)
        )
    }

    @Test
    fun `multiplying by the group order gives the identity`() {
        val identity = Ed25519Group.scalarMultBase(Ed25519Group.L)
        assertArrayEquals(
            hex("0100000000000000000000000000000000000000000000000000000000000000"),
            Ed25519Group.encode(identity)
        )
    }

    @Test
    fun `add and sub are inverse operations`() {
        val m = Ed25519Group.decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))
        val base = Ed25519Group.BASE_POINT
        assertNotNull(m)
        val sum = Ed25519Group.add(base, m!!)
        val back = Ed25519Group.sub(sum, m)
        assertArrayEquals(Ed25519Group.encode(base), Ed25519Group.encode(back))
    }

    @Test
    fun `spake2 generator points decode`() {
        assertNotNull(Ed25519Group.decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")))
        assertNotNull(Ed25519Group.decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")))
    }

    @Test
    fun `random points survive encode-decode roundtrip`() {
        repeat(8) {
            val p = Ed25519Group.scalarMultBase(Ed25519Group.randomScalar(java.security.SecureRandom()))
            val decoded = Ed25519Group.decode(Ed25519Group.encode(p))
            assertNotNull(decoded)
            assertArrayEquals(Ed25519Group.encode(p), Ed25519Group.encode(decoded!!))
        }
    }

    @Test
    fun `scalar arithmetic helpers mirror the C byte layout`() {
        // reduceScalar of a little-endian 64-byte buffer
        val le64 = ByteArray(64)
        le64[0] = 0x07
        assertEquals(BigInteger.valueOf(7), Ed25519Group.reduceScalar(le64))
        // scalarToLittleEndian roundtrip (reduceScalar works mod L by design)
        val v = Ed25519Group.L.add(BigInteger.valueOf(12345))
        assertEquals(
            v.mod(Ed25519Group.L),
            Ed25519Group.reduceScalar(ByteArray(64).also { System.arraycopy(Ed25519Group.scalarToLittleEndian(v), 0, it, 0, 32) })
        )
        // mulByEight
        assertEquals(BigInteger.valueOf(8 * 21), Ed25519Group.mulByEight(BigInteger.valueOf(21)))
    }
}
