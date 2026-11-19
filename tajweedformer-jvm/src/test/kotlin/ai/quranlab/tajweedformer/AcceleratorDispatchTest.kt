package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.core.AcceleratorGate
import ai.quranlab.tajweedformer.core.AcceleratorHost
import ai.quranlab.tajweedformer.core.AcceleratorPlan
import ai.quranlab.tajweedformer.core.AcceleratorRecords
import ai.quranlab.tajweedformer.core.Core
import ai.quranlab.tajweedformer.core.CoreException
import ai.quranlab.tajweedformer.core.DispatchLibrary
import ai.quranlab.tajweedformer.core.GoldenRuns
import ai.quranlab.tajweedformer.core.PromotionInfo
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Stage B's NPU follow-ups of a device lab's Pixel 10 run:
 * 1. the vendor's dispatch library: LiteRT lists a folder of files for it, and AGP's default packaging leaves the app's
 *    `nativeLibraryDir` empty, so the SDK resolves the folder itself ([DispatchLibrary]);
 * 2. a golden test that fails in the runtime (the program's first push, `qlc_stream_push: engine`) is a transient
 *    verdict, not a permanent one; so are two prepares the process never came back from, and the runtime's other
 *    device-side refusals; only a fault of the build (a golden mismatch, a program that breaks the host contract) is
 *    off for good;
 * 3. the Core's prepare reports success without a dispatch library: the SDK never prepares without one, and every
 *    verdict names the library it was found with, so an app update that adds it brings the NPU back at once;
 * 4. no program is downloaded that cannot be promoted (no dispatch library, no NPU runtime in the system image, no
 *    self-test set), and never the QLPD deltas the Core does not read.
 */
class AcceleratorDispatchTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private fun temp(): File = Files.createTempDirectory("tj-dispatch-").toFile().also { dirs += it }

    @After
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private val tensor = "libLiteRtDispatch_GoogleTensor.so"
    private val lib = ByteArray(70_000) { (it * 31 + 7).toByte() }

    /** An APK: a zip whose native libraries are stored (uncompressed), as AGP packages them by default. */
    private fun apk(file: File, entries: Map<String, ByteArray>): File {
        ZipOutputStream(file.outputStream()).use { z ->
            for ((n, b) in entries) {
                val e = ZipEntry(n)
                e.method = ZipEntry.STORED
                e.size = b.size.toLong()
                e.compressedSize = b.size.toLong()
                e.crc = CRC32().apply { update(b) }.value
                z.putNextEntry(e)
                z.write(b)
                z.closeEntry()
            }
        }
        return file
    }

    // ---------------------------------------------------------------- 1. the dispatch library's folder

    @Test
    fun theDocumentedSetupHandsTheNativeLibraryFolderAsItIs() {
        // the runtime extracted (useLegacyPackaging = true): LiteRT's own provider's folder
        val native = temp()
        File(native, tensor).writeBytes(lib)
        File(native, "libLiteRt.so").writeBytes(ByteArray(10))
        val r = DispatchLibrary.resolve("tensor_g5", native, emptyList(), "arm64-v8a", temp())
        assertEquals(native, r.folder!!.dir)
        assertFalse(r.folder!!.copied)
        assertTrue(r.id, r.id.startsWith("lib:"))
        assertNull(r.missing)
    }

    @Test
    fun aLibraryLeftInsideTheApkIsCopiedOutOnceIntoAFolderOfItsOwn() {
        // AGP's default packaging: nativeLibraryDir is empty, the library is a stored entry of base.apk
        val native = temp()
        val base = apk(File(temp(), "base.apk"), mapOf("lib/arm64-v8a/$tensor" to lib, "lib/arm64-v8a/libLiteRt.so" to ByteArray(10),
            "lib/armeabi-v7a/$tensor" to ByteArray(5)))
        val cache = File(temp(), "code_cache/quranlab-dispatch")
        val r = DispatchLibrary.resolve("tensor_g4", native, listOf(base), "arm64-v8a", cache)
        val f = r.folder!!
        assertTrue(f.copied)
        assertArrayEquals("the 64-bit library, byte for byte", lib, File(f.dir, tensor).readBytes())
        assertEquals("LiteRT finds exactly one dispatch library there", listOf(tensor), DispatchLibrary.listDispatch(f.dir).map { it.name })
        assertEquals(listOf(tensor), f.dir.list()!!.toList())
        val crc = CRC32().apply { update(lib) }.value
        assertEquals("apk:%08x:%d".format(crc, lib.size), f.id)
        // the next start reuses the copy
        val stamp = File(f.dir, tensor).lastModified()
        Thread.sleep(20)
        val again = DispatchLibrary.resolve("tensor_g4", native, listOf(base), "arm64-v8a", cache)
        assertEquals(f, again.folder)
        assertEquals(stamp, File(f.dir, tensor).lastModified())
    }

    @Test
    fun anInstallTimeFeatureModuleIsLookedForInTheSplits() {
        // LiteRT's google_tensor_runtime module: a split APK installed with the app on Tensor phones
        val base = apk(File(temp(), "base.apk"), mapOf("lib/arm64-v8a/libLiteRt.so" to ByteArray(10)))
        val split = apk(File(temp(), "split_google_tensor_runtime.apk"), mapOf("lib/arm64-v8a/$tensor" to lib))
        val r = DispatchLibrary.resolve("tensor_g3", temp(), listOf(base, split), "arm64-v8a", temp())
        assertArrayEquals(lib, File(r.folder!!.dir, tensor).readBytes())
    }

    @Test
    fun anotherVendorsDispatchLibraryBesideItIsLeftOut() {
        // LiteRT loads the first dispatch library its listing finds: the SoC's goes into a folder alone
        val native = temp()
        File(native, tensor).writeBytes(lib)
        File(native, "libLiteRtDispatch_Qualcomm.so").writeBytes(ByteArray(100))
        val r = DispatchLibrary.resolve("tensor_g5", native, emptyList(), "arm64-v8a", temp())
        val f = r.folder!!
        assertTrue(f.copied)
        assertEquals(listOf(tensor), DispatchLibrary.listDispatch(f.dir).map { it.name })
        // an Exynos phone gets Samsung's, never Google's
        File(native, "libLiteRtDispatch_Samsung.so").writeBytes(ByteArray(200) { 9 })
        val s = DispatchLibrary.resolve("exynos2600", native, emptyList(), "arm64-v8a", temp())
        assertEquals("libLiteRtDispatch_Samsung.so", s.folder!!.library)
        assertEquals(listOf("libLiteRtDispatch_Samsung.so"), DispatchLibrary.listDispatch(s.folder!!.dir).map { it.name })
    }

    @Test
    fun withoutTheLibraryThereIsNoFolder() {
        val base = apk(File(temp(), "base.apk"), mapOf("lib/arm64-v8a/libLiteRt.so" to ByteArray(10),
            "lib/armeabi-v7a/$tensor" to lib))
        val none = DispatchLibrary.resolve("tensor_g5", temp(), listOf(base), "arm64-v8a", temp())
        assertNull(none.folder)
        assertEquals(DispatchLibrary.NONE, none.id)
        assertTrue(none.missing!!, none.missing!!.contains(tensor))
        assertNull("a 32-bit process", DispatchLibrary.resolve("tensor_g5", temp(), listOf(base), null, temp()).folder)
        assertNull("a SoC with no NPU builds", DispatchLibrary.resolve("sm8850", temp(), listOf(base), "arm64-v8a", temp()).folder)
        assertNull("an unreadable APK proves nothing and breaks nothing",
            DispatchLibrary.resolve("tensor_g5", null, listOf(File(temp(), "gone.apk")), "arm64-v8a", temp()).folder)
    }

    /**
     * Stage B (11-18): LiteRT is the app's to add, not a dependency of the AAR. Without its runtime the NPU path is
     * inert before anything is resolved, copied or fetched; with it, where the linker finds it (extracted, or stored
     * in the base APK or a split, as an app that adds `com.google.ai.edge.litert:litert` gets it), the path goes on.
     */
    @Test
    fun withoutLiteRtInTheAppTheNpuPathIsInert() {
        // the AAR alone: the dispatch library could be there, the runtime is not
        val bare = apk(File(temp(), "base.apk"), mapOf("lib/arm64-v8a/$tensor" to lib, "lib/arm64-v8a/libqlcore.so" to ByteArray(10)))
        val why = DispatchLibrary.runtimeMissing(temp(), listOf(bare), "arm64-v8a")
        assertTrue(why!!, why.contains("libLiteRt.so") && why.contains("com.google.ai.edge.litert:litert"))
        // LiteRT for another ABI only is no runtime for this process
        val otherAbi = apk(File(temp(), "base.apk"), mapOf("lib/armeabi-v7a/libLiteRt.so" to ByteArray(10)))
        assertTrue(DispatchLibrary.runtimeMissing(null, listOf(otherAbi), "arm64-v8a") != null)
        // an app that adds LiteRT: stored in its base APK (the default packaging), extracted, or in a split
        val added = apk(File(temp(), "base.apk"), mapOf("lib/arm64-v8a/libLiteRt.so" to ByteArray(10), "lib/arm64-v8a/$tensor" to lib))
        assertNull(DispatchLibrary.runtimeMissing(temp(), listOf(added), "arm64-v8a"))
        val native = temp().also { File(it, "libLiteRt.so").writeBytes(ByteArray(10)) }
        assertNull(DispatchLibrary.runtimeMissing(native, emptyList(), "arm64-v8a"))
        val split = apk(File(temp(), "split_config.arm64_v8a.apk"), mapOf("lib/arm64-v8a/libLiteRt.so" to ByteArray(10)))
        assertNull(DispatchLibrary.runtimeMissing(temp(), listOf(bare, split), "arm64-v8a"))
        // and then the dispatch library resolves as before
        assertTrue(DispatchLibrary.resolve("tensor_g5", temp(), listOf(added), "arm64-v8a", temp()).folder != null)
        // an unreadable APK proves nothing: missing
        assertTrue(DispatchLibrary.runtimeMissing(null, listOf(File(temp(), "gone.apk")), "arm64-v8a") != null)
    }

    @Test
    fun withoutLiteRtTheGateFetchesNothing() {
        // the gate's dispatch answer when the app has no LiteRT (AndroidPlatform.dispatchFolder): nothing is fetched,
        // the reason is said once (load_error), and nothing is held, so an app update that adds LiteRT brings the NPU back
        val noRuntime = DispatchLibrary.Resolution(null, DispatchLibrary.runtimeMissing(temp(), emptyList(), "arm64-v8a"))
        val m = build()
        val records = AcceleratorRecords(File(temp(), "accel.json"))
        val c = choose(m, records, d = noRuntime)
        assertTrue(c.files.isEmpty())
        assertEquals("load_error", c.reason)
        assertTrue(c.why!!, c.why!!.contains("libLiteRt.so"))
        assertFalse(records.held("${m.build}:npu:$soc:$ctx", "4.0.0", "os1", 1_000L, DispatchLibrary.NONE))
        // the app adds LiteRT (and the dispatch library): the next open fetches the program
        assertEquals(listOf("npu-io", "npu-masks", "npu-model"), choose(m, records).files.map { it.role }.sorted())
    }

    @Test
    fun anAppUpdateWithAnotherLibraryReplacesTheCopy() {
        val cache = temp()
        val v1 = DispatchLibrary.resolve("tensor_g5", temp(), listOf(apk(File(temp(), "v1.apk"), mapOf("lib/arm64-v8a/$tensor" to lib))),
            "arm64-v8a", cache).folder!!
        val lib2 = ByteArray(80_000) { (it * 7).toByte() }
        val v2 = DispatchLibrary.resolve("tensor_g5", temp(), listOf(apk(File(temp(), "v2.apk"), mapOf("lib/arm64-v8a/$tensor" to lib2))),
            "arm64-v8a", cache).folder!!
        assertFalse(v1.id == v2.id)
        assertArrayEquals(lib2, File(v2.dir, tensor).readBytes())
        assertFalse("the old copy is gone", v1.dir.exists())
    }

    // ---------------------------------------------------------------- 4. what is downloaded

    private val soc = "tensor_g5"
    private val ctx = "c16_l64"
    private val prog = "npu/$soc/$ctx/tajweedformer_v4_${soc}_$ctx"

    private fun selfTest() = listOf(
        TestKit.F("selftest/selftest.json", "{}".toByteArray(), "selftest", "*", "optional"),
        TestKit.F("selftest/expected_$ctx.json", "{}".toByteArray(), "selftest", ctx, "optional"),
        TestKit.F("selftest/Husary_001001.s16", ByteArray(64), "selftest", "*", "optional"),
    )

    /** A build like V4 4.0.0 (33638dc8): the Tensor program with its QLPD delta beside it, and the self-test set. */
    private fun build(selftest: Boolean = true, deltaOnly: Boolean = false): ModelManifest {
        var files = TestKit.v4Files(npuSocs = listOf(soc)) +
            TestKit.F("$prog.tflite.qlpd", ByteArray(4000) { 5 }, "npu-delta", ctx, "optional", listOf("android"), soc)
        if (deltaOnly) files = files.filter { it.role != "npu-model" }
        if (selftest) files = files + selfTest()
        return TestKit.manifest(files)
    }

    private val found = DispatchLibrary.Resolution(DispatchLibrary.Folder(File("/data/app/x/lib/arm64"), tensor, "lib:579024:1", false), null)
    private val absent = DispatchLibrary.Resolution(null, "no $tensor in the app (add the LiteRT NPU runtime for this SoC)")

    private fun choose(m: ModelManifest, records: AcceleratorRecords, d: DispatchLibrary.Resolution = found, runtime: String? = null,
                       s: String? = soc, os: String = "os1", now: Long = 1_000L) =
        AcceleratorGate.choose(m, s, ctx, records, "${m.build}:npu:$soc:$ctx", "4.0.0", os, now, { d }, { runtime })

    @Test
    fun theProgramIsFetchedWithoutTheDeltaTheCoreNeverReads() {
        val m = build()
        val c = choose(m, AcceleratorRecords(File(temp(), "accel.json")))
        assertEquals(listOf("npu-io", "npu-masks", "npu-model"), c.files.map { it.role }.sorted())
        assertNull(c.why)
        assertEquals(found, c.dispatch)
        // the transport bytes: the delta was most of it before (V4 4.0.0's Tensor G5: 121 MB program + 86 MB delta)
        assertTrue(ModelProfile.bytes(c.files) < ModelProfile.bytes(ModelProfile.npu(m, soc, ctx)))
    }

    @Test
    fun aProgramThatCannotBePromotedIsNotDownloaded() {
        val records = AcceleratorRecords(File(temp(), "accel.json"))
        // no self-test set: the golden test has nothing to run (a device lab's test build 96200d23)
        val bare = build(selftest = false)
        val c = choose(bare, records)
        assertTrue(c.files.isEmpty())
        assertEquals("other", c.reason)
        assertTrue(c.why!!, c.why!!.contains("self-test"))
        // ... unless the build passed before with this library: then no golden test runs
        val key = "${bare.build}:npu:$soc:$ctx"
        records.end(key, AcceleratorRecords.PASSED, extra = mapOf("dispatch" to found.id))
        assertEquals(3, choose(bare, records).files.size)
        assertTrue("passed with another library: the golden test again, and nothing to run it with",
            choose(bare, records, d = found.copy(folder = found.folder!!.copy(id = "apk:0badf00d:1"))).files.isEmpty())
        // no dispatch library in the app
        val m = build()
        val other = AcceleratorRecords(File(temp(), "accel.json"))
        val n = choose(m, other, d = absent)
        assertTrue(n.files.isEmpty())
        assertEquals("load_error", n.reason)
        assertEquals(absent.missing, n.why)
        // no NPU runtime in the system image (a Pixel 8 on Android 15)
        val r = choose(m, other, runtime = "no libedgetpu_litert.so in this system image")
        assertTrue(r.files.isEmpty())
        assertEquals("load_error", r.reason)
        // a program shipped only as deltas: the Core reads none of it
        val d = choose(build(deltaOnly = true), other)
        assertTrue(d.files.isEmpty())
        assertTrue(d.why!!, d.why!!.contains("delta"))
        // nothing to say: no SoC, no program for this SoC, off for good
        assertNull(choose(m, other, s = null).why)
        assertNull(choose(TestKit.manifest(TestKit.v4Files()), other).why)
        other.end("${m.build}:npu:$soc:$ctx", AcceleratorRecords.FAILED, "golden: mismatch")
        val off = choose(m, other)
        assertTrue(off.files.isEmpty())
        assertNull(off.why)
    }

    @Test
    fun aVerdictFoundWithoutTheLibraryIsLiftedWhenTheAppShipsIt() {
        val m = build()
        val records = AcceleratorRecords(File(temp(), "accel.json"))
        val key = "${m.build}:npu:$soc:$ctx"
        // the client holds the "no dispatch library" verdict so it is said once (TajweedClient.noteUnusable)
        records.hold(key, AcceleratorRecords.UNSUPPORTED, absent.missing!!, "4.0.0", "os1", 1_000L, mapOf("dispatch" to DispatchLibrary.NONE))
        val still = choose(m, records, d = absent)
        assertTrue(still.files.isEmpty())
        assertNull("held: not said again", still.why)
        // the app's update ships the library: tried at once, without waiting 30 days
        assertEquals(3, choose(m, records, d = found).files.size)
        // a verdict of the golden run with one library, and the app's next version ships another
        records.hold(key, AcceleratorRecords.UNSUPPORTED, "golden run: engine", "4.0.0", "os1", 1_000L, mapOf("dispatch" to found.id))
        assertTrue(choose(m, records, d = found).files.isEmpty())
        assertEquals(3, choose(m, records, d = found.copy(folder = found.folder!!.copy(id = "apk:12345678:600000"))).files.size)
        assertEquals("an OS update", 3, choose(m, records, os = "os2").files.size)
    }

    @Test
    fun aConfigRuleKeepsTheNpuOffBeforeAnythingIsFetched() {
        // Quran Lab keeps the Tensor NPU off for SDK 4.0 (accel_deny): nothing fetched, the dispatch library not even
        // looked for, said as `denylist`, and never held (the next config without the rule brings it back)
        val m = build()
        val records = AcceleratorRecords(File(temp(), "accel.json"))
        var resolved = 0
        var runtime = 0
        val c = AcceleratorGate.choose(m, soc, ctx, records, "${m.build}:npu:$soc:$ctx", "4.0.0", "os1", 1_000L,
            { resolved++; found }, { runtime++; null }, configInForce = true, denied = { s -> if (s.startsWith("tensor_")) "Tensor NPU slower than the CPU" else null })
        assertTrue(c.files.isEmpty())
        assertTrue(c.denied)
        assertEquals("denylist", c.reason)
        assertEquals("accel_deny: Tensor NPU slower than the CPU", c.why)
        assertEquals("the dispatch library is not looked for", 0, resolved)
        assertEquals(0, runtime)
        assertNull("nothing held", records.entry("${m.build}:npu:$soc:$ctx"))
        // the rule lifted: the program is fetched again at once
        val lifted = AcceleratorGate.choose(m, soc, ctx, records, "${m.build}:npu:$soc:$ctx", "4.0.0", "os1", 1_000L,
            { resolved++; found }, { null }, configInForce = true, denied = { null })
        assertEquals(3, lifted.files.size)
    }

    @Test
    fun nothingIsFetchedWithoutASignedConfigInForce() {
        // the config may deny the accelerator: without a verified one in force the SDK fetches no program, and says nothing
        val m = build()
        var resolved = 0
        val c = AcceleratorGate.choose(m, soc, ctx, AcceleratorRecords(File(temp(), "accel.json")), "${m.build}:npu:$soc:$ctx", "4.0.0",
            "os1", 1_000L, { resolved++; found }, { null }, configInForce = false)
        assertTrue(c.files.isEmpty())
        assertNull(c.why)
        assertFalse(c.denied)
        assertEquals(0, resolved)
    }

    @Test
    fun aFirmwareTheCoreRefusesIsKnownBeforeTheDownload() {
        // the Core checks the firmware before any installed file: QLC_D_ACCEL_FIRMWARE at once on the Pixel 10 family's
        // launch firmware, QLC_E_STORAGE ("not installed") elsewhere (a device lab's Pixel 10 Pro fetched 121 MB first)
        val launch = Host(prepare = { throw CoreException(Core.E_ENGINE, Core.D_ACCEL_FIRMWARE, "qlc_accel_prepare") })
        assertTrue(AcceleratorGate.firmwareRefused(launch, "npu:tensor_g5", "/x"))
        val elsewhere = Host(prepare = { throw CoreException(Core.E_STORAGE, 0, "qlc_accel_prepare") })
        assertFalse(AcceleratorGate.firmwareRefused(elsewhere, "npu:tensor_g5", "/x"))
        val busy = Host(prepare = { throw IllegalStateException("closed") })
        assertFalse("anything else: the download goes on as before", AcceleratorGate.firmwareRefused(busy, "npu:tensor_g5", "/x"))
        // a prepare that succeeds (nothing should be installed, but it was): released at once
        val ready = Host()
        assertFalse(AcceleratorGate.firmwareRefused(ready, "npu:tensor_g5", "/x"))
        assertEquals(1, ready.releases.get())
        assertEquals(1, launch.prepares.get())
    }

    // ---------------------------------------------------------------- 2 and 3. the verdicts of the promotion

    /** An engine with a program: [prepare] answers the Core's prepare, [golden] the golden runs. */
    private class Host(
        val prepare: () -> String = { "npu:tensor_g5" },
        val golden: (List<Pair<String, FloatArray>>) -> GoldenRuns = { clips ->
            GoldenRuns(clips.map { (id, _) -> Triple(id, listOf(TestKit.headsOf(listOf(5, 6))), listOf(TestKit.headsOf(listOf(5, 6)))) },
                List(10) { 5.0 }, List(10) { 6.0 })
        },
    ) : TajweedEngine, AcceleratorHost {
        val prepares = AtomicInteger()
        val goldens = AtomicInteger()
        val releases = AtomicInteger()
        @Volatile var promoted = false
        override val chunkMs: Int get() = 320
        override val backend: Backend get() = if (promoted) Backend.NPU else Backend.CPU

        override fun createStream(): TajweedEngineStream = object : TajweedEngineStream {
            override fun pushAudio(samples: FloatArray, offset: Int, length: Int): List<HeadValues> = emptyList()
            override fun finish(): List<HeadValues> = emptyList()
            override fun reset() {}
            override fun close() {}
        }

        override fun prepareAccelerator(kind: String, dispatchDir: String?): String {
            prepares.incrementAndGet()
            return prepare()
        }

        override fun goldenRuns(clips: List<Pair<String, FloatArray>>): GoldenRuns {
            goldens.incrementAndGet()
            return golden(clips)
        }

        override fun promote() { promoted = true }

        override fun releaseAccelerator() {
            promoted = false
            releases.incrementAndGet()
        }

        @Volatile override var onFailover: (String) -> Unit = {}

        override fun close() {}
    }

    private val folder: ModelFolder by lazy {
        val dir = temp()
        val files = TestKit.v4Files()
        TestKit.writeDecoded(dir, files, TestKit.sign(TestKit.manifest(files)))
        ModelFolder.open(dir, TestKit.verifier)
    }

    private val records: AcceleratorRecords by lazy { AcceleratorRecords(File(temp(), "accel.json")) }
    private val reports = LinkedBlockingQueue<PromotionInfo>()
    private val key: String get() = "${folder.manifest.build}:npu:tensor_g5:c16_l64"

    private fun plan(dispatch: String = "apk:aaaaaaaa:579024", os: String = "frankel:17/CP2A.260605.012") =
        AcceleratorPlan("tensor_g5", "/data/user/0/app/code_cache/quranlab-dispatch/x", null,
            clips = { listOf("Husary_001001" to FloatArray(16_000)) }, records = records, report = { reports.add(it) },
            sdk = "4.0.0", os = os, clock = { 1_800_000_000_000L }, prepareDeadlineMs = 5_000, dispatchId = dispatch)

    private fun run(host: Host, plan: AcceleratorPlan, waitMs: Long = 10_000): PromotionInfo? {
        val rec = Recognizer.open(folder, Recognizer.Options(context = "c16_l64", accelerator = plan, soc = "tensor_g5", engine = { _, _, _ -> host }))
        try {
            val p = reports.poll(waitMs, TimeUnit.MILLISECONDS)
            if (p != null && !p.promoted) assertEquals(Backend.CPU, rec.backend)
            return p
        } finally {
            rec.close()
        }
    }

    @Test
    fun aGoldenTestThatFailsInTheRuntimeIsHeldNotFailedForGood() {
        // the Pixel 10 without its dispatch library: the prepare said yes, the program's first push fails
        val broken = Host(golden = { throw CoreException(Core.E_ENGINE, 0, "qlc_stream_push") })
        val p = run(broken, plan())!!
        assertFalse(p.promoted)
        assertEquals("golden", p.failedAt)
        assertEquals("load_error", p.reason)
        assertTrue(p.detail!!, p.detail!!.startsWith("golden run: "))
        assertEquals("the program let go", 1, broken.releases.get())
        val e = records.entry(key)!!
        assertEquals(AcceleratorRecords.UNSUPPORTED, e["state"])
        assertEquals("apk:aaaaaaaa:579024", e["dispatch"])
        assertFalse("not off for good (11-18 it was)", records.blocked(key))
        // the same library, SDK and OS: not tried again at the next start
        val same = Host()
        assertNull(run(same, plan(), waitMs = 500))
        assertEquals(0, same.prepares.get())
        // the app's update ships another library (or the system updates): tried again, and promoted when it works
        val fixed = Host()
        assertTrue(run(fixed, plan(dispatch = "lib:600000:1"))!!.promoted)
        assertEquals(1, fixed.goldens.get())
        assertEquals("lib:600000:1", records.entry(key)!!["dispatch"])
    }

    @Test
    fun aGoldenMismatchIsStillAVerdictOnTheBuild() {
        val wrong = Host(golden = { clips ->
            GoldenRuns(clips.map { (id, _) -> Triple(id, listOf(TestKit.headsOf(listOf(5, 7))), listOf(TestKit.headsOf(listOf(5, 6)))) })
        })
        val p = run(wrong, plan())!!
        assertEquals("golden_mismatch", p.reason)
        assertTrue(records.blocked(key))
        val other = Host()
        assertNull("whatever the library", run(other, plan(dispatch = "lib:1:1"), waitMs = 500))
        assertEquals(0, other.prepares.get())
    }

    @Test
    fun twoPreparesThatNeverCameBackAreHeldNotFailedForGood() {
        // two processes ended inside the prepare (the system or the vendor's runtime ended them)
        assertTrue(records.begin(key, "apk:aaaaaaaa:579024"))
        assertTrue(records.begin(key, "apk:aaaaaaaa:579024"))
        val host = Host()
        val p = run(host, plan())!!
        assertEquals("compile", p.failedAt)
        assertEquals("crash", p.reason)
        assertEquals(0, host.prepares.get())
        assertEquals(AcceleratorRecords.UNSUPPORTED, records.entry(key)!!["state"])
        assertFalse(records.blocked(key))
        // after an OS update it is tried again
        val later = Host()
        assertTrue(run(later, plan(os = "frankel:17/CP3A.261005.001"))!!.promoted)
    }

    @Test
    fun theRuntimesDeviceSideRefusalsAreHeldAndTheBuildsFaultsAreNot() {
        for (detail in listOf(Core.D_ACCEL_HOOKED, Core.D_ACCEL_PLATFORM, Core.D_ACCEL_RUNTIME, Core.D_ACCEL_PROGRAM)) {
            val r = AcceleratorRecords(File(temp(), "accel.json"))
            val pl = AcceleratorPlan("tensor_g5", null, null, clips = { listOf("c" to FloatArray(16_000)) }, records = r,
                report = { reports.add(it) }, os = "os", clock = { 1L }, dispatchId = "lib:1:1")
            val p = run(Host(prepare = { throw CoreException(Core.E_ENGINE, detail, "qlc_accel_prepare") }), pl)!!
            assertEquals("compile", p.failedAt)
            assertEquals("detail $detail", AcceleratorRecords.UNSUPPORTED, r.entry(key)!!["state"])
            assertEquals("lib:1:1", r.entry(key)!!["dispatch"])
            assertFalse(r.blocked(key))
        }
        val r = AcceleratorRecords(File(temp(), "accel.json"))
        val pl = AcceleratorPlan("tensor_g5", null, null, clips = { listOf("c" to FloatArray(16_000)) }, records = r,
            report = { reports.add(it) }, os = "os", clock = { 1L })
        assertNotNull(run(Host(prepare = { throw CoreException(Core.E_ENGINE, Core.D_ACCEL_CONTRACT, "qlc_accel_prepare") }), pl))
        assertTrue("a program that breaks the host contract", r.blocked(key))
    }

    @Test
    fun aPassWithAnotherDispatchLibraryRunsTheGoldenTestAgain() {
        records.end(key, AcceleratorRecords.PASSED, extra = mapOf("dispatch" to "lib:1:1"))
        val same = Host()
        assertTrue(run(same, plan(dispatch = "lib:1:1"))!!.promoted)
        assertEquals("passed with this library: promoted without a golden run", 0, same.goldens.get())
        val other = Host()
        assertTrue(run(other, plan(dispatch = "apk:bbbbbbbb:579024"))!!.promoted)
        assertEquals("another library: the golden test first", 1, other.goldens.get())
        // a pass recorded before the library was named counts for any library
        records.end(key, AcceleratorRecords.PASSED)
        val old = Host()
        assertTrue(run(old, plan(dispatch = "lib:9:9"))!!.promoted)
        assertEquals(0, old.goldens.get())
    }
}
