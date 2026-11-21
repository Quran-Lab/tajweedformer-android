@file:JvmName("TajweedClients")

package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.cloud.AppIdentity
import ai.quranlab.tajweedformer.cloud.CloudEnvironment
import ai.quranlab.tajweedformer.cloud.DeviceProfile
import ai.quranlab.tajweedformer.sharing.FileContributorKeyStore
import ai.quranlab.tajweedformer.sharing.PlainSecretBox
import java.io.File

/**
 * The client on the desktop JVM (development machines, tests, tools): the same client as on Android, with the
 * SDK's files in [storage] (a folder the app can write to, used by one process at a time).
 *
 * ```
 * val client = TajweedClient("qlk_v1_...", File("quranlab"))     // your key from sdk.quranlab.ai
 * ```
 *
 * The desktop JVM has no microphone the SDK opens (`model.listen()` is `microphone_unavailable`; use
 * `model.listen(from = clip)` or `model.session()`), no accelerator, and no app lifecycle: call
 * `client.background()` and `client.foreground()` when the app has one. Set `options.appId` and
 * `options.appVersion` to the app's own.
 */
/** The process's ABI as an error report names it: `arm64`, `x86_64` (`x86_64_mac` on a Mac), `x86`. */
internal fun jvmAbi(arch: String? = System.getProperty("os.arch"), os: String? = System.getProperty("os.name")): String? {
    val a = arch?.lowercase() ?: return null
    val mac = os?.lowercase()?.startsWith("mac") == true
    return when (a) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> if (mac) "x86_64_mac" else "x86_64"
        "x86", "i386", "i686" -> "x86"
        else -> null
    }
}

@JvmOverloads
public fun TajweedClient(key: String, storage: File, options: TajweedClientOptions = TajweedClientOptions()): TajweedClient {
    val dir = options.storage ?: storage
    val os = System.getProperty("os.version") ?: "1"
    val env = CloudEnvironment(
        storage = dir,
        app = AppIdentity(options.appId ?: "ai.quranlab.tajweedformer.desktop", AppIdentity.cleanVersion(options.appVersion), build = options.appBuild),
        device = DeviceProfile(DeviceProfile.deviceClass(System.getProperty("os.arch") ?: "jvm"), null,
            os.takeWhile { it.isDigit() }.toIntOrNull()?.coerceIn(1, 99) ?: 1, os.take(32), abi = jvmAbi()),
        contributorKeys = FileContributorKeyStore(File(dir, "keys")),
        secretBox = PlainSecretBox,
    )
    // SDK 4.0: the install's keys are software P-256 keys in the storage folder, and the
    // boot's id is the system's (Linux) or the boot's start to the minute
    return TajweedClient(key, options, ClientPlatform(cloud = env, bootId = bootId()))
}

/** The boot's id for the Core's clock: Linux's boot_id, else the boot's start to the minute (SHA-256 of either). */
internal fun bootId(): ByteArray {
    val text = try {
        File("/proc/sys/kernel/random/boot_id").takeIf { it.isFile }?.readText()?.trim()
    } catch (_: Exception) {
        null
    } ?: "boot-at:${(System.currentTimeMillis() - System.nanoTime() / 1_000_000) / 60_000}"
    return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
}
