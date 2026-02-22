package com.example.digitalwellbeing

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import kotlinx.coroutines.*
import java.util.Calendar

/**
 * Background service that monitors foreground app usage and enforces daily time limits.
 *
 * Usage is tracked in SharedPreferences (in seconds). When an app exceeds its limit,
 * [BlockerActivity] is launched to prevent further use until midnight.
 */
class BlockerService : Service() {

    // Daily limits in minutes, keyed by package name
    private val appLimits = mapOf(
        "com.whatsapp"          to 200L,
        "com.instagram.android" to 30L,
        "com.naver.linewebtoon" to 25L,
        "com.youtube.android"   to 15L
    )

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var currentForegroundApp: String? = null
    private var sessionStartTime: Long = 0L

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        resetDailyUsageIfNewDay()

        serviceScope.launch {
            while (isActive) {
                checkAndBlock()
                delay(POLL_INTERVAL_MS)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        // Flush the current session so usage isn't lost if the service is killed
        persistCurrentSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Core logic
    // -------------------------------------------------------------------------

    /**
     * Detects the current foreground app, accumulates usage, and launches the
     * blocker screen if a limit has been exceeded.
     */
    private fun checkAndBlock() {
        val detectedApp = getForegroundApp()

        // App switch detected — persist the outgoing session
        if (detectedApp != currentForegroundApp) {
            persistCurrentSession()
            currentForegroundApp = detectedApp
            sessionStartTime = System.currentTimeMillis()

            // Check saved usage immediately on switch (handles already-exceeded limits)
            if (detectedApp != null && isLimitExceeded(detectedApp, sessionSeconds = 0)) {
                launchBlocker(detectedApp)
                return
            }
        }

        val app = detectedApp ?: return
        val limitMinutes = appLimits[app] ?: return

        val sessionSeconds = elapsedSessionSeconds()
        val totalSeconds = sessionSeconds + getSavedUsageSeconds(app)

        android.util.Log.d(
            TAG,
            "$app — ${totalSeconds / 60}m ${totalSeconds % 60}s / ${limitMinutes}m limit"
        )

        if (totalSeconds >= limitMinutes * 60) {
            launchBlocker(app)
        }
    }

    private fun launchBlocker(packageName: String) {
        val intent = Intent(this, BlockerActivity::class.java).apply {
            putExtra(BlockerActivity.EXTRA_BLOCKED_APP, packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    // -------------------------------------------------------------------------
    // Usage tracking helpers
    // -------------------------------------------------------------------------

    private fun isLimitExceeded(packageName: String, sessionSeconds: Long): Boolean {
        val limitMinutes = appLimits[packageName] ?: return false
        val totalSeconds = sessionSeconds + getSavedUsageSeconds(packageName)
        return totalSeconds >= limitMinutes * 60
    }

    private fun elapsedSessionSeconds(): Long =
        if (sessionStartTime > 0) (System.currentTimeMillis() - sessionStartTime) / 1000 else 0L

    /** Saves the current in-memory session to SharedPreferences and resets state. */
    private fun persistCurrentSession() {
        val app = currentForegroundApp ?: return
        val elapsed = elapsedSessionSeconds()
        if (elapsed > 0) addUsageSeconds(app, elapsed)
    }

    private fun addUsageSeconds(packageName: String, seconds: Long) {
        val prefs = usagePrefs()
        val current = prefs.getLong(packageName, 0L)
        prefs.edit().putLong(packageName, current + seconds).apply()
    }

    private fun getSavedUsageSeconds(packageName: String): Long =
        usagePrefs().getLong(packageName, 0L)

    /**
     * Clears all stored usage data at the start of a new calendar day.
     */
    private fun resetDailyUsageIfNewDay() {
        val prefs = usagePrefs()
        val lastReset = prefs.getLong(KEY_LAST_RESET, 0L)
        val todayMidnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        if (lastReset < todayMidnight) {
            prefs.edit()
                .clear()
                .putLong(KEY_LAST_RESET, System.currentTimeMillis())
                .apply()
        }
    }

    private fun usagePrefs(): SharedPreferences =
        getSharedPreferences(PREFS_USAGE, Context.MODE_PRIVATE)

    // -------------------------------------------------------------------------
    // Foreground app detection
    // -------------------------------------------------------------------------

    /**
     * Returns the most recently foregrounded package name within the last
     * [USAGE_QUERY_WINDOW_MS] milliseconds, or null if none detected.
     */
    private fun getForegroundApp(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - USAGE_QUERY_WINDOW_MS, now)
        val event = UsageEvents.Event()
        var lastApp: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastApp = event.packageName
            }
        }
        return lastApp
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App Blocker",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Digital Wellbeing Active")
            .setContentText("Monitoring your daily app limits")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "BlockerService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "blocker_service"
        private const val PREFS_USAGE = "usage"
        private const val KEY_LAST_RESET = "last_reset"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val USAGE_QUERY_WINDOW_MS = 30_000L
    }
}