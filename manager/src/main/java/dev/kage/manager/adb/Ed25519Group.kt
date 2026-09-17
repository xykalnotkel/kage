package dev.kage.manager.adb

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Edwards25519 point arithmetic for the SPAKE2 protocol used by adb wireless pairing.
 *
 * The reference algorithm is BoringSSL `crypto/curve25519/spake25519.cc` (Apache 2.0, The
 * BoringSSL Authors), which is what adbd links against. Points are handled in extended
 * homogeneous coordinates (X, Y, Z, T) with the RFC 8032 "unified" addition formulas, which
 * also work for doubling, so a plain double-and-add ladder is enough here - pairing runs a
 * handful of these operations, not thousands, and clarity beats speed for a from-scratch port.
 *
 * External representation is the standard Ed25519 32-byte encoding: y as little-endian with
 * the sign of x in the top bit.
 */
internal object Ed25519Group {

    val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))

    /** -121665 * inv(121666) mod p */
    val D: BigInteger = BigInteger.valueOf(-121665L)
        .multiply(BigInteger.valueOf(121666L).modInverse(P))
        .mod(P)

    /** order of the prime-order subgroup: 2^252 + 27742317777372353535851937790883648493 */
    val L: BigInteger = BigInteger.TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    /** sqrt(-1) mod p, used when recovering x from y while decoding */
    private val SQRT_M1: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4L)), P)

    /** standard base point, y = 4/5 */
    private val BASE_Y: BigInteger = BigInteger.valueOf(4L)
        .multiply(BigInteger.valueOf(5L).modInverse(P))
        .mod(P)

    class Point internal constructor(internal val x: BigInteger, internal val y: BigInteger) {
        internal fun isIdentity(): Boolean = y == BigInteger.ZERO && x == BigInteger.ZERO

        companion object {
            val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE)
        }
    }

    /** Encoded base point: 58 66 66 ... 66 */
    val BASE_POINT: Point = tryRecoverPoint(BASE_Y, false)
        ?: throw IllegalStateException("base point recovery failed")

    /**
     * Decodes a 32-byte Ed25519 point encoding. Returns null when the bytes are not a point
     * on the curve (the caller must fail, exactly like x25519_ge_frombytes_vartime).
     */
    fun decode(encoded: ByteArray): Point? {
        if (encoded.size != 32) return null
        val copy = encoded.copyOf()
        val xSign = ((copy[31].toInt() and 0xFF) shr 7) == 1
        copy[31] = (copy[31].toInt() and 0x7F).toByte()
        val y = littleEndian(copy)
        if (y >= P) return null
        return tryRecoverPoint(y, xSign)
    }

    /** Standard 32-byte encoding: y little-endian, top bit = sign of x. */
    fun encode(point: Point): ByteArray {
        val out = toLittleEndian32(point.y)
        val xOdd = point.x.testBit(0)
        val signBitSet = (out[31].toInt() and 0x80) != 0
        if (xOdd != signBitSet) {
            out[31] = (out[31].toInt() or 0x80).toByte()
        }
        return out
    }

    fun add(a: Point, b: Point): Point {
        // RFC 8032 5.1.4, unified formulas in affine form (BigInteger, no timing guarantees)
        val x1 = a.x
        val y1 = a.y
        val x2 = b.x
        val y2 = b.y

        val dx = x2.multiply(x1).mod(P)
        val dy = y2.multiply(y1).mod(P)
        val dxy = dx.multiply(dy).multiply(D).mod(P)

        val x3Num = x1.multiply(y2).add(x2.multiply(y1))
        val x3Den = BigInteger.ONE.add(dxy)
        val y3Num = y1.multiply(y2).add(dx)
        val y3Den = BigInteger.ONE.subtract(dxy).mod(P)

        return Point(
            x3Num.multiply(x3Den.modInverse(P)).mod(P),
            y3Num.multiply(y3Den.modInverse(P)).mod(P)
        )
    }

    fun sub(a: Point, b: Point): Point = add(a, Point(b.x.negate().mod(P), b.y))

    fun scalarMult(k: BigInteger, point: Point): Point {
        // no reduction mod L: SPAKE2 scalars (the adjusted password mask) may exceed L by
        // design and the C reference multiplies by them unreduced - points like N carry a
        // small-order component, so multiples of L do NOT vanish
        var result = Point.IDENTITY
        var base = point
        var n = k
        while (n.signum() > 0) {
            if (n.testBit(0)) result = add(result, base)
            base = add(base, base)
            n = n.shiftRight(1)
        }
        return result
    }

    fun scalarMultBase(k: BigInteger): Point = scalarMult(k, BASE_POINT)

    /** Reduces a little-endian 64-byte value mod L (mirrors x25519_sc_reduce). */
    fun reduceScalar(le64: ByteArray): BigInteger = littleEndian(le64).mod(L)

    /** Encodes a scalar as 32 bytes little-endian (the format the C code stores scalars in). */
    fun scalarToLittleEndian(v: BigInteger): ByteArray {
        val out = ByteArray(32)
        var n = v
        for (i in 0 until 32) {
            out[i] = n.and(BigInteger.valueOf(0xFFL)).toByte()
            n = n.shiftRight(8)
        }
        return out
    }

    /** scalar * 8, little-endian shift (mirrors left_shift_3). */
    fun mulByEight(v: BigInteger): BigInteger = v.shiftLeft(3)

    private fun tryRecoverPoint(y: BigInteger, xSign: Boolean): Point? {
        if (y.signum() < 0 || y >= P) return null
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = y2.multiply(D).add(BigInteger.ONE).mod(P)
        if (v.signum() == 0) return null

        // RFC 8032 5.1.3: x = (u/v)^((p+3)/8); v*x^2 must equal +/- u, multiply by
        // sqrt(-1) for the negative case, otherwise the bytes are not a curve point
        val uv = u.multiply(v.modInverse(P)).mod(P)
        var x = uv.modPow(P.add(BigInteger.valueOf(3L)).divide(BigInteger.valueOf(8L)), P)
        var check = v.multiply(x).multiply(x).mod(P).subtract(u).mod(P)
        if (check.signum() != 0) {
            check = v.multiply(x).multiply(x).mod(P).add(u).mod(P)
            if (check.signum() != 0) return null
            x = x.multiply(SQRT_M1).mod(P)
        }
        if (x.signum() == 0 && xSign) return null
        if (x.testBit(0) != xSign) {
            x = x.negate().mod(P)
        }
        val point = Point(x, y)
        // sanity: -x^2 + y^2 == 1 + d*x^2*y^2
        val x2 = x.multiply(x).mod(P)
        val lhs = y2.subtract(x2).mod(P)
        val rhs = x2.multiply(y2).multiply(D).add(BigInteger.ONE).mod(P)
        return if (lhs == rhs) point else null
    }

    private fun littleEndian(bytes: ByteArray): BigInteger {
        val be = ByteArray(bytes.size)
        for (i in bytes.indices) be[bytes.size - 1 - i] = bytes[i]
        return BigInteger(1, be)
    }

    private fun toLittleEndian32(v: BigInteger): ByteArray {
        val out = ByteArray(32)
        var n = v.mod(P)
        for (i in 0 until 32) {
            out[i] = n.and(BigInteger.valueOf(0xFFL)).toByte()
            n = n.shiftRight(8)
        }
        return out
    }

    /** Only for tests: expose a secure random. */
    fun randomScalar(random: SecureRandom): BigInteger {
        val raw = ByteArray(64)
        random.nextBytes(raw)
        return reduceScalar(raw)
    }
}
