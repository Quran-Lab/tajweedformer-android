package ai.quranlab.tajweedformer.android

import ai.quranlab.tajweedformer.BackgroundDownloads
import ai.quranlab.tajweedformer.Continued
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The model download goes on when the learner leaves the app or the system ends its process (SDK 3.1.3): when a
 * download of the model starts (`client.model()`, `prefetch`), one WorkManager job is enqueued, as the accelerator's
 * preparation and the telemetry already are. Its worker ([ModelDownloadWorker]) continues the download from what is on
 * the device (the complete ranges of the large file) once the job runs: at once in the app's process, where it waits
 * for the app's own call and keeps the process from being frozen or reclaimed first while the download runs; in a new
 * process when the old one was ended (then with the client made from the options saved last time).
 *
 * Why this and nothing heavier: a regular job needs no permission, no manifest entry in the app, no foreground service
 * and no notification, and works from API 26 on. The system gives a running job about ten minutes and then stops and
 * reschedules it; a CPU install (55 MB) takes 22 s on a typical 4G link and about 90 s on a weak one, and a stopped
 * job continues from the complete ranges. A user-initiated data transfer job (Android 14) or a `dataSync` foreground
 * service would run longer, but each needs a permission in the app's manifest and a notification.
 *
 * The network rule is the call's: `prefetch(unmeteredOnly = true)` waits for an unmetered network, the rest goes on
 * any network. A download that fails for good (a refusal, the daily limit) is not continued, and nothing is once the
 * model was removed. One job at a time (unique work, replaced by the latest call).
 */
internal class ModelDownloads(context: Context) : BackgroundDownloads {
    private val app = context.applicationContext
    private val wm by lazy { WorkManager.getInstance(app) }

    override fun owe(unmeteredOnly: Boolean) {
        try {
            wm.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build())
        } catch (_: Exception) {
            // WorkManager not initialised in this process (an app's own configuration that is not ready): the download
            // goes on while the process does, and the next call continues it
        }
    }

    override fun settled() {
        try { wm.cancelUniqueWork(WORK) } catch (_: Exception) {}
    }

    companion object {
        /** The unique work of the model download. */
        const val WORK = "tajweedformer-model-download"

        /** Runs after which the job gives up until the app asks again (backoff 30 s, doubling). */
        const val MAX_RUNS = 8
    }
}

/**
 * Continues the model download the app started ([ai.quranlab.tajweedformer.TajweedClient.continueDownload]): in the
 * app's process with the app's client, or in a process WorkManager started, with the client made from the options
 * saved last time ([AndroidPlatform.restore]). Its class name is kept in WorkManager's database: it stays.
 */
internal class ModelDownloadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val c = AndroidPlatform.restore(applicationContext) ?: return Result.success()
        val debug = applicationContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debug) DebugHooks.log("SDK job started in process ${android.os.Process.myPid()} (run ${runAttemptCount + 1})")
        val did = c.continueDownload()
        if (debug) DebugHooks.log("SDK job $did in process ${android.os.Process.myPid()}")
        return when (did) {
            Continued.RETRY -> if (runAttemptCount + 1 < ModelDownloads.MAX_RUNS) Result.retry() else Result.success()
            Continued.DONE, Continued.GAVE_UP -> Result.success()
        }
    }
}
