package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.ClientPlatform
import ai.quranlab.tajweedformer.Json
import ai.quranlab.tajweedformer.ModelDownloader
import ai.quranlab.tajweedformer.ModelHosts
import ai.quranlab.tajweedformer.ModelManifest
import ai.quranlab.tajweedformer.ModelStore
import ai.quranlab.tajweedformer.OpenChecked
import ai.quranlab.tajweedformer.RangePlan
import ai.quranlab.tajweedformer.RetryPlan
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedClientOptions
import ai.quranlab.tajweedformer.TestKit
import ai.quranlab.tajweedformer.UrlConnectionTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contract 34.6 (SDK 4.0.2): the counts per `models` host and UTC day. Stand-in model hosts with known timings (a
 * status held back 2 s, a body sent at 90 kB/s) give a whole file, a resumed one, a failed one and a failover to the
 * next host; each gives the expected counts and buckets in the day's telemetry, kept on the device (a new process
 * reads them). The body is checked against the contract's vectors (`steering_40.json`, 11 bodies): the accepted ones
 * are what the SDK builds from the same counts, and what the SDK builds from the refused ones' inputs is never refused.
 */
class HostCountsTest : OpenChecked() {
    private val dirs = ArrayList<File>()
    private val served = ArrayList<ServedModel>()
    private val ctx = TelemetryContext(AppIdentity("org.example.recite", "1.0"), DeviceProfile("Pixel8", "tensor_g3", 14, "14"))
    private val first = HostNames.MODELS[0]
    private val second = HostNames.MODELS[1]

