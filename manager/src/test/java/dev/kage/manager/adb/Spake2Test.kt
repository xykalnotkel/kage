package dev.kage.manager.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.security.SecureRandom

/**
 * SPAKE2 validation:
 *  - fixed vectors produced by scripts/spake2_reference.py, an INDEPENDENT python port of the
 *    same BoringSSL source - this is the cross-language check;
 *  - behavioural checks (agreement, wrong password, corrupted message).
 */
class Spake2Test {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun loadVectors(): List<JSONObject> {
        val stream = javaClass.classLoader!!.getResourceAsStream("spake2_vectors.json")
            ?: throw AssertionError("spake2_vectors.json missing from test resources")
        return JSONObject(stream.readBytes().decodeToString()).getJSONArray("vectors")
            .let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
    }

    @Test
    fun `matches the independent python reference vectors`() {
        for (v in loadVectors()) {
            val password = v.getString("password").toByteArray(Charsets.US_ASCII)
            val seedA = hex(v.getString("seedA"))
            val seedB = hex(v.getString("seedB"))

            val alice = Spake2.newClient(privateSeed = seedA)
            val bob = Spake2.newServer(privateSeed = seedB)

            val msgA = alice.generateMsg(password)
            val msgB = bob.generateMsg(password)
            assertEquals("msgA mismatch (password=${v.getString("password")})", v.getString("msgA"), toHex(msgA))
            assertEquals("msgB mismatch (password=${v.getString("password")})", v.getString("msgB"), toHex(msgB))

            val keyA = alice.processMsg(msgB)
            val keyB = bob.processMsg(msgA)
            assertEquals("keyA mismatch", v.getString("keyA"), toHex(keyA))
            assertEquals("keyB mismatch", v.getString("keyB"), toHex(keyB))
            assertEquals(64, keyA.size)
        }
    }

    @Test
    fun `both roles derive the same key material`() {
        repeat(8) {
            val password = "012345".toByteArray()
            val alice = Spake2.newClient()
            val bob = Spake2.newServer()
            val msgA = alice.generateMsg(password)
            val msgB = bob.generateMsg(password)
            assertArrayEquals(alice.processMsg(msgB), bob.processMsg(msgA))
        }
    }

    @Test
    fun `wrong password yields different key material`() {
        val alice = Spake2.newClient()
        val bob = Spake2.newServer()
        val msgA = alice.generateMsg("111111".toByteArray())
        val msgB = bob.generateMsg("222222".toByteArray())
        val keyA = alice.processMsg(msgB)
        val keyB = bob.processMsg(msgA)
        assertFalse(keyA.contentEquals(keyB))
    }

    @Test
    fun `corrupted peer message never matches - rejected or different key`() {
        val password = "123456".toByteArray()
        val alice = Spake2.newClient()
        val bob = Spake2.newServer()
        val msgA = alice.generateMsg(password)
        val msgB = bob.generateMsg(password)
        val keyB = bob.processMsg(msgA)

        val corrupted = msgB.copyOf().also { it[5] = (it[5].toInt() xor 0x40).toByte() }
        val keyA = try {
            // a flip usually lands off-curve and is rejected outright...
            alice.processMsg(corrupted)
        } catch (e: IllegalArgumentException) {
            return // rejection is the correct outcome
        }
        // ...but when the mutated bytes still decode, the keys MUST differ
        assertFalse(keyA.contentEquals(keyB))
    }

    @Test
    fun `adb pairing names include the NUL terminator`() {
        assertEquals(16, Spake2.NAME_CLIENT.size)
        assertEquals(16, Spake2.NAME_SERVER.size)
        assertTrue(Spake2.NAME_CLIENT.contentEquals("adb pair client".toByteArray() + 0))
        assertTrue(Spake2.NAME_SERVER.contentEquals("adb pair server".toByteArray() + 0))
    }
}
