package ai.quranlab.tajweedformer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * What the four SDKs share and a developer sees: the public types (NAMING.md section 8, so the surface cannot grow
 * back unnoticed, and no scorer symbol is left), the error catalogue (the contract's errors.json, embedded byte for
 * byte) and the names of `vectors30/names.json` (heads, events, reasons, options, the fields of the output types).
 */
class PublicContractTest : OpenChecked() {
    /** The repository's root: the nearest folder above the tests' working directory that holds settings.gradle.kts. */
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** The library's sources, every source set, and the Compose module's. */
    private fun sources(): List<File> = listOf("tajweedformer/src/main/kotlin", "tajweedformer/src/android/kotlin", "tajweedformer/src/jvm/kotlin",
        "tajweedformer-compose/src/main/kotlin").flatMap { d -> File(root, d).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    @Suppress("UNCHECKED_CAST")
    private val names: Map<String, Any?> by lazy {
        Json.parse(javaClass.getResourceAsStream("/vectors30/names.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>
    }

    private fun camel(enumName: String): String =
        enumName.lowercase().split("_").mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar { it.uppercase() } }.joinToString("")

    @Test
    fun thePublicTypesAreThoseOfTheNamingDocument() {
        val type = Regex("^public (?:(?:data|sealed|abstract|open|enum|annotation|fun|value) )*(?:class|object|interface) (\\w+)")
        val found = sources().flatMap { f -> f.readLines().mapNotNull { type.find(it)?.groupValues?.get(1) } }.sorted()
        val naming = listOf("TajweedClient", "TajweedModel", "TajweedSession", "TajweedHeads", "TajweedBackend",
            "TajweedClientOptions", "TajweedSessionOptions",
            "TajweedChunk", "TajweedDecoded", "TajweedToken", "TajweedWord", "TajweedFrame", "TajweedHead", "TajweedEvent",
            "TajweedTokens", "TajweedCalibration", "TajweedReference", "TajweedClip",
            "TajweedSharing", "TajweedAnalytics", "TajweedDiagnosis",
            "TajweedError", "Microphone")
        // the sharing UI components: the Views' theme and settings rows, the Compose style
        val ui = listOf("SharingTheme", "SharingSettingsView", "SharingStyle")
        assertEquals((naming + ui).sorted(), found)
        // the public functions outside a type: the Client's two factories and the two Compose pieces
        val fn = Regex("^public (?:suspend )?fun (?:<[^>]+> )?(\\w+)\\(")
        assertEquals(listOf("SharingSettings", "SharingSheetHost", "TajweedClient", "TajweedClient"),
            sources().flatMap { f -> f.readLines().mapNotNull { fn.find(it)?.groupValues?.get(1) } }.sorted())
        // nothing is public by default: explicit API mode in every library module
        for (m in listOf("tajweedformer", "tajweedformer-jvm", "tajweedformer-compose")) {
            assertTrue("$m is not in explicit API mode", "explicitApi()" in File(root, "$m/build.gradle.kts").readText())
        }
    }

    /** docs/api.md (scripts/make_api_doc.py writes it from the sources) names every public declaration. */
    @Test
    fun theApiPageNamesEveryPublicDeclaration() {
        val page = File(root, "docs/api.md").readText()
        val declared = Regex("""^\s*(?:@\w+(?:\([^)]*\))? )*public (?:[a-z]+ )*(?:class|object|interface|fun|val|var) (?:<[^>]+> )?(\w+)""")
        val missing = sources().flatMap { f -> f.readLines().mapNotNull { declared.find(it)?.groupValues?.get(1) } }.toSortedSet()
            .filter { name -> !Regex("""\b""" + name + """\b""").containsMatchIn(page) }
        assertEquals("public names that docs/api.md lacks (run scripts/make_api_doc.py)", emptyList<String>(), missing)
    }

    @Test
    fun noScorerSymbolIsLeft() {
        // scoring words in any case, and the 2.x type and call names as they were spelled (the calibration's own
        // words, a site's class and a preset, are data and stay, and `reportChecks` takes the app's own flagged count)
        val scorer = Regex("(?i:scor(e|er|ing)\\b|verdict|flagged(Sites|Words))|TajweedSettings|TajweedResult|WordScore|\\bSiteClass\\b|finishAndScore|expectedTokens|expectedWords")
        val hits = sources().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l ->
                val t = l.trim()
                if ((t.startsWith("public ") || t.startsWith("import ")) && scorer.containsMatchIn(t)) "${f.name}:${i + 1}: ${t.take(120)}" else null
            }
        }
        assertEquals("public declarations that name the scorer", emptyList<String>(), hits)
        // the JNI layer calls no scoring, result or settings function of the engine (the 3.0 engine has none)
        val jni = File(root, "native").walkTopDown().filter { it.isFile && (it.extension == "c" || it.extension == "h") && "third_party" !in it.path }
            .flatMap { f -> Regex("zf_tajweed_(score|result|config|expected)\\w*").findAll(f.readText()).map { "${f.name}: ${it.value}" } }.toList()
        assertEquals(emptyList<String>(), jni)
        assertTrue("the scorer's own native build is gone", !File(root, "native/scorer").exists())
    }

