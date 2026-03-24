package ink.trmnl.android.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import ink.trmnl.android.R
import ink.trmnl.android.data.AppConfig
import ink.trmnl.android.data.ImageMetadataStore
import ink.trmnl.android.data.TrmnlDeviceConfigDataStore
import ink.trmnl.android.data.TrmnlDisplayRepository
import ink.trmnl.android.data.log.TrmnlRefreshLogManager
import ink.trmnl.android.model.TrmnlDeviceConfig
import ink.trmnl.android.model.TrmnlDeviceType
import ink.trmnl.android.TrmnlDisplayMirrorApp
import ink.trmnl.android.util.isHttpError
import ink.trmnl.android.util.isHttpOk
import ink.trmnl.android.util.isRateLimitError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that periodically refreshes the TRMNL display image.
 *
 * This replaces the WorkManager periodic scheduling to allow refresh intervals
 * shorter than the 15-minute minimum enforced by WorkManager. The service runs
 * a coroutine loop that fetches images at the configured interval (default 60 seconds).
 *
 * The service reuses the same fetch logic as [TrmnlImageRefreshWorker.doWork].
 */
class TrmnlRefreshForegroundService : Service() {
    companion object {
        private const val TAG = "TrmnlFgService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "trmnl_refresh_channel"

        private const val EXTRA_INTERVAL_SECONDS = "extra_interval_seconds"

        /**
         * Small buffer after each fetch before starting the next cycle.
         * Kept minimal since the server renders images on its own schedule.
         */
        private const val EXTRA_REFRESH_WAIT_TIME_SEC: Long = 0L

        fun newIntent(context: Context, intervalSeconds: Long): Intent =
            Intent(context, TrmnlRefreshForegroundService::class.java).apply {
                putExtra(EXTRA_INTERVAL_SECONDS, intervalSeconds)
            }
    }

    @Inject lateinit var displayRepository: TrmnlDisplayRepository
    @Inject lateinit var trmnlDeviceConfigDataStore: TrmnlDeviceConfigDataStore
    @Inject lateinit var refreshLogManager: TrmnlRefreshLogManager
    @Inject lateinit var trmnlImageUpdateManager: TrmnlImageUpdateManager
    @Inject lateinit var imageMetadataStore: ImageMetadataStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null
    private var currentIntervalSeconds: Long = AppConfig.DEFAULT_REFRESH_INTERVAL_SEC

