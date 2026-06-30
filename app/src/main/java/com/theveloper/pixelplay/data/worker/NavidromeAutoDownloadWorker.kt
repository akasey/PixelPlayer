package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.theveloper.pixelplay.data.database.DownloadSource
import com.theveloper.pixelplay.data.navidrome.NavidromeCacheManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Performs a play-count-triggered auto-download of a Navidrome song. Decoupled from the moment of
 * playback completion so WorkManager can hold the job behind a network constraint: when WiFi-only
 * is enabled the work waits for an un-metered connection, so a song that crossed the threshold
 * offline still downloads automatically once the device is back on WiFi — replacing the old
 * fire-and-forget path that silently skipped off-WiFi completions.
 */
@HiltWorker
class NavidromeAutoDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val cacheManager: NavidromeCacheManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val navidromeId = inputData.getString(KEY_NAVIDROME_ID)
            ?: return Result.failure()

        return try {
            cacheManager.downloadSong(navidromeId, DownloadSource.AUTO)
            Result.success()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "NavidromeAutoDownloadWorker: failed for %s", navidromeId)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "NavidromeAutoDLWorker"
        const val KEY_NAVIDROME_ID = "navidrome_id"

        private fun uniqueName(navidromeId: String) = "navidrome_auto_download_$navidromeId"

        /**
         * Enqueues a unique auto-download for [navidromeId]. [wifiOnly] maps to an un-metered
         * network constraint (which also excludes metered WiFi / Data Saver); otherwise any
         * connection is sufficient. [ExistingWorkPolicy.KEEP] de-duplicates repeated completions.
         */
        fun enqueue(context: Context, navidromeId: String, wifiOnly: Boolean) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<NavidromeAutoDownloadWorker>()
                .setInputData(workDataOf(KEY_NAVIDROME_ID to navidromeId))
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName(navidromeId), ExistingWorkPolicy.KEEP, request)
        }
    }
}
