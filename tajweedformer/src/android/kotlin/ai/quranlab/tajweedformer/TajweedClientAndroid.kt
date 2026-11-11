@file:JvmName("TajweedClients")

package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.android.AndroidPlatform
import android.content.Context

/**
 * The client on Android. Make it once (in `Application.onCreate`, or where the app first needs it) and keep it:
 *
 * ```
 * val client = TajweedClient(context, "qlk_v1_...")     // your key from sdk.quranlab.ai
 * ```
 *
 * [context] may be any Context (the application context is kept). The app's id and version, the SDK's storage
 * (`noBackupFilesDir/quranlab`), the foreground and background of the app and the microphone come from it. A second
 * client while the first is not closed is the error `client_exists`; a key that is not a Quran Lab key is
 * `invalid_argument`.
 */
@JvmOverloads
public fun TajweedClient(context: Context, key: String, options: TajweedClientOptions = TajweedClientOptions()): TajweedClient =
    AndroidPlatform.client(context, key, options)
