package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.ClientPlatform
import ai.quranlab.tajweedformer.ModelFolder
import ai.quranlab.tajweedformer.Recognizer
import ai.quranlab.tajweedformer.TajweedEngine
import ai.quranlab.tajweedformer.TajweedEngineFactory
import ai.quranlab.tajweedformer.TestKit
import java.io.File

/**
 * The platform of a Client in the Client's tests: the stub's API, the build [s] as the CDN, a fake engine. (SDK 4.0: no
 * install records; the install's keys are the software ones of the desktop JVM.)
 */
internal fun gatePlatform(stub: StubQlab, dir: File, s: ServedModel, integrity: IntegrityProvider?,
                          deleteTree: ((File) -> Boolean)? = null, engine: TajweedEngineFactory = CountingEngines(),
                          retry: ai.quranlab.tajweedformer.RetryPlan = ai.quranlab.tajweedformer.RetryPlan.NONE): ClientPlatform =
    ClientPlatform(cloud = stub.environment(dir, integrity = integrity), apiUrl = stub.base, trustedKeys = listOf(stub.publicKey, TestKit.key.first),
        engine = engine, deleteTree = deleteTree ?: { f -> f.deleteRecursively() }, modelRetry = retry)

/**
 * The fake engine, which in addition maps the model's weights file read-only while it is open, as the native engine
 * maps its files: [onMapped] gets the mapping.
 */
internal class MappingEngines(private val onMapped: (java.nio.MappedByteBuffer) -> Unit) : TajweedEngineFactory {
    private val inner = CountingEngines()
    override fun open(model: ModelFolder, context: String, options: Recognizer.Options): TajweedEngine {
        val engine = inner.open(model, context, options)
        val ch = java.io.RandomAccessFile(File(model.dir, "layers.zfw"), "r").channel
        onMapped(ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, ch.size()))
        return object : TajweedEngine by engine {
            override fun close() {
                engine.close()
                ch.close()
            }
        }
    }
}

/** The stub's Play Integrity (it records the project number of each request). */
internal fun stubPlay(stub: StubQlab): IntegrityProvider = IntegrityProvider { h, n -> stub.play(h, n) }

/** A device where Play cannot answer (no Play services, or the call fails). */
internal fun failingPlay(): IntegrityProvider = IntegrityProvider { _, _ -> throw IllegalStateException("Play services are not on this device") }