    @Test
    fun theErrorCatalogueIsTheContracts() {
        val bytes = javaClass.getResourceAsStream("/ai/quranlab/tajweedformer/errors.json")!!.use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        // recorded from the contract's errors.json; the
        // copy in the SDK is never typed by hand
        assertEquals("04b264b9d820f865a0bfd4d695e17a07b2ae6b1be318324c91f3177d322cd510", sha)
        // where the contract folder is at hand (not on CI), byte for byte
        val contract = (System.getProperty("TJ_ERRORS_JSON") ?: "").let(::File)
        if (contract.isFile) assertArrayEquals("the embedded catalogue differs from ${contract.path}", contract.readBytes(), bytes)

        @Suppress("UNCHECKED_CAST")
        val codes = names["error_codes"] as List<String>
        assertEquals(26, codes.size)
        assertEquals(codes, ErrorCatalogue.codes)
        // `runtime_unsupported` names where the SDK runs, from the one constant, which says what the build and the
        // README say: the library's minSdk and its ABIs
        val unsupported = RecognizerException.RuntimeUnsupported("no library for x86")
        assertEquals("The SDK cannot run here: no library for x86.", unsupported.message)
        assertEquals("Run the app where the SDK is supported: Android 8.0 (API 26) or later on arm64-v8a, armeabi-v7a or x86_64.", unsupported.fix)
        val gradle = File(root, "tajweedformer/build.gradle.kts").readText()
        assertTrue("minSdk", "minSdk = 26" in gradle)
        assertTrue("the library's abiFilters are SdkInfo.ABIS", "abiFilters += listOf(${SdkInfo.ABIS.joinToString(", ") { "\"$it\"" }})" in gradle)
        // the README says it in the same words (the public README's "Before you start", the private one's "Who it
        // is for"): the Android version of the constant, and each ABI
        val readme = File(root, "README.md").readText()
        for (part in listOf(SdkInfo.MIN_ANDROID) + SdkInfo.ABIS) assertTrue("README.md does not name $part", part in readme)
        // and it says plainly what x86_64 is for, in one sentence that is the same in both READMEs
        val said = readme.replace(Regex("\\s+"), " ")
        assertTrue("README.md does not say what x86_64 is for", "`x86_64` is for the Android emulator on an Intel or AMD PC and for x86 Chromebooks: " +
            "there the SDK runs its CPU engine only, never an accelerator." in said)
        // the code makes the sentence true: an x86 process has no SoC id, so no accelerator build is ever looked for
        for (arch in listOf("x86_64", "amd64", "x86", "i686", " X86_64 ")) {
            assertTrue(arch, SocIds.isX86(arch))
            for (soc in listOf("tensor_g5", "exynos2600", "exynos2500")) assertEquals("$soc on $arch", null, SocIds.onArch(soc, arch))
        }
        for (arch in listOf("aarch64", "armv8l", "armv7l", "arm", null)) {
            assertEquals("$arch", false, SocIds.isX86(arch))
            assertEquals("tensor_g5", SocIds.onArch("tensor_g5", arch))
            assertEquals(null, SocIds.onArch(null, arch))
        }
        for (code in codes) {
            val e = TajweedError(code)
            assertEquals(code, e.code)
            assertTrue("$code has no message", e.message.isNotBlank() && e.message != code)
            assertTrue("$code has no fix", e.fix.isNotBlank())
            assertEquals("${names["error_docs_base"]}$code", e.docs)
        }
        assertEquals(listOf("code", "message", "fix", "docs"), names["error_fields"])
        // every failure the SDK raises carries one of the 26 codes
        val raised = sources().flatMap { f -> Regex("(?:RecognizerException|TajweedError)\\(\"([a-z_]+)\"").findAll(f.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue("the SDK raises no code at all?", raised.size >= 15)
        assertEquals("codes raised that the catalogue does not have", emptySet<String>(), raised - codes.toSet())
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun theSharedNames() {
        // heads: name, kind, frame length, classes
        val heads = (names["heads"] as List<Map<String, Any?>>).map { listOf(it["name"], it["kind"], (it["frameMs"] as Double).toInt(), (it["classes"] as Double).toInt()) }
        assertEquals(heads, HeadId.entries.map { listOf(it.key, if (it.kind == HeadKind.LOG_PROBS) "ctc" else "attribute", it.frameMs, it.width) })
        assertEquals(names["default_heads"], HeadId.entries.filter { it in Recognizer.DEFAULT_HEADS }.map { it.key })
        assertEquals("all", names["all_heads_keyword"])
        assertEquals(HeadId.entries.toSet(), TajweedSessionOptions(heads = listOf("all")).headSet())
        // events and why a take ends
        assertEquals(names["ended_reasons"], TajweedEvent.Ended.Reason.entries.map { camel(it.name) })
        assertEquals(names["interrupted_causes"], TajweedEvent.Interrupted.Cause.entries.map { camel(it.name) })
        assertEquals((names["events"] as List<Map<String, Any?>>).map { it["name"] },
            listOf("SpeechStarted", "SpeechEnded", "Interrupted", "Resumed", "BackendChanged", "Ended").map { it.replaceFirstChar { c -> c.lowercase() } })
        val nested = TajweedEvent::class.java.declaredClasses.map { it.simpleName }.toSet()
        assertEquals(setOf("SpeechStarted", "SpeechEnded", "Interrupted", "Resumed", "BackendChanged", "Ended"), nested)
        // the Backend
        val backend = names["backend"] as Map<String, Any?>
        assertEquals(backend["kind"], TajweedBackend.Kind.entries.map { it.name.lowercase() })
        assertEquals(backend["stage"], TajweedBackend.Stage.entries.map { it.name.lowercase() })
        // options
        val session = names["session_options"] as Map<String, Any?>
        assertEquals(session["backend"], TajweedSessionOptions.Backend.entries.map { camel(it.name) })
        assertEquals(session["pace"], TajweedSessionOptions.Pace.entries.map { camel(it.name) })
        assertEquals((session["sampleRate_default"] as Double).toInt(), TajweedSessionOptions().sampleRate)
        assertEquals((session["topK_default"] as Double).toInt(), TajweedSessionOptions().topK)
        assertEquals(null, TajweedSessionOptions().endOnSilenceMs)
        val speech = names["speech"] as Map<String, Any?>
        assertEquals("the heads a frame is active on", speech["active"], SpeechFrames.HEADS.map { it.key })
        val silence = session["endOnSilenceMs"] as Map<String, Any?>
        for (edge in listOf("min", "max")) TajweedSessionOptions(endOnSilenceMs = (silence[edge] as Double).toInt()).validate()
        assertEquals(listOf(500, 60_000), listOf("min", "max").map { (silence[it] as Double).toInt() })
        assertEquals((speech["default_window_ms"] as Double).toInt(), TajweedSession.DEFAULT_SILENCE_MS)
        // the fields of the output types are public properties of those names
        fun props(c: Class<*>) = c.methods.map { it.name }.filter { it.startsWith("get") || it.startsWith("is") }
            .map { n -> if (n.startsWith("is")) n else n.removePrefix("get").replaceFirstChar { it.lowercase() } }.toSet()
        for ((key, c) in listOf("chunk_fields" to TajweedChunk::class.java, "decoded_fields" to TajweedDecoded::class.java,
            "token_fields" to TajweedToken::class.java, "head_fields" to TajweedHead::class.java)) {
            val want = names[key] as List<String>
            assertEquals("$key missing on ${c.simpleName}", emptyList<String>(), want.filter { it !in props(c) })
        }
        // diagnose(): the fields in order, and the words it uses
        val diagnosis = names["diagnosis"] as Map<String, Any?>
        val d = TajweedDiagnosis("3.0.0", null, null, null, "none", null, null, false, null, null, "none", emptyMap(), null, 0, "unknown")
        // the eleven fields, then the optional ones this SDK gives
        assertEquals((diagnosis["fields"] as List<String>) + (diagnosis["optional_fields"] as List<String>), d.toMap().keys.toList())
        assertEquals(diagnosis["downloadProtocol"], TajweedClient.DOWNLOAD_PROTOCOLS)
        assertEquals(diagnosis["microphone"], MicPermission.entries.map { it.key })
        assertEquals(diagnosis["connection"], TajweedClient.CONNECTIONS)
    }
}