    @After
    fun down() {
        PerHostCounts.send = true
        served.forEach { it.close() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun temp(): File = Files.createTempDirectory("tj-hosts-").toFile().also { dirs += it }

    /** A build whose weights are [kib] KiB of random bytes (stored gzip: about as large). */
    private fun host(kib: Int = 400): ServedModel {
        val files = TestKit.v4Files().map { f ->
            if (f.path == "layers.zfw") TestKit.F("layers.zfw", ByteArray(kib shl 10).also { java.util.Random(7).nextBytes(it) }, "weights") else f
        }
        return ServedModel.of(files).also { served += it }
    }

    private fun manifest(s: ServedModel) = ModelManifest.parse(File(s.root, ModelManifest.FILE_NAME).readBytes())

    private fun weights(s: ServedModel) = manifest(s).files.first { it.localPath == "layers.zfw" }

    private fun base(s: ServedModel) = "https://$first/${ServedModel.REPO}/${s.build}/${ServedModel.FOLDER}/"

    /** The models hosts [hosts] (the first served by the first of [by], ...), counted into [t]. */
    private fun endpoints(by: List<ServedModel>, t: Telemetry): Endpoints {
        val hosts = HostNames.MODELS.take(by.size)
        return Endpoints.mapped(HostNames.API, hosts, hosts.zip(by.map { it.url }).toMap()).also { it.meter = t }
    }

    private fun downloader(by: List<ServedModel>, t: Telemetry, retry: RetryPlan = RetryPlan(longArrayOf(10, 10, 10))): ModelDownloader {
        val b = base(by[0])
        return ModelDownloader(temp(), b, UrlConnectionTransport(), null, RangePlan.DEFAULT, retry, ModelHosts.of(b, endpoints(by, t)))
    }

    @Suppress("UNCHECKED_CAST")
    private fun hosts(t: Telemetry): List<Map<String, Any?>> = (t.peek(ctx)?.get("hosts") as? List<Map<String, Any?>>) ?: emptyList()

    private fun Map<String, Any?>.n(k: String) = (this[k] as Number).toLong()
    private fun Map<String, Any?>.list(k: String) = (this[k] as List<*>).map { (it as Number).toInt() }

    /** Requests, unreachable, files, bytes of one entry. */
    private fun Map<String, Any?>.counts() = listOf(n("requests"), n("unreachable"), n("files"), n("bytes"))

    @Test
    fun aWholeFileWithItsTimingsInTheirBuckets() {
        val a = host(400)
        val e = weights(a)
        a.headDelayMs = { p -> if (p == e.path) 2_000L else null }
        a.bytesPerSecond = { p -> if (p == e.path) 90_000L else null }
        val t = Telemetry(temp())
        downloader(listOf(a), t).fetch(e)
        val h = hosts(t).single()
        assertEquals(first, h["host"])
        assertEquals("models", h["role"])
        assertEquals(listOf(1L, 0L, 1L, e.size), h.counts())
        assertEquals("the status after 2 s: 1500 to 3000 ms", listOf(0, 0, 0, 0, 0, 1, 0, 0, 0), h.list("ttfb_ms"))
        assertEquals("about 90 kB/s: 64 to 128", listOf(0, 0, 1, 0, 0, 0, 0, 0, 0, 0), h.list("kbps"))
        assertEquals(0L, h.n("hedged"))
        assertEquals(0L, h.n("slow_switches"))
        assertTrue(serverTakes(hosts(t)))
    }

    @Test
    fun aResumedFileIsTwoRequestsAndOneFileAndIsKeptForTheNextProcess() {
        val a = host(1024)
        val e = weights(a)
        val n = AtomicInteger()
        a.cut = { p -> if (p == e.path && n.incrementAndGet() == 1) 300L shl 10 else null }
        val dir = temp()
        val t = Telemetry(dir)
        downloader(listOf(a), t).fetch(e)
        val asked = a.requests.filter { it.path == e.path }
        assertEquals("the cut and its resume: $asked", 2, asked.size)
        val h = hosts(t).single()
        assertEquals("2 requests, the cut unreachable, 1 file, every byte once", listOf(2L, 1L, 1L, e.size), h.counts())
        assertEquals(2, h.list("ttfb_ms").sum())
        assertEquals("both transfers at least 256 KiB (300 KiB and the rest)", 2, h.list("kbps").sum())
        // a new process (the background job's, say) reads the day's counts
        assertEquals(hosts(t), hosts(Telemetry(dir)))
    }

    @Test
    fun aFailedFileAndARefusal() {
        val a = host()
        val m = manifest(a)
        val w = weights(a)
        val other = m.files.first { it.path != w.path }
        a.answer = { p, _ ->
            when (p) {
                w.path -> ServedModel.Answer(503)
                other.path -> ServedModel.Answer(404, """{"error":"not_found","message":"no such file"}""")
                else -> null
            }
        }
        val t = Telemetry(temp())
        val d = downloader(listOf(a), t, RetryPlan.NONE)
        assertTrue(runCatching { d.fetch(w) }.isFailure)
        assertTrue(runCatching { d.fetch(other) }.isFailure)
        val h = hosts(t).single()
        assertEquals("2 requests, the 503 unreachable (the 404 with the host's error is a refusal), no file, no bytes",
            listOf(2L, 1L, 0L, 0L), h.counts())
        assertEquals(2, h.list("ttfb_ms").sum())
        assertEquals(0, h.list("kbps").sum())
    }

    @Test
    fun aFailoverToTheNextHost() {
        val a = host()
        val b = ServedModel.ofFolder(a.root).also { served += it }
        val e = weights(a)
        a.answer = { p, _ -> if (p == e.path) ServedModel.Answer(503) else null }
        val t = Telemetry(temp())
        downloader(listOf(a, b), t).fetch(e)
        val hs = hosts(t)
        assertEquals("in the order they were asked", listOf(first, second), hs.map { it["host"] })
        assertEquals(listOf(1L, 1L, 0L, 0L), hs[0].counts())
        assertEquals(listOf(1L, 0L, 1L, e.size), hs[1].counts())
        assertTrue(serverTakes(hs))
    }

    /** Contract 0d9f5d7: the build's manifest.json is one of its host's files, with its request and bytes. */
    @Test
    fun theManifestIsOneOfItsHostsFiles() {
        val a = host()
        val t = Telemetry(temp())
        val st = ModelStore(temp(), base(a), UrlConnectionTransport(), null, TestKit.verifier, expectBuild = a.build,
            retry = RetryPlan.NONE, endpoints = endpoints(listOf(a), t))
        st.manifest(true)
        val h = hosts(t).single()
        assertEquals(listOf(1L, 0L, 1L, File(a.root, ModelManifest.FILE_NAME).length()), h.counts())
    }

    @Test
    fun aFolderOffTheAllowListIsNotCounted() {
        val a = host()
        val t = Telemetry(temp())
        val e = weights(a)
        // the stand-in's own address: a fixed folder (28.4), never a host of the counts
        ModelDownloader(temp(), "${a.url}/${ServedModel.REPO}/${a.build}/${ServedModel.FOLDER}/", UrlConnectionTransport(), null,
            RangePlan.DEFAULT, RetryPlan.NONE, ModelHosts.of("${a.url}/${ServedModel.REPO}/${a.build}/${ServedModel.FOLDER}/", endpoints(listOf(a), t))).fetch(e)
        assertTrue(hosts(t).isEmpty())
        for (h in listOf("cdn.example.com", "evilquranlab.ai", "127.0.0.1")) t.request(h, 50)
        assertTrue(hosts(t).isEmpty())
    }

    @Test
    fun theBucketsAreUnderTheirEdges() {
        assertEquals(0, PerHostCounts.bucket(99.0, PerHostCounts.TTFB_MS))
        assertEquals(1, PerHostCounts.bucket(100.0, PerHostCounts.TTFB_MS))
        assertEquals(7, PerHostCounts.bucket(9_999.0, PerHostCounts.TTFB_MS))
        assertEquals(8, PerHostCounts.bucket(10_000.0, PerHostCounts.TTFB_MS))
        // kB/s of 1000 bytes
        assertEquals(0, PerHostCounts.rateBucket(31_900, 1_000_000_000))
        assertEquals(1, PerHostCounts.rateBucket(32_000, 1_000_000_000))
        assertEquals(8, PerHostCounts.rateBucket(8_191_000, 1_000_000_000))
        assertEquals(9, PerHostCounts.rateBucket(8_192_000, 1_000_000_000))
        // a transfer under 256 KiB has bytes and no rate
        val t = Telemetry(temp())
        t.transfer(first, 262_143, 1_000_000_000)
        assertEquals(0, hosts(t).single().list("kbps").sum())
        t.transfer(first, 262_144, 1_000_000_000)
        assertEquals("262 kB/s: 256 to 512", listOf(0, 0, 0, 0, 1, 0, 0, 0, 0, 0), hosts(t).single().list("kbps"))
        assertEquals(524_287L, hosts(t).single().n("bytes"))
    }

    /**
     * The send points: the counts go with the day's report and start again from zero; the day's hosts stay with the
     * day, so a day has at most 8 across its reports. With the switch off the counts are kept and never sent, and a
     * day with host counts alone makes no report.
     */
    @Test
    fun theSendPointsAndTheSwitch() {
        val t = Telemetry(temp())
        for (i in 0 until 5) t.request("n$i.quranlab.ai", 50)
        assertEquals(1, t.seal(ctx))
        @Suppress("UNCHECKED_CAST")
        val sent = (Json.parse(t.pendingReports().single().readText()) as Map<String, Any?>)["hosts"] as List<Map<String, Any?>>
        assertEquals(5, sent.size)
        assertTrue(serverTakes(sent))
        for (i in 5 until 10) t.request("n$i.quranlab.ai", 50)
        assertEquals("the day's cap of 8 across its reports", listOf("n5.quranlab.ai", "n6.quranlab.ai", "n7.quranlab.ai"), hosts(t).map { it["host"] })

        PerHostCounts.send = false
        val dir = temp()
        val off = Telemetry(dir)
        off.request(first, 50)
        off.file(first)
        assertNull("host counts alone are no report", off.peek(ctx))
        assertEquals(0, off.seal(ctx))
        PerHostCounts.send = true
        assertEquals("kept with the day through the send point", listOf(1L, 0L, 1L, 0L), hosts(Telemetry(dir)).single().counts())
    }

    @Test
    fun theClientsTelemetryCountsItsModelRequests() {
        val stub = StubQlab()
        try {
            val c = TajweedClient(stub.key, TajweedClientOptions(), ClientPlatform(cloud = stub.environment(temp()), apiUrl = stub.base,
                trustedKeys = listOf(stub.publicKey)), autoLaunch = false)
            try {
                assertSame(c.cloud.telemetry, c.cloud.endpoints.meter)
            } finally {
                c.close()
            }
        } finally {
            stub.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private val vectors: Map<String, Any?> by lazy {
        (Json.parse(javaClass.getResourceAsStream("/steering/steering_40.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<String, Any?>)["telemetry"] as Map<String, Any?>
    }

    @Test
    fun theContractsCopyIsByteForByte() {
        // the contract's file at sdk-4.0.2 (0d9f5d7), when the contract is on this machine
        val dir = System.getProperty("TJ_VECTORS40_DIR") ?: System.getenv("TJ_VECTORS40_DIR") ?: return
        val theirs = File(dir, "steering_40.json").takeIf { it.isFile } ?: return
        val mine = javaClass.getResourceAsStream("/steering/steering_40.json")!!.use { it.readBytes() }
        org.junit.Assert.assertArrayEquals(theirs.readBytes(), mine)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun theBodiesTheServerTakesAndNoneItRefuses() {
        assertEquals(PerHostCounts.TTFB_MS.toList(), (vectors["ttfb_buckets_ms"] as List<*>).map { (it as Number).toLong() })
        assertEquals(PerHostCounts.KBPS.toList(), (vectors["kbps_buckets"] as List<*>).map { (it as Number).toLong() })
        val cases = vectors["cases"] as List<Map<String, Any?>>
        assertEquals(11, cases.size)
        // the test's reading of the server's rules gives every case's verdict
        for (c in cases) assertEquals(c["name"] as String, c["accepted"], serverTakes(c["hosts"]))
        // each accepted body, built by the SDK from the same counts (4.0.2 has no hedge and no slow switch: 0)
        for (c in cases.filter { it["accepted"] == true }) {
            val want = (c["hosts"] as List<Map<String, Any?>>).map { h -> norm(h + mapOf("hedged" to 0, "slow_switches" to 0)) }
            assertTrue(c["name"] as String, serverTakes(want))
            val a = DayAggregate()
            for (h in c["hosts"] as List<Map<String, Any?>>) {
                val (name, counts) = HostCounts.fromStore(h)!!
                a.hosts[name] = counts
                a.dayHosts.add(name)
            }
            val body = TelemetryPayload.build("2026-11-22", a, ctx, "0".repeat(32))?.let { Json.parse(String(it, Charsets.UTF_8)) as Map<String, Any?> }
            val got = ((body?.get("hosts") as? List<Map<String, Any?>>) ?: emptyList()).map { norm(it) }
            assertEquals(c["name"] as String, want, got)
        }
        // the refused ones' inputs, through the recorder: what it builds is taken
        for (c in cases.filter { it["accepted"] == false }) {
            val t = Telemetry(temp())
            for (h in c["hosts"] as List<Map<String, Any?>>) {
                val name = h["host"] as String
                t.request(name, 50)
                t.transfer(name, maxOf(1L, Math.abs((h["bytes"] as Number).toLong())), 1_000_000_000)
                t.file(name)
            }
            val got = hosts(t)
            assertTrue("${c["name"]}: $got", serverTakes(got))
            assertNotEquals(c["name"] as String, (c["hosts"] as List<Map<String, Any?>>).map { norm(it) }, got.map { norm(it) })
        }
    }

    /** Numbers as whole numbers, keys in one order. */
    private fun norm(h: Map<String, Any?>): Map<String, Any?> = h.toSortedMap().mapValues { (_, v) ->
        when (v) {
            is Number -> v.toLong()
            is List<*> -> v.map { (it as? Number)?.toLong() ?: it }
            else -> v
        }
    }

    /** 34.6's closed body as the server reads it: at most 8 entries of exactly these fields, allow-list hosts once each. */
    private fun serverTakes(hosts: Any?): Boolean {
        val l = hosts as? List<*> ?: return false
        if (l.size > PerHostCounts.MAX_HOSTS) return false
        val seen = HashSet<String>()
        for (x in l) {
            val h = x as? Map<*, *> ?: return false
            if (h.keys != setOf("role", "host", "files", "bytes", "requests", "unreachable", "hedged", "slow_switches", "ttfb_ms", "kbps")) return false
            if (h["role"] != "models") return false
            val name = h["host"] as? String ?: return false
            if (!HostNames.allowed(name) || name != name.lowercase() || !seen.add(name)) return false
            fun whole(v: Any?) = (v as? Number)?.let { it.toDouble() >= 0 && it.toDouble() == Math.floor(it.toDouble()) } == true
            if (!listOf("files", "bytes", "requests", "unreachable", "hedged", "slow_switches").all { whole(h[it]) }) return false
            val ttfb = h["ttfb_ms"] as? List<*> ?: return false
            val kbps = h["kbps"] as? List<*> ?: return false
            if (ttfb.size != 9 || kbps.size != 10 || !(ttfb + kbps).all { whole(it) }) return false
        }
        return true
    }
}
