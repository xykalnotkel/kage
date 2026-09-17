package dev.kage.manager.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Signature

/** DigestInfo(SHA-1) prefix used by adb's AUTH signature (RSA_sign(NID_sha1, token)). */
private fun digestInfo(token: ByteArray): ByteArray =
    byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14) + token

private data class RawMessage(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

private fun readRaw(input: InputStream): RawMessage {
    val head = ByteArray(24)
    var off = 0
    while (off < 24) {
        val n = input.read(head, off, 24 - off)
        if (n < 0) throw java.io.EOFException()
        off += n
    }
    val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
    val cmd = bb.int
    val arg0 = bb.int
    val arg1 = bb.int
    val len = bb.int
    bb.int // checksum
    val magic = bb.int
    if (magic != cmd.inv()) throw AssertionError("bad magic")
    val data = if (len > 0) ByteArray(len).also {
        var o = 0
        while (o < len) {
            val r = input.read(it, o, len - o)
            if (r < 0) throw java.io.EOFException()
            o += r
        }
    } else ByteArray(0)
    return RawMessage(cmd, arg0, arg1, data)
}

/**
 * Loopback against a mock adbd speaking the ADB protocol over TLS: CNXN -> OPEN -> OKAY ->
 * WRTE(s) -> CLSE. Exercises AdbClient end to end, including the (theoretical, non-TLS) AUTH path.
 */
class AdbClientTest {

    private val key = AdbKey.generate()

    private class Adbd(val banner: String = "hello from adbd") {
        var seenCommand: String? = null

        /** Runs the normal (already-authorized) flow, starting from [cnxn] we already read. */
        fun handle(cnxn: RawMessage, input: InputStream, output: OutputStream) {
            assertEquals(AdbClient.CMD_CNXN, cnxn.cmd)
            assertEquals(AdbClient.PROTOCOL_VERSION, cnxn.arg0)

            fun head(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)): ByteArray {
                var sum = 0L
                for (b in data) sum += (b.toInt() and 0xFF)
                return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size)
                    .putInt(sum.toInt()).putInt(cmd.inv()).array()
            }

            val bannerBytes = "device::".toByteArray(Charsets.UTF_8)
            output.write(head(AdbClient.CMD_CNXN, AdbClient.PROTOCOL_VERSION, 1024 * 1024, bannerBytes))
            output.write(bannerBytes)
            output.flush()

            val open = readRaw(input)
            assertEquals(AdbClient.CMD_OPEN, open.cmd)
            seenCommand = String(open.data, Charsets.UTF_8)

            output.write(head(AdbClient.CMD_OKAY, 7, open.arg0))
            output.flush()

            val data = banner.toByteArray(Charsets.UTF_8)
            output.write(head(AdbClient.CMD_WRTE, 7, open.arg0, data))
            output.write(data)
            output.flush()
            val ack = readRaw(input)
            assertEquals(AdbClient.CMD_OKAY, ack.cmd)
            assertEquals(7, ack.arg1)

            output.write(head(AdbClient.CMD_CLSE, 7, open.arg0))
            output.flush()
        }
    }

    @Test
    fun `shell roundtrip works end to end`() {
        val adbd = Adbd()
        val server = TlsTestServer(key) { input, output, _ ->
            adbd.handle(readRaw(input), input, output)
        }
        try {
            val client = AdbClient(key)
            val chunks = mutableListOf<String>()
            val result = client.shell(
                "127.0.0.1", server.port, "echo hello",
                { chunk -> chunks.add(String(chunk, Charsets.UTF_8)) },
                connectTimeoutMs = 10_000,
                ioTimeoutMs = 30_000,
                totalTimeoutMs = 60_000
            )
            assertEquals("shell:echo hello", adbd.seenCommand)
            assertEquals("hello from adbd", result.output)
            assertTrue(chunks.joinToString("").contains("hello from adbd"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `auth token challenge is answered with a digestinfo signature`() {
        val token = ByteArray(20) { it.toByte() }
        var tokenVerified = false
        val adbd = Adbd()

        val server = TlsTestServer(key) { input, output, _ ->
            fun head(cmd: Int, arg0: Int, arg1: Int, data: ByteArray): ByteArray {
                var sum = 0L
                for (b in data) sum += (b.toInt() and 0xFF)
                return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size)
                    .putInt(sum.toInt()).putInt(cmd.inv()).array()
            }
            val cnxn = readRaw(input)
            assertEquals(AdbClient.CMD_CNXN, cnxn.cmd)
            // a non-TLS adbd challenges us before answering with CNXN - replay that here
            output.write(head(AdbClient.CMD_AUTH, AdbClient.AUTH_TOKEN, 0, token))
            output.write(token)
            output.flush()
            val auth = readRaw(input)
            assertEquals(AdbClient.CMD_AUTH, auth.cmd)
            assertEquals(AdbClient.AUTH_SIGNATURE, auth.arg0)
            assertEquals(256, auth.data.size) // RSA-2048 PKCS#1 v1.5 signature
            val sig = Signature.getInstance("NONEwithRSA")
            sig.initVerify(key.certificate.publicKey)
            sig.update(digestInfo(token))
            tokenVerified = sig.verify(auth.data)
            // continue the normal flow with the CNXN we already consumed
            adbd.handle(cnxn, input, output)
        }
        try {
            val client = AdbClient(key)
            client.shell("127.0.0.1", server.port, "x")
            assertTrue("signature did not verify", tokenVerified)
        } finally {
            server.close()
        }
    }
}
