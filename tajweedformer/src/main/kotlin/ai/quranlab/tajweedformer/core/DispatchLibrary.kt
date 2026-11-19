package ai.quranlab.tajweedformer.core

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * The vendor's LiteRT dispatch library that an NPU program needs (R2), and the folder the SDK hands the Core's
 * `qlc_accel_prepare` as `dispatch_dir`.
 *
 * How LiteRT finds it (LiteRT `litert/core/dynamic_loading.cc`, `FindLiteRtDispatchSharedLibs`): it lists the folder
 * given as `kLiteRtEnvOptionTagDispatchLibraryDir` and loads the first readable regular file whose name contains
 * `libLiteRtDispatch` and ends in `.so`. LiteRT's own Kotlin provider (`BuiltinNpuAcceleratorProvider`) gives it the
 * app's `nativeLibraryDir`, and Google's integration guide for Google Tensor says why that needs
 * `packaging { jniLibs { useLegacyPackaging = true } }` (or `android:extractNativeLibs="true"`): LiteRT reads that folder
 * as a folder of files. Under the Android Gradle plugin's default packaging (libraries stored uncompressed in the APK,
 * `extractNativeLibs` false, the default for every app on this SDK's minSdk) the folder is empty, so the program
 * compiles without its dispatch and fails at its first run (a device lab, Pixel 10, 11-18).
 *
 * So the SDK resolves the folder itself, in this order:
 * 1. the app's `nativeLibraryDir` when it holds the SoC's dispatch library as a file and no other dispatch library
 *    (Google's documented setup: the runtime from `com.google.ai.edge.litert:litert-npu-runtime-google-tensor`, or the
 *    `google_tensor_runtime` install-time feature module, with native libraries extracted);
 * 2. otherwise the same library out of the app's installed APKs (the base APK and its splits, where an install-time
 *    feature module puts it), copied once into the app's code cache (`Context.getCodeCacheDir`: the system clears it
 *    when the app or the platform is updated) in a folder of its own that holds only that library, its CRC checked;
 * 3. otherwise none: the app ships no dispatch library for this SoC, and the SDK neither downloads nor prepares a
 *    program (an NPU program without its dispatch runs nowhere).
 */
internal object DispatchLibrary {
    /** LiteRT's rule for a dispatch library's file name. */
    fun isDispatch(name: String): Boolean = name.contains("libLiteRtDispatch") && name.endsWith(".so")

    /** The dispatch library LiteRT loads for the SoC family of [soc] ([ai.quranlab.tajweedformer.SocIds]); null for a
     *  SoC this SDK ships no NPU builds for. */
    fun nameFor(soc: String): String? = when {
        soc.startsWith("tensor_") -> "libLiteRtDispatch_GoogleTensor.so"
        soc.startsWith("exynos") -> "libLiteRtDispatch_Samsung.so"
        else -> null
    }

    /** The folder for `qlc_accel_prepare` and what identifies the library in it (an accelerator verdict found with one
     *  library is tried again with another: an app update that adds or changes it). */
    data class Folder(val dir: File, val library: String, val id: String, val copied: Boolean)

    /** The outcome: [folder] when the SoC's library is usable, else [missing] says why (for the telemetry's detail). */
    data class Resolution(val folder: Folder?, val missing: String?) {
        /** What a verdict is keyed by: the library's identity, or `none`. */
        val id: String get() = folder?.id ?: NONE
    }

    const val NONE = "none"

    /** LiteRT's runtime, which the Core opens by this name (quranlab-core `accel_litert.c`). */
    const val RUNTIME = "libLiteRt.so"

    /**
     * Why the app cannot run an NPU program, when it ships no LiteRT runtime, else null. LiteRT is not a dependency of
     * the AAR (Stage B, 11-18: at 4.0's launch no NPU program is served, and LiteRT 2.2.0's two libraries share one
     * namespace, which the Android Gradle plugin 9 refuses without `android.uniquePackageNames=false`); an app that wants
     * the NPU adds `com.google.ai.edge.litert:litert` 2.2.0 or later itself. Without it the Core's `dlopen` of
     * [RUNTIME] would fail only after the program was downloaded and decrypted, so the SDK looks first, where the
     * system's linker looks for an app's library: [nativeLibraryDir] (extracted) or `lib/<abi>/` in the installed APKs
     * (the default packaging). Asked before the dispatch library is resolved, so nothing is copied either.
     */
    fun runtimeMissing(nativeLibraryDir: File?, apks: List<File>, abi: String?): String? {
        if (abi == null) return null // a 32-bit process: resolve says why
        if (nativeLibraryDir?.let { File(it, RUNTIME).isFile } == true) return null
        val entry = "lib/$abi/$RUNTIME"
        for (apk in apks) {
            val found = try { ZipFile(apk).use { it.getEntry(entry) != null } } catch (_: Exception) { false }
            if (found) return null
        }
        return "no LiteRT runtime ($RUNTIME) in the app: an app adds com.google.ai.edge.litert:litert 2.2.0 or later to use the NPU"
    }

