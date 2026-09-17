package dev.kage.manager.adb

import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsServerContext
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.util.concurrent.atomic.AtomicReference

/**
 * A real BC TLS server on localhost for loopback tests - it requests a client certificate and
 * accepts anything, the same posture adbd's pairing/connect services have.
 */
class TlsTestServer(
    private val key: AdbKey,
    private val handler: (InputStream, OutputStream, TlsServerContext) -> Unit,
) {

    private val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port: Int get() = serverSocket.localPort

    /** Pairing exporter captured at handshake completion (BC gate - see TlsChannels). */
    val exportedKeying = java.util.concurrent.atomic.AtomicReference<ByteArray>()

    private val thread = Thread {
        try {
            serverSocket.accept().use { socket ->
                socket.soTimeout = 60_000
                serve(socket)
            }
        } catch (t: Throwable) {
            System.err.println("=== TlsTestServer failure ===")
            t.printStackTrace()
        }
    }

    init {
        thread.start()
    }

    private fun serve(socket: Socket) {
        val proto = TlsServerProtocol(socket.getInputStream(), socket.getOutputStream())
        val ctxRef = AtomicReference<TlsServerContext>()
        val crypto = BcTlsCrypto(SecureRandom())
        val server = object : DefaultTlsServer(crypto) {
            override fun init(context: TlsServerContext) {
                super.init(context)
                ctxRef.set(context)
            }

            override fun getCertificateRequest(): CertificateRequest {
                val algs = java.util.Vector<SignatureAndHashAlgorithm>()
                algs.add(SignatureAndHashAlgorithm.rsa_pss_rsae_sha256)
                return CertificateRequest(ByteArray(0), algs, null, null)
            }

            override fun getCredentials(): TlsCredentials = signerFor(key, ctxRef.get())

            override fun notifyHandshakeComplete() {
                super.notifyHandshakeComplete()
                runCatching {
                    exportedKeying.set(
                        ctxRef.get().exportKeyingMaterial(
                            PairingConnection.EXPORTER_LABEL_STRING, null, PairingConnection.EXPORTED_KEY_SIZE
                        )
                    )
                }
            }

            override fun notifyClientCertificate(clientCertificate: org.bouncycastle.tls.Certificate) {
                // accept everything, like adbd's pairing TLS
            }
        }
        proto.accept(server)
        handler(proto.inputStream, proto.outputStream, ctxRef.get() ?: throw AssertionError("no ctx"))
    }

    fun close() {
        serverSocket.close()
    }

    companion object {
        fun signerFor(key: AdbKey, context: TlsServerContext?): TlsCredentials {
            val crypto = BcTlsCrypto(SecureRandom())
            val jk = key.privateKey as RSAPrivateCrtKey
            val params = org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters(
                jk.modulus, jk.publicExponent, jk.privateExponent,
                jk.primeP, jk.primeQ, jk.primeExponentP, jk.primeExponentQ, jk.crtCoefficient
            )
            val chain = arrayOf(crypto.createCertificate(key.certDer))
            // TLS 1.3 requires the ctor WITH request context (empty = not requested)
            val tlsCertificate = org.bouncycastle.tls.Certificate(
                ByteArray(0),
                arrayOf(org.bouncycastle.tls.CertificateEntry(chain[0], null))
            )
            return BcDefaultTlsCredentialedSigner(
                TlsCryptoParameters(context ?: throw AssertionError("no tls context")),
                crypto,
                params,
                tlsCertificate,
                SignatureAndHashAlgorithm.rsa_pss_rsae_sha256
            )
        }

        /** Pairing frame write, deliberately re-implemented here to cross-check the client. */
        fun writeFrame(out: OutputStream, type: Int, payload: ByteArray) {
            val header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            header.put(1)
            header.put(type.toByte())
            header.putInt(payload.size)
            out.write(header.array())
            out.write(payload)
            out.flush()
        }

        fun readFrame(input: InputStream): Pair<Int, ByteArray> {
            val header = ByteArray(6)
            var off = 0
            while (off < 6) {
                val n = input.read(header, off, 6 - off)
                if (n < 0) throw java.io.EOFException()
                off += n
            }
            val version = header[0].toInt() and 0xFF
            val type = header[1].toInt() and 0xFF
            require(version == 1) { "bad version" }
            val size = ByteBuffer.wrap(header, 2, 4).order(ByteOrder.BIG_ENDIAN).int
            val payload = ByteArray(size)
            off = 0
            while (off < size) {
                val n = input.read(payload, off, size - off)
                if (n < 0) throw java.io.EOFException()
                off += n
            }
            return Pair(type, payload)
        }
    }
}
