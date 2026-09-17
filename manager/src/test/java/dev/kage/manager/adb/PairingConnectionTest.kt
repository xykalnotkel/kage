package dev.kage.manager.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.kage.manager.wireless.NativePairing
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Full pairing loop on localhost: our PairingConnection (client/Alice) against a TLS pairing
 * server (Bob) that follows adbd's side of the protocol. The TLS exporter, SPAKE2 key and
 * AES-GCM sequence numbers must agree exactly for this to pass.
 */
class PairingConnectionTest {

    private val key = AdbKey.generate()

    /**
     * adbd-side pairing handler: exchange SPAKE2 msgs, decrypt the client's PeerInfo, send ours.
     */
    private fun serverHandler(
        exported: () -> ByteArray?,
        code: ByteArray,
        guid: String,
    ): (InputStream, OutputStream, org.bouncycastle.tls.TlsServerContext) -> Unit =
        { input, output, _ ->
            val exporter = exported() ?: throw IOException("no exporter")
            val spake = Spake2.newServer()

            val bobMsg = spake.generateMsg(code + exporter)
            TlsTestServer.writeFrame(output, PairingConnection.TYPE_SPAKE2_MSG, bobMsg)

            val (t1, theirMsg) = TlsTestServer.readFrame(input)
            if (t1 != PairingConnection.TYPE_SPAKE2_MSG) throw IOException("expected spake2 msg")
            val cipher = PairingCrypto(spake.processMsg(theirMsg))

            val info = ByteArray(PairingPeerInfo.STRUCT_SIZE)
            info[0] = PairingPeerInfo.TYPE_DEVICE_GUID.toByte()
            guid.toByteArray(Charsets.UTF_8).copyInto(info, 1)
            TlsTestServer.writeFrame(output, PairingConnection.TYPE_PEER_INFO, cipher.encrypt(info))

            val (t2, theirInfo) = TlsTestServer.readFrame(input)
            if (t2 != PairingConnection.TYPE_PEER_INFO) throw IOException("expected peer info")
            val decrypted = cipher.decrypt(theirInfo)
            assertEquals(PairingPeerInfo.STRUCT_SIZE, decrypted.size)
            assertEquals(PairingPeerInfo.TYPE_RSA_PUB_KEY, decrypted[0].toInt())
            assertTrue(
                "client sent unexpected pubkey line",
                String(decrypted, 1, 64, Charsets.UTF_8).startsWith(key.publicKeyLine.take(32))
            )
        }

    private fun startServer(code: ByteArray, guid: String): TlsTestServer {
        // the exporter is captured by the server during the handshake; the handler reads it
        // through a holder because the lambda is built before the server exists
        val holder = arrayOfNulls<TlsTestServer>(1)
        val server = TlsTestServer(key, serverHandler({ holder[0]?.exportedKeying?.get() }, code, guid))
        holder[0] = server
        return server
    }

    @Test
    fun `client pairs against a protocol-correct server`() {
        val server = startServer("246810".toByteArray(), "kage-test-guid")
        try {
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 60_000
                val peer = PairingConnection(key).runClient(socket, "246810".toByteArray())
                assertEquals(PairingPeerInfo.TYPE_DEVICE_GUID, peer.type)
                assertEquals("kage-test-guid", peer.asCString())
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `wrong code fails before any usable result`() {
        val server = startServer("111111".toByteArray(), "guid-2")
        try {
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 60_000
                // the AES-GCM tag check must fail (any exception type - BC wraps it), and it must
                // happen on the PEER_INFO step, never produce a peer
                val ex = assertThrows(Exception::class.java) {
                    PairingConnection(key).runClient(socket, "999999".toByteArray())
                }
                assertFalse(ex is java.net.ConnectException)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `host port parsing handles v4 v6 and localhost`() {
        assertEquals("192.168.1.9" to 5555, NativePairing.splitHostPort("192.168.1.9:5555"))
        assertEquals("localhost" to 1234, NativePairing.splitHostPort("localhost:1234"))
        assertEquals("::1" to 9999, NativePairing.splitHostPort("[::1]:9999"))
        assertThrows(IOException::class.java) { NativePairing.splitHostPort("no-port-here") }
    }
}
