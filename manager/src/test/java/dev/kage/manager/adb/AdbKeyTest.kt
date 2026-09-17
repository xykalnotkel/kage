package dev.kage.manager.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigInteger
import java.security.cert.X509Certificate
import java.util.Base64

class AdbKeyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `android public key line has the exact libcrypto_utils layout`() {
        val key = AdbKey.generate()
        val line = key.publicKeyLine
        assertTrue("comment suffix", line.endsWith(" " + AdbKey.PUB_KEY_COMMENT))

        val b64 = line.removeSuffix(" " + AdbKey.PUB_KEY_COMMENT)
        val raw = Base64.getDecoder().decode(b64)
        assertEquals(AdbKey.ANDROID_PUBKEY_ENCODED_SIZE, raw.size)

        fun leInt(off: Int): Long {
            var v = 0L
            for (i in 3 downTo 0) v = (v shl 8) or (raw[off + i].toLong() and 0xFF)
            return v
        }
        assertEquals(2048L, leInt(0))                        // key_bits
        assertEquals(65537L, leInt(520))                     // exponent

        val modulus = BigInteger(1, raw.copyOfRange(8, 264).reversedArray())
        val certPub = key.certificate.publicKey as java.security.interfaces.RSAPublicKey
        assertEquals(certPub.modulus, modulus)

        // n0inv must equal -n^-1 mod 2^32 ( Montgomery parameter)
        val n0 = BigInteger.valueOf(leInt(4))
        val nMod = modulus.mod(BigInteger.TWO.pow(32))
        val check = n0.multiply(nMod).add(BigInteger.ONE).mod(BigInteger.TWO.pow(32))
        assertEquals(BigInteger.ZERO, check)
    }

    @Test
    fun `certificate mirrors adb x509_generator`() {
        val cert: X509Certificate = AdbKey.generate().certificate
        assertEquals("3", cert.version.toString())
        assertEquals(1, cert.serialNumber.toInt())
        assertEquals("CN=Adb,O=Android,C=US", cert.subjectX500Principal.name)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertTrue(cert.basicConstraints != -1) // CA:TRUE
        val tenYears = 10L * 365 * 24 * 60 * 60 * 1000
        val validity = cert.notAfter.time - cert.notBefore.time
        assertTrue("validity $validity", validity in tenYears..tenYears + 2 * 86_400_000)
    }

    @Test
    fun `persistence roundtrip`() {
        val dir: File = tmp.newFolder("adbkey")
        val key = AdbKey.loadOrCreate(dir)
        assertTrue(File(dir, "adbkey.pk8").exists())
        assertTrue(File(dir, "adbkey.der").exists())

        val loaded = AdbKey.load(dir)
        assertNotNull(loaded)
        assertEquals(key.publicKeyLine, loaded!!.publicKeyLine)
        assertArrayEquals(key.certDer, loaded.certDer)

        // a second loadOrCreate must not regenerate
        assertEquals(key.publicKeyLine, AdbKey.loadOrCreate(dir).publicKeyLine)
    }

    @Test
    fun `two generated keys are independent`() {
        assertNotEquals(AdbKey.generate().publicKeyLine, AdbKey.generate().publicKeyLine)
    }
}
