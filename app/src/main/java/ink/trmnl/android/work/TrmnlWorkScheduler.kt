package ink.trmnl.android.work

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.asFlow
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.squareup.anvil.annotations.optional.SingleIn
import ink.trmnl.android.data.TrmnlDeviceConfigDataStore
import ink.trmnl.android.di.AppScope
import ink.trmnl.android.di.ApplicationContext
import ink.trmnl.android.work.TrmnlImageRefreshWorker.Companion.PARAM_LOAD_NEXT_PLAYLIST_DISPLAY_IMAGE
import ink.trmnl.android.work.TrmnlImageRefreshWorker.Companion.PARAM_REFRESH_WORK_TYPE
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Manages the scheduling and execution of background work.
 *
 * Periodic image refresh is handled by [TrmnlRefreshForegroundService], which allows
 * refresh intervals shorter than the 15-minute minimum imposed by WorkManager.
 * One-time image refresh requests are still dispatched via WorkManager.
 */
@SingleIn(AppScope::class)
class TrmnlWorkScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val trmnlDeviceConfigDataStore: TrmnlDeviceConfigDataStore,
    ) {
        companion object {
            internal const val IMAGE_REFRESH_PERIODIC_WORK_NAME = "trmnl_image_refresh_work_periodic"
            internal const val IMAGE_REFRESH_PERIODIC_WORK_TAG = "trmnl_image_refresh_work_periodic_tag"
            internal const val IMAGE_REFRESH_ONETIME_WORK_NAME = "trmnl_image_refresh_work_onetime"
            internal const val IMAGE_REFRESH_ONETIME_WORK_TAG = "trmnl_image_refresh_work_onetime_tag"
        }

        /** Tracks whether the foreground service is running. */
        private val _foregroundServiceRunning = MutableStateFlow(false)

        /** The current interval (in seconds) the foreground service was started with. */
        private var currentForegroundIntervalSeconds: Long = 0L

        /**
         * Start the foreground service for periodic image refresh.
         *
         * This replaces the previous WorkManager-based periodic scheduling to allow
         * refresh intervals below 15 minutes (e.g., 60 seconds).
         *
         * @param intervalSeconds The desired refresh interval in seconds
         */
        fun scheduleImageRefreshWork(intervalSeconds: Long) {
            Timber.d("Scheduling foreground service refresh: $intervalSeconds seconds")

            if (trmnlDeviceConfigDataStore.hasTokenSync().not()) {
                Timber.w("Token not set, skipping foreground service start")
                return
            }

            // Cancel any leftover WorkManager periodic work from before the migration
            WorkManager.getInstance(context).cancelUniqueWork(IMAGE_REFRESH_PERIODIC_WORK_NAME)

            val intent = TrmnlRefreshForegroundService.newIntent(context, intervalSeconds)
            ContextCompat.startForegroundService(context, intent)

            currentForegroundIntervalSeconds = intervalSeconds
            _foregroundServiceRunning.value = true
        }

        /**
         * Start a one-time image refresh work immediately.
         *
         * This method:
         * - Executes immediately (subject to network constraints)
         * - Requires a valid token to be set, otherwise work is skipped
         * - Replaces any existing one-time work request
         * - Requires network connectivity
         * - Uses exponential backoff for retries
         *
         * @param loadNextPlaylistImage If true, advances to next playlist item using /api/display endpoint.
         *                              If false, reloads current screen using /api/current_screen endpoint.
         *                              Defaults to false.
         */
        fun startOneTimeImageRefreshWork(loadNextPlaylistImage: Boolean = false) {
            Timber.d("Starting one-time image refresh work with loadNextPlaylistImage: $loadNextPlaylistImage")

            if (trmnlDeviceConfigDataStore.hasTokenSync().not()) {
                Timber.w("Token not set, skipping one-time image refresh work")
                return
            }

            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            val workRequest =
                OneTimeWorkRequestBuilder<TrmnlImageRefreshWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        // Exponential backoff for retrying failed work
                        // Using 60 seconds initial delay (increased from 30s default)
                        BackoffPolicy.EXPONENTIAL,
                        60_000L, // 60 seconds initial backoff
                        TimeUnit.MILLISECONDS,
                    ).setInputData(
                        workDataOf(
                            PARAM_REFRESH_WORK_TYPE to RefreshWorkType.ONE_TIME.name,
                            PARAM_LOAD_NEXT_PLAYLIST_DISPLAY_IMAGE to loadNextPlaylistImage,
                        ),
                    ).addTag(IMAGE_REFRESH_ONETIME_WORK_TAG)
                    .build()

            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(
                    uniqueWorkName = IMAGE_REFRESH_ONETIME_WORK_NAME,
                    existingWorkPolicy = ExistingWorkPolicy.REPLACE,
                    request = workRequest,
                )
        }

        /**
         * Stop the periodic image refresh foreground service.
         *
         * Note: This only stops the foreground service, not one-time work requests.
         */
        fun cancelPeriodicImageRefreshWork() {
            Timber.d("Stopping foreground refresh service")
            context.stopService(Intent(context, TrmnlRefreshForegroundService::class.java))
            _foregroundServiceRunning.value = false
        }

        /**
         * Checks if the foreground refresh service is running.
         * @return Flow of Boolean that emits true if service is running
         */
        fun isImageRefreshWorkScheduled(): Flow<Boolean> = _foregroundServiceRunning

        /**
         * Synchronously checks if image refresh work is scheduled
         * @return true if service is running
         */
        fun isImageRefreshWorkScheduledSync(): Boolean = _foregroundServiceRunning.value

        /**
         * Get the scheduled periodic work info as a Flow.
         *
         * Since periodic work is now handled by the foreground service, this returns
         * a synthetic [WorkInfo] flow for UI compatibility. One-time work info is still
         * available through WorkManager.
         *
         * @return Flow that emits the current WorkInfo for any remaining one-time work, or null
         */
        fun getScheduledWorkInfo(): Flow<WorkInfo?> =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(IMAGE_REFRESH_ONETIME_WORK_NAME)
                .asFlow()
                .map { it.firstOrNull() }

        /**
         * Update the refresh interval for periodic work.
         *
         * This method:
         * - Saves the new interval to the device config data store
         * - Restarts the foreground service with the new interval
         *
         * @param newIntervalSeconds The new refresh interval in seconds
         */
        suspend fun updateRefreshInterval(newIntervalSeconds: Long) {
            Timber.d("Updating refresh interval to $newIntervalSeconds seconds")

            // Save the refresh rate to data store
            trmnlDeviceConfigDataStore.saveRefreshRateSeconds(newIntervalSeconds)

            // Restart foreground service with new interval
            scheduleImageRefreshWork(newIntervalSeconds)
        }
    }
