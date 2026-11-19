package ai.quranlab.tajweedformer.sharing

import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.OpenChecked
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/**
 * The cross-SDK sharing vectors (copies in test resources): the Ogg muxer byte for byte, the deletion
 * message and its signatures, the recovery codes, the libopus encode vector of the web and C SDKs, and
 * since 2.0.1 the adaptive bitrate ladder and the custom consent wording.
 */
class SharingVectorsTest : OpenChecked() {
    @Suppress("UNCHECKED_CAST")
    private fun json(path: String): Map<String, Any?> =
        Json.parse(javaClass.getResourceAsStream("/sharing_vectors/$path")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun b64(s: String) = Base64.getUrlDecoder().decode(s.trimEnd('='))

    /** Packet i: length 3 + (i*37 % 290), byte j = (i*31 + j*7 + 1) & 255; 3750 packets; final granule from the vector. */
    private class VectorEncoder(override val preSkip: Int, override val finalGranulePosition: Long, val tail: List<ByteArray>) : OpusEncoding {
        override fun encode(pcm: FloatArray, offset: Int, length: Int): List<ByteArray> = emptyList()
        /** As libopus does, the last packets (its lookahead) come out at the flush. */
        override fun flush(): List<ByteArray> = tail
    }

    private fun packet(p: Int) = ByteArray(3 + (p * 37 % 290)) { j -> ((p * 31 + j * 7 + 1) and 255).toByte() }

    @Suppress("UNCHECKED_CAST")
    @Test fun oggMuxerVectorByteForByte() {
        val v = json("ogg/expected.json")
        val input = v["input"] as Map<String, Any?>
        val n = (input["packets"] as Double).toInt()
        val dir = OggTestKit.tempDir()
        try {
            val closed = ArrayList<OggSegment>()
            val w = OggOpusWriter(
                { File(dir, ShareFiles.name(it) + ".opus") },
                (input["plan_seconds"] as List<Double>).map { it.toInt() },
                (input["input_rate"] as Double).toInt(),
                serial = (input["serial"] as Double).toLong().toInt(),
                encoder = VectorEncoder((input["pre_skip"] as Double).toInt(), (input["final_granule"] as Double).toLong(),
                    (n - 7 until n).map(::packet)),
                onSegment = { closed.add(it) },
                vendor = input["vendor"] as String,
            )
            // fed in uneven batches, as an encoder hands them over
            var i = 0
            val rnd = java.util.Random(1)
            while (i < n - 7) {
                val k = minOf(1 + rnd.nextInt(7), n - 7 - i)
                w.appendPackets((i until i + k).map(::packet))
                i += k
            }
            w.finish()
            val segs = v["segments"] as List<Map<String, Any?>>
            assertEquals(segs.map { (it["n"] as Double).toInt() }, closed.map { it.n })
            for (s in segs) {
                val f = File(dir, s["file"] as String)
                val b = f.readBytes()
                assertEquals(s["file"] as String, (s["bytes"] as Double).toLong(), b.size.toLong())
                assertEquals(s["file"] as String, s["sha256"], sha(b))
                val pages = OggTestKit.pages(b)
                val want = s["pages"] as List<Map<String, Any?>>
                assertEquals(want.size, pages.size)
                for ((p, e) in pages.zip(want)) {
                    assertEquals((e["seq"] as Double).toInt(), p.sequence)
                    assertEquals((e["granule"] as Double).toLong(), p.granule)
                    assertEquals((e["flags"] as Double).toInt(), p.flags)
                    assertEquals((e["packets"] as Double).toInt(), p.packets.size)
                }
            }
            println("ogg muxer vector: ${segs.size} segments byte for byte")
        } finally { dir.deleteRecursively() }
    }

    /**
     * The web and C SDKs' encode vector: 3 s of the Swift tests' noise at 48 kHz, pushed at once, serial 42,
     * one file, made by 2.0.0 (its vendor string is passed explicitly). The wasm build and a plain-C x86-64
     * build agree; this checks this SDK's libopus build (on this machine) against it. Prints the result
     * either way; fails only with TJ_REQUIRE_ENCODE_VECTOR=1.
     */
    @Test fun libopusEncodeVector() {
        OggTestKit.requireOpus()
        val f = OggTestKit.temp()
        try {
            val w = OggOpusWriter({ f }, emptyList(), 48_000, serial = 42, vendor = "QuranLab SDK 2.0.0 ${OpusVersion.string}")
            w.append(OggTestKit.noise(144_000))
            w.finish()
            val b = f.readBytes()
            val want = "904c8177e4177f12ba917bd04c7a0ccce6677612780e09811addfced1e51fcf9"
            val got = sha(b)
            val arch = System.getProperty("os.arch")
            println("libopus encode vector on $arch: ${b.size} bytes, sha256 $got (${if (got == want) "EQUAL to" else "DIFFERS from"} the web vector, 68180 bytes)")
            if (System.getenv("TJ_REQUIRE_ENCODE_VECTOR") == "1") {
                assertEquals(68_180, b.size)
                assertEquals(want, got)
            }
            assertEquals("audio packets", 151, OggTestKit.pages(b).drop(2).sumOf { it.packets.size })
        } finally { f.delete() }
    }

    private fun p256(): ECParameterSpec {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        return ap.getParameterSpec(ECParameterSpec::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun deletionSigningVectors() {
        val v = json("signing.json")
        val jwk = v["private_key_jwk"] as Map<String, Any?>
        val spec = p256()
        val kf = KeyFactory.getInstance("EC")
        val x = BigInteger(1, b64(jwk["x"] as String))
        val y = BigInteger(1, b64(jwk["y"] as String))
        val pub = kf.generatePublic(ECPublicKeySpec(ECPoint(x, y), spec)) as java.security.interfaces.ECPublicKey
        val priv = kf.generatePrivate(ECPrivateKeySpec(BigInteger(1, b64(jwk["d"] as String)), spec))
        // the public key as the session start sends it
        val point = ContributorKeys.point(pub)
        assertEquals(v["contributor_key"], Base64.getUrlEncoder().withoutPadding().encodeToString(point))
        assertEquals(v["public_key_uncompressed_b64url"], Base64.getUrlEncoder().withoutPadding().encodeToString(point))
        val cases = v["cases"] as List<Map<String, Any?>>
        assertTrue(cases.size >= 3)
        for (c in cases) {
            val fields = c["fields"] as Map<String, Any?>
            val msg = ContributorKeys.deletionMessage(fields["app"] as String, fields["contributor"] as String, fields["nonce"] as String,
                (fields["timestamp"] as Double).toLong(), fields["action"] as String)
            assertEquals(c["message"], msg)
            val bytes = msg.toByteArray(Charsets.UTF_8)
            assertEquals(c["message_sha256"], sha(bytes))
            // the vector's signatures verify (DER, and r||s)
            val ver = Signature.getInstance("SHA256withECDSA")
            ver.initVerify(pub)
            ver.update(bytes)
            assertTrue("DER signature", ver.verify(b64(c["signature_der_b64url"] as String)))
            val p1363 = Signature.getInstance("SHA256withECDSAinP1363Format")
            p1363.initVerify(pub)
            p1363.update(bytes)
            assertTrue("r||s signature", p1363.verify(b64(c["signature_p1363_b64url"] as String)))
            // and so does one made here, as the Keystore makes it (DER)
            val s = Signature.getInstance("SHA256withECDSA")
            s.initSign(priv)
            s.update(bytes)
            val mine = s.sign()
            ver.initVerify(pub)
            ver.update(bytes)
            assertTrue("own signature", ver.verify(mine))
        }
        println("deletion signing vectors: ${cases.size} messages byte exact, signatures verify")
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun recoveryCodeVectors() {
        val v = json("recovery.json")
        for (c in v["cases"] as List<Map<String, Any?>>) {
            val hex = c["bytes_hex"] as String
            val bytes = ByteArray(16) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            val code = ContributorKeys.recoveryCode(bytes)
            assertEquals(c["code"], code)
            assertEquals(c["normalised"], ContributorKeys.normaliseRecoveryCode(code))
            assertEquals(c["recovery_hash"], ContributorKeys.recoveryHash(c["normalised"] as String))
        }
        for (t in v["typed"] as List<Map<String, Any?>>) {
            val want = t["normalised"] as String?
            if (want == null) assertNull(ContributorKeys.normaliseRecoveryCode(t["typed"] as String))
            else assertEquals(want, ContributorKeys.normaliseRecoveryCode(t["typed"] as String))
        }
    }

    /**
     * The bundled deck 2026-11-19.1 is Quran Lab's, byte for byte (the deck's READY list), and parses in every
     * language; the consent version did not change, and no language has a guardian string.
     */
    @Test fun copyDeck() {
        val want = mapOf(
            "ar" to "1159dff6482b974798d46686855c0ff0b90293f4ee7bc9890b17c76596cfc474",
            "bn" to "ef6ebcac7798a5e3f2c6c6bc930518e1d7290b2a29821dc83865e7c3249ef949",
            "en" to "4011ed13cb1683348cc2d26c8b5a6adf96ebeb4bc5143cb77eb72104d5992d21",
            "fr" to "03b2026cef95cc4707a841d53e08596fa18af343c9e33ee57feffd373b8411f7",
            "id" to "ddd02973988f286e007a9fc5e0a979b16766c43cb6d276a64ea7b59c7f17483f",
            "ms" to "502835016af3430f898b669cb986ac87cbec4aa89fc10849ea33fe1226d93bcb",
            "tr" to "faabab1693a40f2f9c12a94808e0d7840e66094c3cf32db1bf1695fee48008f0",
            "ur" to "9dec0fd78c867a08aac9a641849ab628b441f2dae88102392b1855f9931aee05",
        )
        for ((lang, h) in want) {
            val b = SharingCopy::class.java.getResourceAsStream("/ai/quranlab/tajweedformer/sharing_copy/$lang.json")!!.use { it.readBytes() }
            assertEquals(lang, h, sha(b))
            val c = SharingCopy.forLanguage(lang)
            assertEquals(lang, c.lang)
            assertEquals("2026-11-19.1", c.version)
            assertTrue("$lang: a guardian string", c.keys.none { it.startsWith("sheet.guardian") })
            // the re-ask card's lines, the deck's own: its Not now the sheet's, its button no longer than the sheet's share
            assertTrue("$lang has the card's lines itself", c.hasCard)
            assertEquals("$lang card.not_now", c["sheet.not_now"], c["card.not_now"])
            assertTrue("$lang card.open no longer than sheet.share", c["card.open"].codePointCount(0, c["card.open"].length) <=
                c["sheet.share"].codePointCount(0, c["sheet.share"].length))
            assertEquals("share-2026-11-09", c.consentVersion)
            assertEquals(0, c.deleteDays)
            assertEquals("immediate", c.deleteTiming)
            assertNull(c.consentTextSha256)
            assertEquals(lang in setOf("ar", "ur"), c.rtl)
            for (k in listOf("sheet.title", "sheet.what", "sheet.who", "sheet.control", "sheet.share", "sheet.not_now", "sheet.mistakes",
                "thanks", "settings.toggle", "settings.what", "settings.delete", "what.detail", "delete.confirm", "delete.done", "reask",
                "delete.failed.title", "delete.failed.network", "delete.retry", "recovery.hint", "recovery.reveal", "recovery.copy",
                "recovery.copied", "recovery.keep", "recovery.used", "recovery.invalid", "card.line", "card.open", "card.not_now")) {
                assertTrue("$lang $k", c[k].isNotEmpty())
                assertTrue("$lang $k has a dash", c[k].none { it.code == 0x2013 || it.code == 0x2014 })
                assertFalse("$lang $k: no placeholder left", c.deletion(k).contains("{days}"))
            }
            assertTrue(c.impactMinutes(12).isNotEmpty())
            assertTrue("$lang hadith verified", c.hadith != null && c.hadithRef != null)
        }
        assertEquals("ar", SharingCopy.forLanguage("ar-SA").lang)
        assertEquals("id", SharingCopy.forLanguage("in_ID").lang)
        assertEquals("en", SharingCopy.forLanguage("de").lang)
        assertEquals("You have shared 1 minute of recitation. May Allah reward you.", SharingCopy.ENGLISH.impactMinutes(1))
        assertEquals(listOf("Your recitation today could help another learner tomorrow.", "See how it helps", "Not now"),
            listOf(SharingCopy.ENGLISH["card.line"], SharingCopy.ENGLISH["card.open"], SharingCopy.ENGLISH["card.not_now"]))
        assertEquals("few", SharingCopy.plural("ar", 3))
        assertEquals("many", SharingCopy.plural("ar", 11))
        // a deck of the .1 series (a {days} placeholder, delete_days 30, no delete_timing) still parses and fills it
        val old = SharingCopy.parse("{\"format\":\"quranlab.sharing-copy/1\",\"version\":\"2026-11-07.1\",\"lang\":\"en\",\"dir\":\"ltr\"," +
            "\"consent_version\":\"share-2026-11-07\",\"delete_days\":30,\"strings\":{\"delete.confirm\":\"Deleted within {days} days.\"}}")
        assertEquals("Deleted within 30 days.", old.deletion("delete.confirm"))
        assertNull(old.deleteTiming)
    }

    private companion object {
        /** How the sheets read the who line and the detail of What is shared: the deck's string by its key. */
        const val WHO = "c[\"sheet.who\"]"
        const val DETAIL = "[\"what.detail\"]"
    }

    /**
     * SPEC 11.8: every consent sheet shows the deck's `sheet.who` whole, in each of the eight languages, and "What is
     * shared" shows `what.detail` whole. The text the SDK hands a sheet is the deck's string itself (read here from the
     * deck's own JSON, not through the SDK's parser); both of the SDK's sheets, the Views dialog and the Compose sheet,
     * draw that line in the disclosure of the one sheet every app shows (SDK 4.0, contract section 26).
     */
    @Test fun everySheetShowsTheWhoLineWhole() {
        for (lang in listOf("en", "ar", "id", "ms", "tr", "fr", "ur", "bn")) {
            val b = SharingCopy::class.java.getResourceAsStream("/ai/quranlab/tajweedformer/sharing_copy/$lang.json")!!.use { it.readBytes() }
            @Suppress("UNCHECKED_CAST")
            val strings = (ai.quranlab.tajweedformer.Json.parse(String(b, Charsets.UTF_8)) as Map<String, Any?>)["strings"] as Map<String, String>
            val c = SharingCopy.forLanguage(lang)
            for (k in listOf("sheet.who", "what.detail")) {
                assertTrue("$lang $k", strings[k]!!.isNotEmpty())
                assertEquals("$lang $k: the sheet shows the deck's line whole", strings[k], c[k])
            }
        }
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "settings.gradle.kts").isFile }
        for (path in listOf("tajweedformer/src/android/kotlin/ai/quranlab/tajweedformer/SharingViews.kt",
            "tajweedformer-compose/src/main/kotlin/ai/quranlab/tajweedformer/compose/SharingCompose.kt")) {
            val src = java.io.File(root, path).readText()
            // SDK 4.0: one sheet for every app, whose disclosure draws sheet.who whole
            val sheet = src.substring(listOf("fun consentSheet(", "fun ConsentSheet(").map { src.indexOf(it) }.single { it >= 0 })
            val what = sheet.indexOf("sheet.what")
            val who = sheet.indexOf(WHO)
            assertTrue("$path: the consent sheet does not draw sheet.who in its disclosure", what in 0 until who)
            assertFalse("$path: a sheet of another variant", "guardian" in src)
            // What is shared: the Views dialog draws the detail; the Compose settings open that same dialog
            assertTrue("$path: What is shared does not draw what.detail", DETAIL in src || "SharingDialogs.whatIsShared(" in src)
        }
    }

    /**
     * SPEC 11.8: no string the SDK can show, in any of the eight languages, holds the sentence that deck
     * 2026-11-12.1 added (known only by its length and hash, [DeckFragments]): every string of the bundled deck as the
     * sheets read it (the consent sheet's lines, "What is shared", the settings, the deletion lines
     * with their numbers filled in, the reask, the thank-you), the impact line, and the hadith with its reference.
     */
    @Test fun theSentenceThatDeck2026_11_12_1AddedIsInNoStringTheSdkShows() {
        val added = DeckFragments.addedBy2026_11_12_1
        assertEquals(setOf("en", "ar", "id", "ms", "tr", "fr", "ur", "bn"), added.keys)
        for ((lang, f) in added) {
            assertTrue("$lang: the fragment's numbers", f.length > 20 && f.sha256.matches(Regex("[0-9a-f]{64}")))
            val b = SharingCopy::class.java.getResourceAsStream("/ai/quranlab/tajweedformer/sharing_copy/$lang.json")!!.use { it.readBytes() }
            @Suppress("UNCHECKED_CAST")
            val keys = ((ai.quranlab.tajweedformer.Json.parse(String(b, Charsets.UTF_8)) as Map<String, Any?>)["strings"] as Map<String, String>).keys
            val c = SharingCopy.forLanguage(lang)
            val shown = keys.flatMap { listOf(c[it], c.deletion(it)) } + listOf(1L, 2L, 5L, 12L, 600L).map { c.impactMinutes(it) } +
                listOfNotNull(c.hadith, c.hadithRef)
            for (t in shown) assertFalse("$lang: a string the SDK shows holds the sentence that deck 2026-11-12.1 added", DeckFragments.holds(t, f))
        }
    }

    /** The adaptive bitrate ladder: every case of the shared vector, a fresh ladder each, every step's expected rate. */
    @Suppress("UNCHECKED_CAST")
    @Test fun bitrateLadderVector() {
        val v = json("bitrate_ladder.json")
        assertEquals((v["rungs"] as List<Double>).map { it.toInt() }, BitrateLadder.RUNGS.toList())
        assertEquals((v["start"] as Double).toInt(), BitrateLadder().bitrate)
        assertEquals((v["up_streak"] as Double).toInt(), BitrateLadder.UP_STREAK)
        assertEquals(v["up_max_backlog_s"] as Double, BitrateLadder.UP_MAX_BACKLOG_S, 0.0)
        assertEquals(v["down_backlog_s"] as Double, BitrateLadder.DOWN_BACKLOG_S, 0.0)
        var steps = 0
        val cases = v["cases"] as List<Map<String, Any?>>
        for (c in cases) {
            val l = BitrateLadder()
            for ((i, st) in (c["steps"] as List<Map<String, Any?>>).withIndex()) {
                when (st["event"]) {
                    "upload_failed" -> l.uploadFailed()
                    "upload_ok" -> l.uploadOk(st["backlog_s"] as Double)
                    "segment_closed" -> l.segmentClosed(st["backlog_s"] as Double)
                    else -> throw AssertionError("event ${st["event"]}")
                }
                assertEquals("${c["name"]} step $i", (st["expect"] as Double).toInt(), l.bitrate)
                steps++
            }
        }
        println("bitrate ladder vector: ${cases.size} cases, $steps steps")
    }

    /** Custom consent wording: the canonical text, its SHA-256 and the version of every case; every refused input refused. */
    @Suppress("UNCHECKED_CAST")
    @Test fun consentTextVector() {
        val v = json("consent_text.json")
        val deck = SharingCopy.ENGLISH
        for (c in v["cases"] as List<Map<String, Any?>>) {
            val base = c["base"] as String
            val ov = c["overrides"] as Map<String, String>
            assertEquals(base, deck.consentVersion)
            val worded = deck.withWording(ov)
            assertEquals(c["name"] as String, c["consent_version"], worded.consentVersion)
            assertEquals(c["name"] as String, c["consent_text_sha256"], worded.consentTextSha256)
            val hex = c["canonical_utf8_hex"] as String?
            if (hex == null) {
                assertNull(worded.consentText)
            } else {
                assertEquals(hex, worded.consentText!!.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff) })
                assertEquals(ConsentWording.version(base, ov), worded.consentVersion)
                // the copy shows the app's words
                for ((k, t) in ov) {
                    if (k.startsWith("impact.minutes.")) assertTrue(worded.impactMinutes(if (k.endsWith(".one")) 1 else 7).isNotEmpty())
                    else assertEquals(t, worded[k])
                }
            }
        }
        assertEquals("{minutes} dakika paylast\u0131n\u0131z.".replace("{minutes}", "7"),
            deck.withWording(mapOf("impact.minutes.other" to "{minutes} dakika paylast\u0131n\u0131z.")).impactMinutes(7))
        for (r in v["refused"] as List<Map<String, Any?>>) {
            try {
                deck.withWording(r["overrides"] as Map<String, String>)
                throw AssertionError("accepted: ${r["why"]}")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
        println("consent text vector: ${(v["cases"] as List<*>).size} cases, ${(v["refused"] as List<*>).size} refusals")
    }
}