    /**
     * Resolves the dispatch folder for [soc].
     * @param nativeLibraryDir the app's `ApplicationInfo.nativeLibraryDir`
     * @param apks the app's installed APKs (`sourceDir` and `splitSourceDirs`)
     * @param abi the process's ABI as APKs name it (`arm64-v8a`); null: a 32-bit process, which runs no NPU program
     * @param cache the SDK's folder in the app's code cache, for a copied library
     */
    fun resolve(soc: String, nativeLibraryDir: File?, apks: List<File>, abi: String?, cache: File): Resolution {
        val name = nameFor(soc) ?: return Resolution(null, "no NPU dispatch library is known for $soc")
        if (abi == null) return Resolution(null, "a 32-bit process runs no NPU program")
        // 1. the documented setup: the library extracted into nativeLibraryDir, and the only dispatch library there
        val there = nativeLibraryDir?.let { listDispatch(it) }.orEmpty()
        val own = there.firstOrNull { it.name == name }
        if (own != null && there.size == 1) {
            return Resolution(Folder(nativeLibraryDir!!, name, "lib:${own.length()}:${own.lastModified()}", copied = false), null)
        }
        // 2. the library inside the installed APKs (not extracted), or beside other vendors' dispatch libraries:
        // copied into a folder that holds it alone
        val entry = "lib/$abi/$name"
        for (apk in apks) {
            val r = try {
                ZipFile(apk).use { z -> z.getEntry(entry)?.let { e -> copyOut(z, e, name, cache) } }
            } catch (_: Exception) {
                null
            }
            if (r != null) return Resolution(r, null)
        }
        // an extracted library beside other vendors': copied from the file
        if (own != null) {
            val r = try { copyFile(own, name, cache) } catch (_: Exception) { null }
            if (r != null) return Resolution(r, null)
        }
        return Resolution(null, "no $name in the app (add the LiteRT NPU runtime for this SoC)")
    }

    /** The dispatch libraries LiteRT would find in [dir]: readable regular files, in [dir] and its subfolders. */
    fun listDispatch(dir: File): List<File> {
        val out = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            val kids = try { d.listFiles() } catch (_: Exception) { null } ?: return
            for (f in kids) {
                if (f.isFile && f.canRead() && isDispatch(f.name)) out += f
                else if (f.isDirectory && depth < 4) walk(f, depth + 1)
            }
        }
        walk(dir, 0)
        return out
    }

    private fun target(cache: File, name: String, crc: Long, size: Long): File =
        File(File(cache, "${name.removeSuffix(".so")}-${"%08x".format(crc)}-$size"), name)

    private fun copyOut(z: ZipFile, e: java.util.zip.ZipEntry, name: String, cache: File): Folder? {
        val crc = e.crc
        val size = e.size
        if (crc < 0 || size <= 0) return null
        val dst = target(cache, name, crc, size)
        val id = "apk:${"%08x".format(crc)}:$size"
        if (dst.isFile && dst.length() == size) return Folder(dst.parentFile!!, name, id, copied = true)
        dst.parentFile!!.mkdirs()
        val tmp = File(dst.parentFile, "$name.tmp")
        val c = CRC32()
        z.getInputStream(e).use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    c.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        if (c.value != crc || tmp.length() != size) {
            tmp.delete()
            return null
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete()
            return null
        }
        dst.setReadable(true, true)
        dst.setWritable(false, false)
        prune(cache, dst.parentFile!!)
        return Folder(dst.parentFile!!, name, id, copied = true)
    }

    private fun copyFile(src: File, name: String, cache: File): Folder? {
        val c = CRC32()
        src.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                c.update(buf, 0, n)
            }
        }
        val dst = target(cache, name, c.value, src.length())
        val id = "lib:${"%08x".format(c.value)}:${src.length()}"
        if (!(dst.isFile && dst.length() == src.length())) {
            dst.parentFile!!.mkdirs()
            val tmp = File(dst.parentFile, "$name.tmp")
            src.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(dst)) {
                tmp.delete()
                return null
            }
            dst.setWritable(false, false)
        }
        prune(cache, dst.parentFile!!)
        return Folder(dst.parentFile!!, name, id, copied = true)
    }

    /** Copies of an earlier library (an app update replaced it) go: their files, then their folders. */
    private fun prune(cache: File, keep: File) {
        val dirs = cache.listFiles { f -> f.isDirectory && f != keep && isDispatch(f.name + ".so") } ?: return
        for (d in dirs) {
            d.listFiles()?.forEach { it.delete() }
            d.delete()
        }
    }
}
