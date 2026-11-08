package ai.quranlab.tajweedformer.engine

/**
 * Loads libtajweedformer_jni: from the path in the system property `tajweedformer.library` when it is
 * set, otherwise from the library path (the APK's native libraries on Android).
 */
object NativeLibrary {
    @Volatile private var loaded = false

    @JvmStatic
    @Synchronized
    fun load() {
        if (loaded) return
        val path = System.getProperty("tajweedformer.library")
        if (path != null) System.load(path) else System.loadLibrary("tajweedformer_jni")
        loaded = true
    }
}
