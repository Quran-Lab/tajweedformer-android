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
@JvmOverloads
public fun TajweedClient(key: String, storage: File, options: TajweedClientOptions = TajweedClientOptions()): TajweedClient {
    val dir = options.storage ?: storage
    val os = System.getProperty("os.version") ?: "1"
    val env = CloudEnvironment(
        storage = dir,
        app = AppIdentity(options.appId ?: "ai.quranlab.tajweedformer.desktop", AppIdentity.cleanVersion(options.appVersion)),
        device = DeviceProfile(DeviceProfile.deviceClass(System.getProperty("os.arch") ?: "jvm"), null,
            os.takeWhile { it.isDigit() }.toIntOrNull()?.coerceIn(1, 99) ?: 1, os.take(32)),
        contributorKeys = FileContributorKeyStore(File(dir, "keys")),
        secretBox = PlainSecretBox,
    )
    return TajweedClient(key, options, ClientPlatform(cloud = env))
}
