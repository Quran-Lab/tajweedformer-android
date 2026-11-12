package ai.quranlab.tajweedformer.cloud.android

import ai.quranlab.tajweedformer.cloud.IntegrityProvider
import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import java.util.concurrent.TimeUnit

/**
 * Play Integrity's STANDARD request for the attested token (PLATFORM_CLIENT.md section 2, SPEC 15.4): the provider is
 * prepared once per Cloud project number, then each token request carries `requestHash` = base64url SHA-256 of the
 * server's challenge. The project number comes from Quran Lab's signed config (`flags.play_integrity_project_number`):
 * without it the SDK never calls this. The AAR depends on `com.google.android.play:integrity` since 3.1.0. Where Play
 * services are missing or Play cannot answer, a call throws and the SDK continues with an unattested token.
 */
internal class PlayIntegrity private constructor(private val context: Context) : IntegrityProvider {
    @Volatile private var prepared: Pair<Long, StandardIntegrityManager.StandardIntegrityTokenProvider>? = null

    private fun provider(project: Long): StandardIntegrityManager.StandardIntegrityTokenProvider {
        prepared?.let { (n, p) -> if (n == project) return p }
        val m = IntegrityManagerFactory.createStandard(context.applicationContext)
        val p = Tasks.await(m.prepareIntegrityToken(
            StandardIntegrityManager.PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(project).build()), 60, TimeUnit.SECONDS)
        prepared = project to p
        return p
    }

    override fun token(requestHash: String, cloudProjectNumber: Long): String {
        val request = StandardIntegrityManager.StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()
        val t = try {
            Tasks.await(provider(cloudProjectNumber).request(request), 60, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // the prepared provider can expire: prepare again once
            prepared = null
            Tasks.await(provider(cloudProjectNumber).request(request), 60, TimeUnit.SECONDS)
        }
        return t.token()
    }

    companion object {
        /** The provider, or null when Play Integrity's classes are not in the app (an app that excluded the dependency). */
        @JvmStatic
        fun create(context: Context): IntegrityProvider? = try {
            Class.forName("com.google.android.play.core.integrity.IntegrityManagerFactory")
            Class.forName("com.google.android.gms.tasks.Tasks")
            PlayIntegrity(context)
        } catch (_: Throwable) {
            null
        }
    }
}
