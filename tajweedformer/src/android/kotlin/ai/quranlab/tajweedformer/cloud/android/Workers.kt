package ai.quranlab.tajweedformer.cloud.android

import ai.quranlab.tajweedformer.android.AndroidPlatform
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

// The class names are what WorkManager keeps in its database, also across an upgrade from 2.x: they stay.

/** Telemetry reports and issue reports. */
internal class DeliveryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val c = AndroidPlatform.restore(applicationContext) ?: return Result.success()
        c.cloud.deliver()
        return Result.success()
    }
}

/** The sharing outbox: drains while there is network; retried with backoff while something is left. */
internal class OutboxWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val c = AndroidPlatform.restore(applicationContext) ?: return Result.success()
        val sharing = c.cloud.sharing
        sharing.outbox.drain()
        return if (sharing.active && sharing.outbox.hasWork()) Result.retry() else Result.success()
    }
}
