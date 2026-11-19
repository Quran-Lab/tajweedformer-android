package ai.quranlab.tajweedformer

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/** Which backend a recognizer should run. */
internal enum class BackendPreference {
    /** The NPU when this device has a verified build that passes its self-test, else the CPU. */
    AUTO,
    /** The exact CPU engine only. */
    CPU,
}