    override fun onCreate() {
        super.onCreate()
        // Inject dependencies from the app component
        val appComponent = (application as TrmnlDisplayMirrorApp).appComponent()
        appComponent.inject(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val intervalSeconds = intent?.getLongExtra(EXTRA_INTERVAL_SECONDS, AppConfig.DEFAULT_REFRESH_INTERVAL_SEC)
            ?: AppConfig.DEFAULT_REFRESH_INTERVAL_SEC

        Timber.tag(TAG).d("onStartCommand: intervalSeconds=$intervalSeconds")

        startForeground(NOTIFICATION_ID, buildNotification())

        // If the interval changed or the refresh loop is not running, (re)start it
        if (intervalSeconds != currentIntervalSeconds || refreshJob?.isActive != true) {
            currentIntervalSeconds = intervalSeconds
            startRefreshLoop(intervalSeconds)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Timber.tag(TAG).d("Service destroyed, cancelling refresh loop")
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startRefreshLoop(intervalSeconds: Long) {
        refreshJob?.cancel()
        refreshJob = serviceScope.launch {
            while (true) {
                try {
                    doRefresh()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error during image refresh")
                }
                val adjustedIntervalMs = (intervalSeconds + EXTRA_REFRESH_WAIT_TIME_SEC) * 1000L
                Timber.tag(TAG).d("Next refresh in ${adjustedIntervalMs / 1000} seconds")
                delay(adjustedIntervalMs)
            }
        }
    }

    /**
     * Core image refresh logic, mirroring [TrmnlImageRefreshWorker.doWork].
     */
    private suspend fun doRefresh() {
        Timber.tag(TAG).d("Starting foreground image refresh")

        val deviceConfig: TrmnlDeviceConfig? = trmnlDeviceConfigDataStore.deviceConfigFlow.firstOrNull()

        if (deviceConfig == null) {
            Timber.tag(TAG).w("Device config and token is not set, skipping image refresh")
            refreshLogManager.addFailureLog("No device config with API token found")
            return
        }

        // Determine whether to advance playlist based on device type
        val shouldAdvancePlaylist = when (deviceConfig.type) {
            TrmnlDeviceType.BYOS -> true
            TrmnlDeviceType.BYOD -> deviceConfig.isMasterDevice ?: true
            TrmnlDeviceType.TRMNL -> false
        }

        val trmnlDisplayInfo = if (shouldAdvancePlaylist) {
            displayRepository.getNextDisplayData(deviceConfig)
        } else {
            displayRepository.getCurrentDisplayData(deviceConfig)
        }

        // Handle rate limit (HTTP 429)
        if (trmnlDisplayInfo.status.isRateLimitError()) {
            Timber.tag(TAG).w("Rate limit exceeded (HTTP 429), will retry next cycle")
            refreshLogManager.addFailureLog(
                error = "Rate limit exceeded (HTTP 429) - Too many requests. Will retry next cycle.",
                httpResponseMetadata = trmnlDisplayInfo.httpResponseMetadata,
            )

            val cachedMetadata = imageMetadataStore.imageMetadataFlow.firstOrNull()
            if (cachedMetadata != null && cachedMetadata.url.isNotBlank()) {
                Timber.tag(TAG).i("Showing cached image during rate limit: ${cachedMetadata.url}")
                imageMetadataStore.saveImageMetadata(
                    imageUrl = cachedMetadata.url,
                    refreshIntervalSec = cachedMetadata.refreshIntervalSecs,
                    httpStatusCode = 429,
                )
                trmnlImageUpdateManager.updateImage(
                    imageUrl = cachedMetadata.url,
                    refreshIntervalSecs = cachedMetadata.refreshIntervalSecs,
                    errorMessage = null,
                )
            }
            return
        }

        // Handle other HTTP errors
        if (trmnlDisplayInfo.status.isHttpError()) {
            Timber.tag(TAG).w("Failed to fetch display data: ${trmnlDisplayInfo.error}")
            refreshLogManager.addFailureLog(
                error = trmnlDisplayInfo.error ?: "Unknown server error",
                httpResponseMetadata = trmnlDisplayInfo.httpResponseMetadata,
            )
            return
        }

        // Validate image URL
        if (trmnlDisplayInfo.imageUrl.isEmpty() || trmnlDisplayInfo.status.isHttpOk().not()) {
            Timber.tag(TAG).w("No image URL provided in response. ${trmnlDisplayInfo.error}")
            refreshLogManager.addFailureLog(
                error = "No image URL provided in response. ${trmnlDisplayInfo.error}",
                httpResponseMetadata = trmnlDisplayInfo.httpResponseMetadata,
            )
            return
        }

        // Success - log and update
        refreshLogManager.addSuccessLog(
            trmnlDeviceType = deviceConfig.type,
            imageUrl = trmnlDisplayInfo.imageUrl,
            imageName = trmnlDisplayInfo.imageFileName,
            refreshIntervalSeconds = trmnlDisplayInfo.refreshIntervalSeconds,
            imageRefreshWorkType = RefreshWorkType.PERIODIC.name,
            httpResponseMetadata = trmnlDisplayInfo.httpResponseMetadata,
        )

        // Check if refresh rate changed and restart loop if needed
        val refreshRate = trmnlDisplayInfo.refreshIntervalSeconds
        refreshRate?.let { newRefreshRateSec ->
            if (trmnlDeviceConfigDataStore.shouldUpdateRefreshRate(newRefreshRateSec)) {
                Timber.tag(TAG).d("Refresh rate changed to $newRefreshRateSec, updating loop")
                trmnlDeviceConfigDataStore.saveRefreshRateSeconds(newRefreshRateSec)
                currentIntervalSeconds = newRefreshRateSec
                // Restart the loop with the new interval
                startRefreshLoop(newRefreshRateSec)
            }
        }

        // Update the image in the UI
        trmnlImageUpdateManager.updateImage(
            trmnlDisplayInfo.imageUrl,
            trmnlDisplayInfo.refreshIntervalSeconds,
        )

        Timber.tag(TAG).i("Image refresh successful, new URL: ${trmnlDisplayInfo.imageUrl}")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
}
