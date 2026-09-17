package dev.kage.manager.adb

import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The PeerInfo struct adb peers exchange inside the encrypted pairing channel. */
data class PairingPeerInfo(val type: Int, val data: ByteArray) {

    /** Reads the C-string in [data] (guid, public key line, ...). */
    fun asCString(): String {
        val end = data.indexOf(0)
        val stop = if (end >= 0) end else data.size
        return String(data, 0, stop, Charsets.UTF_8)
    }

    companion object {
        const val TYPE_RSA_PUB_KEY = 0
        const val TYPE_DEVICE_GUID = 1
        const val STRUCT_SIZE = 8192

        fun fromStruct(bytes: ByteArray): PairingPeerInfo {
            if (bytes.size != STRUCT_SIZE) throw IOException("PeerInfo size ${bytes.size} != $STRUCT_SIZE")
            val type = bytes[0].toInt() and 0xFF
            return PairingPeerInfo(type, bytes.copyOfRange(1, bytes.size))
        }

        /** Builds the 8192-byte struct the adb host sends: type + public key line + zero padding. */
        fun rsaPubKey(publicKeyLine: String): ByteArray {
            val line = publicKeyLine.toByteArray(Charsets.UTF_8)
            val out = ByteArray(STRUCT_SIZE)
            out[0] = TYPE_RSA_PUB_KEY.toByte()
            if (line.size > out.size - 1) throw IOException("public key line too long")
            System.arraycopy(line, 0, out, 1, line.size)
            return out
        }
    }
}

/**
 * The adb wireless pairing protocol, client side (adb pair).
 *
 * Port of adb pairing_connection/pairing_connection.cpp + pairing_auth:
 *
 *  1. TLS 1.3 handshake, client certificate = our adb key, any server certificate accepted;
 *  2. 64 bytes of TLS exporter keying material (label "adb-label\0", no context) are appended
 *     to the 6-digit code - the SPAKE2 password is code + exporter;
 *  3. SPAKE2 messages are exchanged in plain (but TLS-protected) frames;
 *  4. both sides exchange their 8192-byte PeerInfo struct encrypted with AES-128-GCM keyed
 *     from the SPAKE2 secret. Wrong code => decryption fails => pairing fails.
 *
 * Frame layout: [u8 version=1][u8 type][u32 payload size, big endian] + payload,
 * types SPAKE2_MSG=0, PEER_INFO=1.
 */
class PairingConnection(private val key: AdbKey) {

    /** Runs the client role over an already-connected socket. Blocking. */
    fun runClient(socket: Socket, code: ByteArray): PairingPeerInfo {
        try {
            socket.tcpNoDelay = true

            // password = code || SSL_export_keying_material("adb-label\0", no context, 64);
            // BouncyCastle only allows the export inside notifyHandshakeComplete, so the label
            // is handed to the handshake and the captured value returned via the Channel
            val tls = TlsChannels.handshake(
                streamIn = socket.getInputStream(),
                streamOut = socket.getOutputStream(),
                key = key,
                exporterLabel = EXPORTER_LABEL_STRING,
                exporterLength = EXPORTED_KEY_SIZE
            )
            val exported = tls.exportKeyingMaterial(EXPORTER_LABEL_STRING, null, EXPORTED_KEY_SIZE)
                ?: throw IOException("export keying material failed")
            val spakePassword = code + exported

            val spake = Spake2.newClient()
            val myMsg = spake.generateMsg(spakePassword)
            writeFrame(tls, TYPE_SPAKE2_MSG, myMsg)
            val (t, theirMsg) = readFrame(tls)
            if (t != TYPE_SPAKE2_MSG) throw IOException("expected SPAKE2 msg, got $t")
            val cipher = PairingCrypto(spake.processMsg(theirMsg))

            // send our PeerInfo encrypted, then read and decrypt theirs
            val encrypted = cipher.encrypt(PairingPeerInfo.rsaPubKey(key.publicKeyLine))
            writeFrame(tls, TYPE_PEER_INFO, encrypted)
            val (t2, payload) = readFrame(tls)
            if (t2 != TYPE_PEER_INFO) throw IOException("expected PEER_INFO, got $t2")
            val decrypted = cipher.decrypt(payload)
            return PairingPeerInfo.fromStruct(decrypted)
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        const val TYPE_SPAKE2_MSG = 0
        const val TYPE_PEER_INFO = 1
        const val HEADER_SIZE = 6
        const val MAX_PAYLOAD = 2 * 8192
        const val EXPORTED_KEY_SIZE = 64
        const val EXPORTER_LABEL_STRING = "adb-label\u0000" // C code exports sizeof(label) bytes: NUL included

        private fun writeFrame(tls: TlsChannels.Channel, type: Int, payload: ByteArray) {
            val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            header.put(1)
            header.put(type.toByte())
            header.putInt(payload.size)
            val out = tls.outputStream
            out.write(header.array())
            out.write(payload)
            out.flush()
        }

        private fun readFrame(tls: TlsChannels.Channel): Pair<Int, ByteArray> {
            val input = tls.inputStream
            val header = ByteArray(HEADER_SIZE)
            readFully(input, header)
            val version = header[0].toInt() and 0xFF
            if (version != 1) throw IOException("pairing header version $version")
            val type = header[1].toInt() and 0xFF
            if (type !in intArrayOf(TYPE_SPAKE2_MSG, TYPE_PEER_INFO)) throw IOException("pairing type $type")
            val buf = ByteBuffer.wrap(header, 2, 4).order(ByteOrder.BIG_ENDIAN)
            val payloadSize = buf.int
            if (payloadSize <= 0 || payloadSize > MAX_PAYLOAD) throw IOException("bad payload size $payloadSize")
            val payload = ByteArray(payloadSize)
            readFully(input, payload)
            return Pair(type, payload)
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

/** Thin convenience wrapper: TCP connect + pairing, mirroring what `adb pair host:port code` does. */
class PairingClient(
    private val host: String,
    private val port: Int,
    private val code: ByteArray,
    private val key: AdbKey,
    private val connectTimeoutMs: Int = 10_000,
    private val ioTimeoutMs: Int = 25_000,
) {

    /** @return the device's peer info (type ADB_DEVICE_GUID on success). */
    fun run(): PairingPeerInfo {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = ioTimeoutMs
            return PairingConnection(key).runClient(socket, code)
        }
    }
}
