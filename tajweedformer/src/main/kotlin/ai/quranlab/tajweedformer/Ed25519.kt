package ai.quranlab.tajweedformer

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature verification (RFC 8032, section 5.1.7), in plain Kotlin so it runs on every
 * Android version the SDK supports (the platform's own Ed25519 arrives only with API 33). It
 * verifies model manifests once per load, so it favours being short and obviously the RFC's
 * reference arithmetic over speed.
 */
internal object Ed25519 {
    private val TWO: BigInteger = BigInteger.valueOf(2) // BigInteger.TWO is Java 9, Android API 33
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(inv(BigInteger.valueOf(121666))).mod(P)
    private val SQRT_M1: BigInteger = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
    private val G: Array<BigInteger> = run {
        val y = BigInteger.valueOf(4).multiply(inv(BigInteger.valueOf(5))).mod(P)
        val x = recoverX(y, 0)!!
        arrayOf(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun inv(x: BigInteger): BigInteger = x.modPow(P.subtract(TWO), P)

    private fun add(a: Array<BigInteger>, b: Array<BigInteger>): Array<BigInteger> {
        val aa = a[1].subtract(a[0]).multiply(b[1].subtract(b[0])).mod(P)
        val bb = a[1].add(a[0]).multiply(b[1].add(b[0])).mod(P)
        val c = TWO.multiply(a[3]).multiply(b[3]).multiply(D).mod(P)
        val d = TWO.multiply(a[2]).multiply(b[2]).mod(P)
        val e = bb.subtract(aa)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = bb.add(aa)
        return arrayOf(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun mul(s: BigInteger, p: Array<BigInteger>): Array<BigInteger> {
        var q = arrayOf(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        var pp = p
        for (i in 0 until s.bitLength()) {
            if (s.testBit(i)) q = add(q, pp)
            pp = add(pp, pp)
        }
        return q
    }

    private fun equal(a: Array<BigInteger>, b: Array<BigInteger>): Boolean =
        a[0].multiply(b[2]).subtract(b[0].multiply(a[2])).mod(P).signum() == 0 &&
            a[1].multiply(b[2]).subtract(b[1].multiply(a[2])).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val x2 = y.multiply(y).subtract(BigInteger.ONE).multiply(inv(D.multiply(y).multiply(y).add(BigInteger.ONE))).mod(P)
        if (x2.signum() == 0) return if (sign != 0) null else BigInteger.ZERO
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
        return x
    }

    private fun littleEndian(b: ByteArray, from: Int, to: Int): BigInteger =
        BigInteger(1, b.copyOfRange(from, to).reversedArray())

    private fun decompress(b: ByteArray): Array<BigInteger>? {
        if (b.size != 32) return null
        var y = littleEndian(b, 0, 32)
        val sign = if (y.testBit(255)) 1 else 0
        y = y.clearBit(255)
        val x = recoverX(y, sign) ?: return null
        return arrayOf(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    /** True when [signature] (64 bytes) is [publicKey]'s (32 raw bytes) Ed25519 signature of [message]. */
    @JvmStatic
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val r = decompress(rBytes) ?: return false
        val s = littleEndian(signature, 32, 64)
        if (s >= L) return false
        val sha = MessageDigest.getInstance("SHA-512")
        sha.update(rBytes)
        sha.update(publicKey)
        sha.update(message)
        val h = BigInteger(1, sha.digest().reversedArray()).mod(L)
        return equal(mul(s, G), add(r, mul(h, a)))
    }
}
