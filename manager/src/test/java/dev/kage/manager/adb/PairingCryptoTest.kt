package dev.kage.manager.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

class PairingCryptoTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `hkdf sha256 matches RFC 5869 test case 1`() {
        val okm = PairingCrypto.hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun `encrypt-decrypt roundtrip with realistic sizes`() {
        val crypto = PairingCrypto(ByteArray(64) { it.toByte() })
        val peerInfo = ByteArray(8192).also { it[0] = 0; "adbkeyline".toByteArray().copyInto(it, 1) }
        val encrypted = crypto.encrypt(peerInfo)
        assertEquals(8192 + 16, encrypted.size)
        val decrypted = crypto.decrypt(encrypted)
        assertArrayEquals(peerInfo, decrypted)
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val crypto = PairingCrypto(ByteArray(64) { it.toByte() })
        val encrypted = crypto.encrypt("hello adb".toByteArray())
        encrypted[3] = (encrypted[3].toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) { crypto.decrypt(encrypted) }
    }

    @Test
    fun `nonces advance per operation so identical plaintexts differ`() {
        val crypto = PairingCrypto(ByteArray(64) { 7 })
        val a = crypto.encrypt(byteArrayOf(1, 2, 3))
        val b = crypto.encrypt(byteArrayOf(1, 2, 3))
        assertFalse(a.contentEquals(b))
        assertArrayEquals(byteArrayOf(1, 2, 3), crypto.decrypt(a))
        assertArrayEquals(byteArrayOf(1, 2, 3), crypto.decrypt(b))
    }
}
