package ai.quranlab.tajweedformer

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The server's side of the protected model for the tests, from the
 * contract's TEST keys only (vectors40/: the test signer, the test core root's K_core of release 4.0.0-c1, the test
 * model root's build key of manifest_40.json). No server trusts any of them. It makes what qlab-api makes: binary
 * leases (18.4.6, ECDH and RSA-OAEP wraps), install certificates (18.4.3) and signed refusals (18.4.10); and it reads
 * the vector files. Shared by the JVM tests and the device tests (src/sharedTest), so it uses only what Android 8 has
 * (Ed25519 signing is written out here: the platform's arrives with API 33).
 */
object ProtectedKit {
    @Suppress("UNCHECKED_CAST")
    fun vector(name: String): Map<String, Any?> =
        Json.parse(ProtectedKit::class.java.getResourceAsStream("/vectors40/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    fun b64u(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun unb64u(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trimEnd('='))
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    // ------------------------------------------------------------------ the test keys

    /** The contract's test signer (Ed25519 seed): pinned in the test release 4.0.0-c1 of the Core. */
    @Suppress("UNCHECKED_CAST")
    val signerSeed: ByteArray by lazy { hex((vector("lease.json")["signer"] as Map<String, Any?>)["seed_hex"] as String) }
    val signerPublic: ByteArray by lazy { Ed.publicKey(signerSeed) }

    const val RELEASE = "4.0.0-c1"

    /** K_core of the test release 4.0.0-c1 (hkdf.json). */
    @Suppress("UNCHECKED_CAST")
    val kCore: ByteArray by lazy { hex(parseDict(vector("hkdf.json")["k_core_hex"])[RELEASE]!!) }

    /** The build key of manifest_40.json's key id, model and version (hkdf.json). */
    val buildKey: ByteArray by lazy { hex(vector("hkdf.json")["build_key_hex"] as String) }

    /** The contract's test model root (hkdf.json's inputs): every test build's keys come from it. */
    @Suppress("UNCHECKED_CAST")
    val modelRoot: ByteArray by lazy { hex((vector("hkdf.json")["inputs"] as Map<String, Any?>)["model_root_hex"] as String) }

    /** The build key of any test build (protected40.py build_key: HKDF, salt the key id, ikm the model root), as the
     *  Core's tools/make_test_build.py makes its builds. */
    fun buildKeyFor(keyId: ByteArray, model: String, version: String): ByteArray =
        hkdf(keyId, modelRoot, "quranlab-build-key 1\n$model\n$version\n".toByteArray(Charsets.UTF_8))

    /** hkdf.json writes some fields as Python's repr of a dict: `{'a': 'b', ...}`. */
    fun parseDict(v: Any?): Map<String, String> {
        if (v is Map<*, *>) return v.entries.associate { it.key as String to it.value as String }
        val s = (v as String).trim().removePrefix("{").removeSuffix("}")
        return Regex("'([^']*)':\\s*'([^']*)'").findAll(s).associate { it.groupValues[1] to it.groupValues[2] }
    }

    // ------------------------------------------------------------------ HKDF, AES-GCM

    fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, n: Int = 32): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < n) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(n)
    }

    fun gcm(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(plain)
        }

    // ------------------------------------------------------------------ the lease (18.4.6)

    class LeaseFields(
        val flags: Int = 8, val leaseId: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) },
        val iat: Long, val exp: Long, val refreshAfter: Long,
        val keyId: ByteArray, val build: ByteArray, val installHash: ByteArray, val mark: ByteArray = ByteArray(24),
        val model: String = "v4.0", val version: String = "4.1.0", val release: String = RELEASE,
    )

    /**
     * A signed binary lease of [f] wrapping [buildKey] for [installKeySpki] (P-256: ECDH with [ephPrivate] or a fresh
     * ephemeral key; RSA-2048: OAEP SHA-1 of a fresh KEK, or [rsaKek]), inner layer under [kCore].
     */
    fun lease(f: LeaseFields, installKeySpki: ByteArray, buildKey: ByteArray = this.buildKey, kCore: ByteArray = this.kCore,
              ephPrivate: PrivateKey? = null, ephPublic: ByteArray? = null, rsaKek: ByteArray? = null, seed: ByteArray = signerSeed): ByteArray {
        val rsa = KeyFactory.getInstance("RSA").let { kf -> try { kf.generatePublic(X509EncodedKeySpec(installKeySpki)); true } catch (_: Exception) { false } }
        val head = ByteArrayOutputStream()
        fun u16(v: Int) = head.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        fun u64(v: Long) = head.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) { val b = s.toByteArray(); head.write(b.size); head.write(b) }
        head.write(byteArrayOf(0x51, 0x4C, 0x4C, 0x31))
        u16(1); u16(f.flags)
        head.write(f.leaseId)
        u64(f.iat); u64(f.exp); u64(f.refreshAfter)
        head.write(f.keyId); head.write(f.build); head.write(f.installHash); head.write(f.mark)
        str(f.model); str(f.version); str(f.release)
        val d = sha256(head.toByteArray())
        val inner = gcm(kCore, f.leaseId.copyOf(12), d, buildKey)
        val eph: ByteArray
        val kek: ByteArray
        if (!rsa) {
            val (priv, pub) = if (ephPrivate != null) ephPrivate to ephPublic!! else KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair().let { it.private to point(it.public) }
            }
            eph = pub
            val z = KeyAgreement.getInstance("ECDH").run {
                init(priv)
                doPhase(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(installKeySpki)), true)
                generateSecret()
            }
            kek = hkdf(f.leaseId, z, "quranlab-lease-kek 1\n".toByteArray() + f.installHash + eph)
        } else {
            kek = rsaKek ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            eph = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").run {
                init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(installKeySpki)),
                    javax.crypto.spec.OAEPParameterSpec("SHA-1", "MGF1", java.security.spec.MGF1ParameterSpec.SHA1, javax.crypto.spec.PSource.PSpecified.DEFAULT))
                doFinal(kek)
            }
        }
        val outer = gcm(kek, ByteArray(12), d, inner)
        head.write(if (rsa) 2 else 1)
        u16(eph.size); head.write(eph)
        u16(outer.size); head.write(outer)
        head.write(sha256(Ed.publicKey(seed)).copyOf(8))
        val body = head.toByteArray()
        return body + Ed.sign(seed, "quranlab-lease 1\n".toByteArray() + body)
    }

    /** An uncompressed P-256 point of [pub]. */
    fun point(pub: PublicKey): ByteArray {
        val w = (pub as ECPublicKey).w
        fun b32(x: BigInteger) = x.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        return byteArrayOf(4) + b32(w.affineX) + b32(w.affineY)
    }

    fun ecPrivate(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    // ------------------------------------------------------------------ certificates and refusals

    /** An install certificate (18.4.3): base64url of the text, a dot, base64url of the signature. */
    fun cert(app: String, platform: String, key: String, installHex: String, signHex: String, att: String = "keyattest", hw: Int = 1,
             iat: Long, exp: Long, recheck: Int = 0, batch: String = "-", digest: String = "-", seed: ByteArray = signerSeed): String {
        val text = "quranlab-install-cert 1\napp $app\nplatform $platform\nkey $key\ninstall $installHex\nsign $signHex\natt $att\nhw $hw\n" +
            "iat $iat\nexp $exp\nrecheck $recheck\nbatch $batch\ndigest $digest\n"
        return b64u(text.toByteArray()) + "." + b64u(Ed.sign(seed, text.toByteArray()))
    }

    /** The `signed` field of a signed stop (18.4.10). */
    fun signedRefusal(keyId: String, reason: String, installHex: String, challenge: String, at: Long, seed: ByteArray = signerSeed): String {
        val text = "quranlab-refusal 1\n$keyId\n$reason\n$installHex\n$challenge\n$at\n"
        return b64u(text.toByteArray()) + "." + b64u(Ed.sign(seed, text.toByteArray()))
    }

    // ------------------------------------------------------------------ Ed25519 signing (RFC 8032), for the tests

    /** protected40.py's stream(label, n): SHA-256 of the label and a 32-bit little-endian counter, concatenated. */
    fun stream(label: String, n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (out.size() < n) {
            out.write(sha256(label.toByteArray(Charsets.US_ASCII) + byteArrayOf(i.toByte(), (i shr 8).toByte(), (i shr 16).toByte(), (i shr 24).toByte())))
            i++
        }
        return out.toByteArray().copyOf(n)
    }

    /** A weekly data piece (18.6.7) signed by the test signer, as quranlab-core tools/make_test_pieces.py makes it. */
    fun piece(id: Int, fields: List<Pair<Int, ByteArray>>, seed: ByteArray = signerSeed): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        body.write(1)
        body.write(byteArrayOf(id.toByte(), (id shr 8).toByte(), (id shr 16).toByte(), (id shr 24).toByte()))
        body.write(fields.size)
        for ((tag, v) in fields) {
            body.write(tag)
            body.write(byteArrayOf(v.size.toByte(), (v.size shr 8).toByte()))
            body.write(v)
        }
        val b = body.toByteArray()
        return b + sha256(Ed.publicKey(seed)).copyOf(8) + Ed.sign(seed, "quranlab-piece 1\n".toByteArray() + b)
    }

    /** make_test_pieces.py's pieces by the names piece_order.json uses. */
    val pieces: Map<String, ByteArray> by lazy {
        val p7 = piece(7, listOf(1 to stream("quranlab-test-piece-7", 16), 2 to byteArrayOf(1, 3), 3 to byteArrayOf(64), 9 to "later".toByteArray()))
        val bad = p7.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        mapOf("piece7" to p7, "piece8" to piece(8, listOf(1 to stream("quranlab-test-piece-8", 16))),
            "piece9" to piece(9, listOf(1 to stream("quranlab-test-piece-9", 16))), "piece_bad_sig" to bad)
    }

    object Ed {
        private val TWO = BigInteger.valueOf(2)
        private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        private val L = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
        private val D = BigInteger.valueOf(-121665).multiply(inv(BigInteger.valueOf(121666))).mod(P)
        private val SQRT_M1 = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
        private val G: Array<BigInteger> by lazy {
            val y = BigInteger.valueOf(4).multiply(inv(BigInteger.valueOf(5))).mod(P)
            val x2 = y.multiply(y).subtract(BigInteger.ONE).multiply(inv(D.multiply(y).multiply(y).add(BigInteger.ONE))).mod(P)
            var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
            if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
            if (x.testBit(0)) x = P.subtract(x)
            arrayOf(x, y, BigInteger.ONE, x.multiply(y).mod(P))
        }

        private fun inv(x: BigInteger) = x.modPow(P.subtract(TWO), P)

        private fun add(a: Array<BigInteger>, b: Array<BigInteger>): Array<BigInteger> {
            val aa = a[1].subtract(a[0]).multiply(b[1].subtract(b[0])).mod(P)
            val bb = a[1].add(a[0]).multiply(b[1].add(b[0])).mod(P)
            val c = TWO.multiply(a[3]).multiply(b[3]).multiply(D).mod(P)
            val d = TWO.multiply(a[2]).multiply(b[2]).mod(P)
            val e = bb.subtract(aa); val f = d.subtract(c); val g = d.add(c); val h = bb.add(aa)
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

        private fun encode(p: Array<BigInteger>): ByteArray {
            val zi = inv(p[2])
            val x = p[0].multiply(zi).mod(P)
            val y = p[1].multiply(zi).mod(P)
            val out = le(y)
            if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
            return out
        }

        private fun le(v: BigInteger): ByteArray {
            val b = v.toByteArray().reversedArray()
            return ByteArray(32) { if (it < b.size) b[it] else 0 }
        }

        private fun sha512(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").run { for (p in parts) update(p); digest() }

        private fun secret(seed: ByteArray): Pair<BigInteger, ByteArray> {
            val h = sha512(seed)
            val a = h.copyOf(32)
            a[0] = (a[0].toInt() and 248).toByte()
            a[31] = ((a[31].toInt() and 127) or 64).toByte()
            return BigInteger(1, a.reversedArray()) to h.copyOfRange(32, 64)
        }

        fun publicKey(seed: ByteArray): ByteArray = encode(mul(secret(seed).first, G))

        fun sign(seed: ByteArray, msg: ByteArray): ByteArray {
            val (a, prefix) = secret(seed)
            val pub = encode(mul(a, G))
            val r = BigInteger(1, sha512(prefix, msg).reversedArray()).mod(L)
            val rEnc = encode(mul(r, G))
            val k = BigInteger(1, sha512(rEnc, pub, msg).reversedArray()).mod(L)
            val s = r.add(k.multiply(a)).mod(L)
            return rEnc + le(s)
        }
    }
}
