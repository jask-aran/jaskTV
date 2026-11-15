/*
 * jaskTV — GPL-3.0-or-later.
 * Opportunistic cloud sync: periodic Android scheduling, no permanent daemon.
 */
package app.opentv.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.opentv.core.ServiceLocator
import java.util.concurrent.TimeUnit

class CloudSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!CloudSyncPrefs(applicationContext).load().enabled) return Result.success()
        return when (WebDavCloudSync(applicationContext, ServiceLocator.get(applicationContext)).sync()) {
            is WebDavCloudSync.Result.Success -> Result.success()
            is WebDavCloudSync.Result.NotConfigured -> Result.success()
            is WebDavCloudSync.Result.Failed ->
                if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val PERIODIC = "jasktv-cloud-periodic"
        private const val ON_OPEN = "jasktv-cloud-open"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!CloudSyncPrefs(context).load().enabled) {
                wm.cancelUniqueWork(PERIODIC)
                wm.cancelUniqueWork(ON_OPEN)
                return
            }
            val job = PeriodicWorkRequestBuilder<CloudSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints()).build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, job)
        }

        /** Sync on each cold app start, if no previous on-open sync is pending. */
        fun onOpen(context: Context) {
            if (!CloudSyncPrefs(context).load().enabled) return
            val job = OneTimeWorkRequestBuilder<CloudSyncWorker>()
                .setConstraints(constraints()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(ON_OPEN, ExistingWorkPolicy.KEEP, job)
        }
    }
}
