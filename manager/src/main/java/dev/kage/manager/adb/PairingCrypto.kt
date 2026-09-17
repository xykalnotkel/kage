package dev.kage.manager.adb

import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cipher adb wireless pairing uses to protect the PeerInfo exchange: AES-128-GCM keyed
 * via HKDF-SHA256 from the SPAKE2 shared secret.
 *
 * Port of adb pairing_auth/aes_128_gcm.cpp:
 *  - HKDF-SHA256(empty salt, key material, info = "adb pairing_auth aes-128-gcm key", 16 bytes)
 *  - 12-byte nonce = uint32 sequence number little-endian followed by 8 zero bytes;
 *    encrypt and decrypt keep independent counters that both start at 0
 *  - 128-bit GCM tag (EVP_AEAD_DEFAULT_TAG_LENGTH)
 */
internal class PairingCrypto(keyMaterial: ByteArray) {

    private val key: ByteArray
    private var encryptSeq = 0L
    private var decryptSeq = 0L

    init {
        val info = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8)
        key = hkdfSha256(keyMaterial, ByteArray(32), info, 16)
    }

    /** Encrypts with the current encrypt counter; output = ciphertext || 16-byte tag. */
    fun encrypt(plain: ByteArray): ByteArray {
        val nonce = nonceFor(encryptSeq)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val out = cipher.doFinal(plain)
        encryptSeq++
        return out
    }

    /** Decrypts with the current decrypt counter; throws when the tag does not verify. */
    fun decrypt(ciphertextAndTag: ByteArray): ByteArray {
        if (ciphertextAndTag.size < TAG_LEN) throw GeneralSecurityException("ciphertext too short")
        val nonce = nonceFor(decryptSeq)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val out = cipher.doFinal(ciphertextAndTag)
        decryptSeq++
        return out
    }

    private fun nonceFor(seq: Long): ByteArray {
        val nonce = ByteArray(12)
        nonce[0] = (seq and 0xff).toByte()
        nonce[1] = ((seq ushr 8) and 0xff).toByte()
        nonce[2] = ((seq ushr 16) and 0xff).toByte()
        nonce[3] = ((seq ushr 24) and 0xff).toByte()
        return nonce
    }

    companion object {
        const val TAG_LEN = 16

        /** RFC 5869 HKDF with SHA-256. */
        fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            // extract
            mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            // expand
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = ByteArray(length)
            var t = ByteArray(0)
            var pos = 0
            var counter = 1
            while (pos < length) {
                mac.reset()
                mac.update(t)
                mac.update(info)
                mac.update(counter.toByte())
                t = mac.doFinal()
                val n = minOf(t.size, length - pos)
                System.arraycopy(t, 0, out, pos, n)
                pos += n
                counter++
            }
            return out
        }

        fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    }
}
