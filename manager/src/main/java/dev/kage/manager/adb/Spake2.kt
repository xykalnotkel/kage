package dev.kage.manager.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A faithful Java port of the SPAKE2 protocol that adb wireless pairing uses, i.e. BoringSSL
 * `crypto/curve25519/spake25519.cc` (The BoringSSL Authors, Apache 2.0).
 *
 * Protocol shape (must match adbd byte for byte):
 *  - group: edwards25519, fixed generator points M (Alice) / N (Bob) baked into BoringSSL;
 *  - private key: 64 random bytes reduced mod the group order, then multiplied by 8 (cofactor);
 *  - password scalar: SHA-512(password) reduced mod the group order, then adjusted to a
 *    multiple of 8 by adding order/2*order/4*order when the low bits are set ("password
 *    scalar hack" - the C code adds prime-order multiples so the mask point lands in the
 *    prime-order subgroup);
 *  - my message: [private]G + [passwordScalar]M(or N), 32-byte Ed25519 encoding;
 *  - shared secret: [private](theirMsg - [passwordScalar]N(or M)), then
 *    SHA-512 over 8-byte little-endian length-prefixed: names, both messages, the shared
 *    point encoding, and the full password hash.
 *
 * adb instantiates the client role (Alice) with names "adb pair client"/"adb pair server"
 * including their NUL terminators (16 bytes each) - see pairing_auth/pairing_auth.cpp.
 */
internal class Spake2 private constructor(
    private val aliceRole: Boolean,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    private val random: SecureRandom,
    /** test hook: when set, replaces the random 64-byte pre-reduction private entropy */
    private val privateSeedOverride: ByteArray? = null,
) {

    private var myMsg = ByteArray(0)
    private var privateScalar = BigInteger.ZERO
    private var passwordScalar = BigInteger.ZERO
    private var passwordHash = ByteArray(0)
    private var done = false

    init {
        require(myName.size <= 255 && theirName.size <= 255)
    }

    /** Returns this party's 32-byte SPAKE2 message (x25519_ge_tobytes output). */
    fun generateMsg(password: ByteArray): ByteArray {
        check(myMsg.isEmpty()) { "generateMsg must be called once" }

        // private_tmp = rand64 -> sc_reduce -> left_shift_3
        val seed = privateSeedOverride ?: ByteArray(64).also { random.nextBytes(it) }
        val reduced = Ed25519Group.reduceScalar(seed)
        val privateEight = Ed25519Group.mulByEight(reduced)
        privateScalar = privateEight

        val pointP = Ed25519Group.scalarMultBase(privateEight)

        // password_tmp = SHA512(password); keep the full hash for the KDF later
        val md = MessageDigest.getInstance("SHA-512")
        passwordHash = md.digest(password)
        val pwScalar = Ed25519Group.reduceScalar(passwordHash.copyOf())
        passwordScalar = adjustPasswordScalar(pwScalar)

        val maskPoint = if (aliceRole) POINT_M else POINT_N
        val mask = Ed25519Group.scalarMult(passwordScalar, maskPoint)
        val pStar = Ed25519Group.add(pointP, mask)

        myMsg = Ed25519Group.encode(pStar)
        return myMsg.copyOf()
    }

    /**
     * Processes the peer's 32-byte message and returns the 64-byte shared key material
     * (SPAKE2_process_msg output). Only valid after [generateMsg].
     */
    fun processMsg(theirMsg: ByteArray): ByteArray {
        check(myMsg.isNotEmpty()) { "generateMsg must run first" }
        check(!done) { "processMsg must be called once" }
        if (theirMsg.size != 32) throw IllegalArgumentException("spake2 msg must be 32 bytes")
        done = true

        val qStar = Ed25519Group.decode(theirMsg)
            ?: throw IllegalArgumentException("peer msg is not a curve point")

        val peerMaskPoint = if (aliceRole) POINT_N else POINT_M
        val peerMask = Ed25519Group.scalarMult(passwordScalar, peerMaskPoint)
        val qExt = Ed25519Group.sub(qStar, peerMask)

        val dh = Ed25519Group.scalarMult(privateScalar, qExt)
        val dhEncoded = Ed25519Group.encode(dh)

        val sha = MessageDigest.getInstance("SHA-512")
        if (aliceRole) {
            withLengthPrefix(sha, myName)
            withLengthPrefix(sha, theirName)
            withLengthPrefix(sha, myMsg)
            withLengthPrefix(sha, theirMsg)
        } else {
            withLengthPrefix(sha, theirName)
            withLengthPrefix(sha, myName)
            withLengthPrefix(sha, theirMsg)
            withLengthPrefix(sha, myMsg)
        }
        withLengthPrefix(sha, dhEncoded)
        withLengthPrefix(sha, passwordHash)
        return sha.digest()
    }

    private fun adjustPasswordScalar(pw: BigInteger): BigInteger {
        // Mirrors the C code exactly: the low bits are tested on the *running* value,
        // adding L, 2L, 4L in sequence so the final scalar is a multiple of 8.
        var s = pw
        if (s.testBit(0)) s = s.add(L)
        if (s.testBit(1)) s = s.add(L.shiftLeft(1))
        if (s.testBit(2)) s = s.add(L.shiftLeft(2))
        return s
    }

    private fun withLengthPrefix(sha: MessageDigest, data: ByteArray) {
        val len = ByteArray(8)
        var l = data.size.toLong()
        for (i in 0 until 8) {
            len[i] = (l and 0xff).toByte()
            l = l ushr 8
        }
        sha.update(len)
        sha.update(data)
    }

    companion object {
        private val L = Ed25519Group.L

        // Encoded points from spake25519.cc:
        //   N: 10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778
        //   M: 5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e
        private val POINT_N: Ed25519Group.Point =
            Ed25519Group.decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))
                ?: throw IllegalStateException("bad N point")
        private val POINT_M: Ed25519Group.Point =
            Ed25519Group.decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))
                ?: throw IllegalStateException("bad M point")

        internal val NAME_CLIENT = "adb pair client".toByteArray(Charsets.UTF_8) + 0
        internal val NAME_SERVER = "adb pair server".toByteArray(Charsets.UTF_8) + 0

        fun newClient(random: SecureRandom = SecureRandom(), privateSeed: ByteArray? = null): Spake2 =
            Spake2(aliceRole = true, NAME_CLIENT, NAME_SERVER, random, privateSeed)

        fun newServer(random: SecureRandom = SecureRandom(), privateSeed: ByteArray? = null): Spake2 =
            Spake2(aliceRole = false, NAME_SERVER, NAME_CLIENT, random, privateSeed)

        private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
