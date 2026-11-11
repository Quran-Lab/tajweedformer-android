package ai.quranlab.tajweedformer.cloud.android

import ai.quranlab.tajweedformer.cloud.IntegrityProvider
import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import java.util.concurrent.TimeUnit

/**
 * Play Integrity's STANDARD request for the attested token (PLATFORM_CLIENT.md section 2): the
 * provider is prepared once with Quran Lab's Google Cloud project number, then each token request
 * carries `requestHash` = base64url SHA-256 of the server's challenge. The SDK compiles against
 * `com.google.android.play:integrity` without shipping it: an app that wants attested sharing adds
 * that dependency; without it [create] returns null and sharing uses only what the server allows
 * unattested (nothing, at the default `attested` tier).
 */
internal class PlayIntegrity private constructor(private val context: Context, private val cloudProjectNumber: Long) : IntegrityProvider {
    @Volatile private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    private fun provider(): StandardIntegrityManager.StandardIntegrityTokenProvider {
        provider?.let { return it }
        val m = IntegrityManagerFactory.createStandard(context.applicationContext)
        val p = Tasks.await(m.prepareIntegrityToken(
            StandardIntegrityManager.PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build()), 60, TimeUnit.SECONDS)
        provider = p
        return p
    }

    override fun token(requestHash: String): String {
        val t = try {
            Tasks.await(provider().request(StandardIntegrityManager.StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()),
                60, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // the prepared provider can expire: prepare again once
            provider = null
            Tasks.await(provider().request(StandardIntegrityManager.StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()),
                60, TimeUnit.SECONDS)
        }
        return t.token()
    }

    companion object {
        /** The provider, or null when the app does not include Play Integrity or no project number was given. */
        @JvmStatic
        fun create(context: Context, cloudProjectNumber: Long?): IntegrityProvider? {
            if (cloudProjectNumber == null) return null
            return try {
                Class.forName("com.google.android.play.core.integrity.IntegrityManagerFactory")
                Class.forName("com.google.android.gms.tasks.Tasks")
                PlayIntegrity(context, cloudProjectNumber)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
