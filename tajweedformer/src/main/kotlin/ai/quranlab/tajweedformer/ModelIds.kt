package ai.quranlab.tajweedformer

import java.util.concurrent.ConcurrentHashMap

/**
 * Build ids and the versions they are builds of (SPEC 2a). Inside the SDK a model is identified by its BUILD (the
 * first 40 hex of the SHA-256 of its signed manifest): the promotion key, the files on disk and every internal event
 * carry it. What leaves the device names the VERSION (`x.y.z`) and, next to it, the build: telemetry cohorts and
 * promotions, incident reports, the shared-session JSON. This is where one is looked up from the other; a manifest is
 * noted here when it verified ([ManifestVerifier.verify]).
 */
internal object ModelIds {
    private val versions = ConcurrentHashMap<String, String>()

    fun note(m: ModelManifest) {
        val v = m.version ?: return
        versions[m.build] = v
    }

    /** The version of the build [id]; [id] itself when it is not a known build (a 2.x style revision in a test). */
    fun version(id: String?): String = id?.let { versions[it] ?: it } ?: ""

    /** [id] when it is a known build, else empty (nothing to report next to the version). */
    fun build(id: String?): String = if (id != null && versions.containsKey(id)) id else ""
}
