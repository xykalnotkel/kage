package dev.kage.manager.adb

import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.Extensions
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.TBSCertificate
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.Validity
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

/**
 * The RSA-2048 key + self-signed certificate Kage presents to adbd, byte-compatible with the
 * "adb key" concept: the same key is used for the pairing TLS handshake (which is what adbd
 * actually stores) and for the TLS client certificate when connecting afterwards.
 *
 * Mirrors adb crypto/rsa_2048_key.cpp + crypto/x509_generator.cpp:
 *  - RSA 2048, exponent 65537
 *  - X.509 v3, serial 1, ~10 years, subject/issuer C=US, O=Android, CN=Adb
 *  - extensions: basicConstraints critical CA:TRUE, keyUsage critical
 *    digitalSignature|keyCertSign|cRLSign, subjectKeyIdentifier (hash)
 *  - signed with SHA256withRSA
 *
 * The certificate is assembled with raw bcprov ASN.1 on purpose: bcpkix's JCA bridging
 * (JcaContentSignerBuilder & friends) references classes that moved between bcprov releases,
 * while this path only needs asn1.* plus java.security.Signature.
 *
 * The Android public key line (PeerInfo payload / adbkey.pub format) follows
 * libcrypto_utils/android_pubkey.c: a little-endian struct of
 *   uint32 key_bits(2048), uint32 n0inv, uint8 modulus[256], uint8 rr[256], uint32 exponent
 * then base64 and a " user@host" suffix.
 */
class AdbKey private constructor(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {

    val certDer: ByteArray by lazy { certificate.encoded }

    /** The line adbd stores in its keystore when pairing succeeds. */
    val publicKeyLine: String by lazy {
        val pub = certificate.publicKey as RSAPublicKey
        val encoded = encodeAndroidPublicKey(pub)
        Base64.getEncoder().encodeToString(encoded) + " " + PUB_KEY_COMMENT
    }

    fun saveTo(dir: File) {
        dir.mkdirs()
        val keyFile = File(dir, KEY_FILE)
        val certFile = File(dir, CERT_FILE)
        keyFile.writeBytes(privateKey.encoded)
        certFile.writeBytes(certDer)
        // best effort hardening; the app-private dir is already protected by Linux uid
        runCatching {
            keyFile.setReadable(false, false)
            keyFile.setReadable(true, true)
            keyFile.setWritable(false, false)
            keyFile.setWritable(true, true)
        }
    }

    companion object {
        private const val KEY_FILE = "adbkey.pk8"
        private const val CERT_FILE = "adbkey.der"
        internal const val ANDROID_PUBKEY_ENCODED_SIZE = 524
        internal const val PUB_KEY_COMMENT = "kage@localhost"

        /** Loads the key from [dir], or generates and saves a new one. */
        fun loadOrCreate(dir: File): AdbKey {
            val keyFile = File(dir, KEY_FILE)
            val certFile = File(dir, CERT_FILE)
            if (keyFile.exists() && certFile.exists()) {
                runCatching {
                    load(dir)?.let { return it }
                }
                // fall through to regeneration when the files are corrupt
            }
            val key = generate()
            key.saveTo(dir)
            return key
        }

        fun load(dir: File): AdbKey? = runCatching {
            val keyBytes = File(dir, KEY_FILE).readBytes()
            val certBytes = File(dir, CERT_FILE).readBytes()
            val kf = KeyFactory.getInstance("RSA")
            val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(certBytes.inputStream()) as X509Certificate
            AdbKey(privateKey, cert)
        }.getOrNull()

        fun generate(): AdbKey {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val pair: KeyPair = kpg.generateKeyPair()
            val cert = buildCertificate(pair)
            return AdbKey(pair.private, cert)
        }

        private fun buildCertificate(pair: KeyPair): X509Certificate {
            val name = X500Name("C=US,O=Android,CN=Adb")
            val now = System.currentTimeMillis()
            val tenYearsMs = 10L * 365L * 24L * 60L * 60L * 1000L
            val sha256Rsa = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption)
            val spki = SubjectPublicKeyInfo.getInstance(pair.public.encoded)
            val der = ASN1Encoding.DER

            val skiValue = MessageDigest.getInstance("SHA-1").digest(spki.publicKeyData.bytes)
            val extensions = Extensions(
                arrayOf(
                    Extension(
                        Extension.basicConstraints, true,
                        BasicConstraints(true).toASN1Primitive().getEncoded(der)
                    ),
                    Extension(
                        Extension.keyUsage, true,
                        KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign or KeyUsage.cRLSign)
                            .toASN1Primitive().getEncoded(der)
                    ),
                    Extension(
                        Extension.subjectKeyIdentifier, false,
                        SubjectKeyIdentifier(skiValue).toASN1Primitive().getEncoded(der)
                    )
                )
            )

            val tbs = TBSCertificate(
                ASN1Integer(2),                      // X.509 v3
                ASN1Integer(BigInteger.ONE),         // serial 1, like adb
                sha256Rsa,
                name,                                // issuer == subject (self-signed)
                Validity(Time(Date(now)), Time(Date(now + tenYearsMs))),
                name,
                spki,
                null,                                // issuerUID
                null,                                // subjectUID
                extensions
            )
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(pair.private)
            signer.update(tbs.getEncoded(der))
            val cert = Certificate(tbs, sha256Rsa, DERBitString(signer.sign()))

            val x509 = CertificateFactory.getInstance("X.509")
                .generateCertificate(cert.encoded.inputStream()) as X509Certificate
            x509.verify(pair.public)
            return x509
        }

        /**
         * libcrypto_utils/android_pubkey.c android_pubkey_encode: everything little-endian.
         * n0inv = -n^-1 mod 2^32; rr = R^2 mod n with R = 2^2048.
         */
        internal fun encodeAndroidPublicKey(pub: RSAPublicKey): ByteArray {
            val n = pub.modulus
            if (n.bitLength() != 2048) throw IllegalArgumentException("expected 2048-bit RSA key")
            val e = pub.publicExponent

            val n0inv = run {
                val nMod = n.mod(BigInteger.TWO.pow(32))
                val inv = nMod.modInverse(BigInteger.TWO.pow(32))
                BigInteger.TWO.pow(32).subtract(inv).mod(BigInteger.TWO.pow(32))
            }
            val rr = BigInteger.TWO.pow(4096).mod(n)

            val out = ByteArray(ANDROID_PUBKEY_ENCODED_SIZE)
            writeLeInt(out, 0, BigInteger.valueOf(2048))
            writeLeInt(out, 4, n0inv)
            writeLeBytes(out, 8, n, 256)
            writeLeBytes(out, 264, rr, 256)
            writeLeInt(out, 520, e)
            return out
        }

        private fun writeLeInt(out: ByteArray, off: Int, v: BigInteger) {
            var x = v
            for (i in 0 until 4) {
                out[off + i] = x.and(BigInteger.valueOf(0xFFL)).toByte()
                x = x.shiftRight(8)
            }
        }

        private fun writeLeBytes(out: ByteArray, off: Int, v: BigInteger, len: Int) {
            var x = v
            for (i in 0 until len) {
                out[off + i] = x.and(BigInteger.valueOf(0xFFL)).toByte()
                x = x.shiftRight(8)
            }
        }
    }
}
