package ai.quranlab.tajweedformer.cloud

import ai.quranlab.tajweedformer.ClientPlatform
import ai.quranlab.tajweedformer.InstallKeyStore
import ai.quranlab.tajweedformer.ModelFolder
import ai.quranlab.tajweedformer.Recognizer
import ai.quranlab.tajweedformer.TajweedEngine
import ai.quranlab.tajweedformer.TajweedEngineFactory
import ai.quranlab.tajweedformer.TestKit
import java.io.File

/**
 * The platform of a Client in [ModelFromServerOnlyTest]: the stub's API, the build [s] as the CDN, a fake engine, and
 * the install records' key store [keys] (SDK 3.1.0). Kept apart from the test so that the same test class can be
 * compiled against the released 3.0.1 code, which has no key store, to show it red there.
 */
internal fun gatePlatform(stub: StubQlab, dir: File, s: ServedModel, keys: InstallKeyStore, integrity: IntegrityProvider?,
                          deleteTree: ((File) -> Boolean)? = null, engine: TajweedEngineFactory = CountingEngines(),
                          retry: ai.quranlab.tajweedformer.RetryPlan = ai.quranlab.tajweedformer.RetryPlan.NONE): ClientPlatform =
    ClientPlatform(cloud = stub.environment(dir, integrity = integrity), apiUrl = stub.base, trustedKeys = listOf(stub.publicKey, TestKit.key.first),
        modelsUrl = s.url, engine = engine, installKeys = keys, deleteTree = deleteTree ?: { f -> f.deleteRecursively() }, modelRetry = retry,
        // the Keystore's rule (the same failure at three starts in a row makes a new key), as Android's platform has it
        installKeyReplaced = true)

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
