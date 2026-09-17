package dev.kage.manager.adb

import org.bouncycastle.tls.TlsClientProtocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Signature

/**
 * A tiny native adb client over TLS 1.3 - the wire side of `adb connect host:port` followed by
 * `adb shell <command>`. Kage runs entirely on the phone, so host = the phone itself.
 *
 * Protocol (adb.h): 24-byte header, all fields little-endian:
 *   command u32, arg0 u32, arg1 u32, data length u32, data checksum u32, magic u32 (=~command)
 * Over TLS the auth token exchange is skipped by design ("All AUTH commands are ignored in TLS
 * mode" in adb.cpp) - identity is the TLS client certificate adbd learned during pairing. We
 * still implement the classic AUTH handshake in case an adbd asks for it.
 *
 * Output streaming mirrors `adb shell` without shell_v2: one byte stream, end on CLSE.
 */
class AdbClient(private val key: AdbKey) {

    data class ShellResult(val output: String, val exitHint: Int = 0)

    /**
     * Runs [command] on the device and streams stdout/stderr chunks into [onChunk].
     * Blocking; returns the accumulated output (UTF-8, best effort).
     */
    fun shell(
        host: String,
        port: Int,
        command: String,
        onChunk: (ByteArray) -> Unit = {},
        connectTimeoutMs: Int = 10_000,
        ioTimeoutMs: Int = 30_000,
        totalTimeoutMs: Long = 120_000,
    ): ShellResult {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.tcpNoDelay = true
            socket.soTimeout = ioTimeoutMs

            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = BufferedOutputStream(socket.getOutputStream())
            val tls = TlsChannels.handshake(streamIn = input, streamOut = output, key = key)
            val tlsIn = tls.inputStream
            val tlsOut = tls.outputStream

            val deadline = System.currentTimeMillis() + totalTimeoutMs
            fun ensureTime() {
                if (System.currentTimeMillis() > deadline) throw IOException("adb shell timeout")
            }

            var localId = 0
            var remoteId = 0
            val collected = StringBuilder()

            fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = EMPTY) {
                val header = headerOf(cmd, arg0, arg1, data)
                tlsOut.write(header)
                if (data.isNotEmpty()) tlsOut.write(data)
                tlsOut.flush()
            }

            // banner: same shape as the adb host ("host::features=..."), contents are informational
            send(CMD_CNXN, PROTOCOL_VERSION, MAX_PAYLOAD, "host::features=".toByteArray(Charsets.UTF_8))

            var opened = false
            while (true) {
                ensureTime()
                val header = ByteArray(24)
                readFully(tlsIn, header)
                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val cmd = bb.int
                val arg0 = bb.int
                val arg1 = bb.int
                val dataLen = bb.int
                val checksum = bb.int
                val magic = bb.int
                if (magic != cmd.inv()) throw IOException("adb magic mismatch")
                if (dataLen < 0 || dataLen > MAX_PAYLOAD) throw IOException("adb bad data length $dataLen")
                val data = if (dataLen > 0) ByteArray(dataLen).also { readFully(tlsIn, it) } else EMPTY
                if (cmd == CMD_WRTE && checksumOf(data) != checksum.toLong() and 0xFFFFFFFFL) {
                    // tolerate a wrong checksum: TLS already guarantees integrity (SKIP_CHECKSUM era)
                }

                when (cmd) {
                    CMD_CNXN -> {
                        // device is ready - open the shell stream
                        localId = 1
                        send(CMD_OPEN, localId, 0, "shell:$command".toByteArray(Charsets.UTF_8))
                    }

                    CMD_AUTH -> {
                        when (arg0) {
                            AUTH_TOKEN -> send(CMD_AUTH, AUTH_SIGNATURE, 0, authSignature(data))
                            AUTH_RSAPUBLICKEY -> send(
                                CMD_AUTH, AUTH_RSAPUBLICKEY, 0,
                                (key.publicKeyLine + "\u0000").toByteArray(Charsets.UTF_8)
                            )
                        }
                    }

                    CMD_OKAY -> {
                        if (dataLen != 0) throw IOException("adb OKAY with data")
                        remoteId = arg0
                        if (!opened) opened = true
                    }

                    CMD_WRTE -> {
                        if (!opened) throw IOException("adb WRTE before OKAY")
                        onChunk(data)
                        collected.append(String(data, Charsets.UTF_8))
                        send(CMD_OKAY, localId, remoteId)
                    }

                    CMD_CLSE -> {
                        return ShellResult(collected.toString())
                    }

                    else -> throw IOException("adb unexpected command 0x${Integer.toHexString(cmd)}")
                }
            }
        }
    }

    /**
     * adb's send_auth_response signs the raw token with RSA PKCS#1 v1.5 where the token itself
     * is the SHA-1 digest (RSA_sign(NID_sha1, token, ...)): DigestInfo(SHA1) || token.
     */
    private fun authSignature(token: ByteArray): ByteArray {
        val digestInfo = SHA1_DIGEST_INFO_PREFIX + token
        val sig = Signature.getInstance("NONEwithRSA")
        sig.initSign(key.privateKey)
        sig.update(digestInfo)
        return sig.sign()
    }

    companion object {
        // adb.h
        const val CMD_CNXN = 0x4e584e43
        const val CMD_OPEN = 0x4e45504f
        const val CMD_OKAY = 0x59414b4f
        const val CMD_CLSE = 0x45534c43
        const val CMD_WRTE = 0x45545257
        const val CMD_AUTH = 0x48545541

        // adb_auth.h
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        const val PROTOCOL_VERSION = 0x01000001 // A_VERSION (checksum skipped era)
        const val MAX_PAYLOAD = 1024 * 1024

        private val EMPTY = ByteArray(0)

        /** DigestInfo header for SHA-1 (RFC 8017): SEQ(21) SEQ(9) OID 1.3.14.3.2.26 NULL OCTET(14) */
        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )

        private fun headerOf(cmd: Int, arg0: Int, arg1: Int, data: ByteArray): ByteArray {
            val bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(cmd)
            bb.putInt(arg0)
            bb.putInt(arg1)
            bb.putInt(data.size)
            bb.putInt(checksumOf(data).toInt())
            bb.putInt(cmd.inv())
            return bb.array()
        }

        private fun checksumOf(data: ByteArray): Long {
            var sum = 0L
            for (b in data) sum += (b.toInt() and 0xFF).toLong()
            return sum and 0xFFFFFFFFL
        }

        private fun readFully(input: java.io.InputStream, buf: ByteArray) {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) throw EOFException("connection closed")
                off += n
            }
        }
    }
}
