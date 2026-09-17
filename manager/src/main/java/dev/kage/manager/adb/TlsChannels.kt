package dev.kage.manager.adb

import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientContext
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.util.concurrent.atomic.AtomicReference

/**
 * The one TLS setup shared by the pairing connection and the adb client: a TLS 1.3 client
 * with a mandatory client certificate (our AdbKey) that accepts any server certificate.
 *
 * adbd behaves exactly like this on the pairing service (tls/tls_connection.cpp pins
 * min=max=TLS1.3 and SSL_VERIFY_PEER|FAIL_IF_NO_PEER_CERT; pairing_connection.cpp overrides
 * verification to accept anything). The server's identity is really established by the
 * SPAKE2 password bound into the TLS exporter, not by the certificate chain.
 */
internal object TlsChannels {

    /** Protocol endpoint + the RFC 5705 keying material captured at handshake completion. */
    class Channel internal constructor(
        val protocol: TlsClientProtocol,
        private val exported: ByteArray?,
    ) {
        val inputStream: java.io.InputStream get() = protocol.inputStream
        val outputStream: java.io.OutputStream get() = protocol.outputStream

        /**
         * BouncyCastle only allows exporters inside notifyHandshakeComplete(), so the value was
         * captured there; the label/length parameters mirror the C API and are matched by caller.
         */
        fun exportKeyingMaterial(label: String, contextValue: ByteArray?, length: Int): ByteArray? {
            require(contextValue == null)
            return exported?.takeIf { it.size == length }
        }
    }

    /** Does the TLS 1.3 handshake; returns the channel ready for data. */
    fun handshake(
        streamIn: java.io.InputStream,
        streamOut: java.io.OutputStream,
        key: AdbKey,
        exporterLabel: String? = null,
        exporterLength: Int = 0,
    ): Channel {
        val protocol = TlsClientProtocol(streamIn, streamOut)
        val contextRef = AtomicReference<TlsClientContext>()
        val exportedRef = AtomicReference<ByteArray>()
        val crypto = BcTlsCrypto(SecureRandom())

        val client = object : DefaultTlsClient(crypto) {
            override fun init(context: TlsClientContext) {
                super.init(context)
                contextRef.set(context)
            }

            override fun notifyHandshakeComplete() {
                super.notifyHandshakeComplete()
                val label = exporterLabel ?: return
                runCatching {
                    exportedRef.set(contextRef.get().exportKeyingMaterial(label, null, exporterLength))
                }
            }

            override fun getAuthentication(): TlsAuthentication {
                return object : TlsAuthentication {
                    override fun notifyServerCertificate(serverCertificate: TlsServerCertificate?) {
                        // accept any certificate - see class doc
                    }

                    override fun getClientCredentials(request: CertificateRequest): TlsCredentials {
                        return signerFor(key, contextRef.get())
                    }
                }
            }
        }
        protocol.connect(client)
        return Channel(protocol, exportedRef.get())
    }

    private fun signerFor(key: AdbKey, context: TlsClientContext?): BcDefaultTlsCredentialedSigner {
        val crypto = BcTlsCrypto(SecureRandom())
        val javaKey = key.privateKey as RSAPrivateCrtKey
        val params = org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters(
            javaKey.modulus, javaKey.publicExponent, javaKey.privateExponent,
            javaKey.primeP, javaKey.primeQ, javaKey.primeExponentP,
            javaKey.primeExponentQ, javaKey.crtCoefficient
        )
        val chain = arrayOf(crypto.createCertificate(key.certDer))
        // TLS 1.3 requires the ctor WITH request context (empty = not requested)
        val tlsCertificate = org.bouncycastle.tls.Certificate(
            ByteArray(0),
            arrayOf(org.bouncycastle.tls.CertificateEntry(chain[0], null))
        )
        return BcDefaultTlsCredentialedSigner(
            TlsCryptoParameters(context ?: throw IllegalStateException("tls context missing")),
            crypto,
            params,
            tlsCertificate,
            SignatureAndHashAlgorithm.rsa_pss_rsae_sha256
        )
    }
}
