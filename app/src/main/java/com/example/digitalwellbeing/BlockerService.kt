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

class BlockerService : Service() {

    private val db by lazy { AppDatabase.get(this) }
    private val appLimits = mapOf(
        "com.whatsapp"          to 200L,
        "com.instagram.android" to 30L,
        "com.naver.linewebtoon" to 25L,
        "com.youtube.android"   to 15L
    )

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentForegroundApp: String? = null
    private var sessionStartTime: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        resetDailyUsageIfNewDay()
        sendUsageFromSystemIfEmpty()

        serviceScope.launch {
            while (isActive) {
                checkAndBlock()
                delay(POLL_INTERVAL_MS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        persistCurrentSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkAndBlock() {
        resetDailyUsageIfNewDay()
        val detectedApp = getForegroundApp()

        if (detectedApp != currentForegroundApp) {
            persistCurrentSession()
            sessionStartTime = 0L
            currentForegroundApp = detectedApp
            sessionStartTime = System.currentTimeMillis()

            if (detectedApp != null) {
                val savedSeconds = getSavedUsageSeconds(detectedApp)
                val limitSeconds = (appLimits[detectedApp] ?: 0L) * 60
                if (limitSeconds > 0 && savedSeconds >= limitSeconds) {
                    launchBlocker(detectedApp)
                    return
                }
            }
        }

        val app = detectedApp ?: return
        val limitSeconds = (appLimits[app] ?: return) * 60

        val totalSeconds = elapsedSessionSeconds() + getSavedUsageSeconds(app)

        android.util.Log.d(TAG, "$app — ${totalSeconds / 60}m ${totalSeconds % 60}s / ${limitSeconds / 60}m limit")

        if (totalSeconds >= limitSeconds) {
            launchBlocker(app)
        }
    }

    private fun launchBlocker(packageName: String) {
        startActivity(Intent(this, BlockerActivity::class.java).apply {
            putExtra(BlockerActivity.EXTRA_BLOCKED_APP, packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
    }

    private fun elapsedSessionSeconds(): Long =
        if (sessionStartTime > 0) (System.currentTimeMillis() - sessionStartTime) / 1000 else 0L

    private fun persistCurrentSession() {
        val app = currentForegroundApp ?: return
        val elapsed = elapsedSessionSeconds()
        if (elapsed > 0) {
            addUsageSeconds(app, elapsed)
            serviceScope.launch {
                db.sessionDao().insert(AppSession(
                    packageName = app,
                    durationSeconds = elapsed
                ))
            }
            sessionStartTime = System.currentTimeMillis()
        }
    }

    private fun addUsageSeconds(packageName: String, seconds: Long) {
        val prefs = usagePrefs()
        prefs.edit().putLong(packageName, prefs.getLong(packageName, 0L) + seconds).apply()
    }

    private fun getSavedUsageSeconds(packageName: String): Long =
        usagePrefs().getLong(packageName, 0L)

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

            // Re-seed immediately after clearing, since it's a new day anyway
            seedFromSystem()
        }
    }

    private fun sendUsageFromSystemIfEmpty() {
        val alreadySeeded = usagePrefs().getBoolean("seeded", false)
        if (alreadySeeded) return
        seedFromSystem()
    }

    private fun seedFromSystem() {
        val prefs = usagePrefs()

        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val todayMidnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            todayMidnight,
            System.currentTimeMillis()
        )

        android.util.Log.d(TAG, "Seeding from system, stats count: ${stats.size}")

        // Aggregate duplicates into a map before saving
        val totals = mutableMapOf<String, Long>()

        stats.filter { appLimits.containsKey(it.packageName) }
            .forEach { stat ->
                val seconds = stat.totalTimeInForeground / 1000
                if (seconds > 0) {
                    totals[stat.packageName] = (totals[stat.packageName] ?: 0L) + seconds
                }
            }

        // Save the summed totals to SharedPreferences
        val editor = prefs.edit()
        totals.forEach { (pkg, seconds) ->
            editor.putLong(pkg, seconds)
            serviceScope.launch {
                db.sessionDao().insert(AppSession(
                    packageName = pkg,
                    durationSeconds = seconds,
                    timestamp = System.currentTimeMillis()
                ))
            }
            android.util.Log.d(TAG, "Seeded $pkg: ${seconds / 60}m ${seconds % 60}s")
        }

        editor.putBoolean("seeded", true)
        editor.apply()
    }

    private fun usagePrefs(): SharedPreferences =
        getSharedPreferences(PREFS_USAGE, Context.MODE_PRIVATE)

    private fun getForegroundApp(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 60 * 60 * 1000L, now)
        val event = UsageEvents.Event()
        var lastApp: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> lastApp = event.packageName
                UsageEvents.Event.ACTIVITY_PAUSED  -> if (event.packageName == lastApp) lastApp = null
            }
        }
        return lastApp
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Touch Grass", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Touch Grass")
            .setContentText("Go outside 🌿")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setColorized(true)
            .build()
    }

    companion object {
        private const val TAG = "BlockerService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "blocker_service"
        private const val PREFS_USAGE = "usage"
        private const val KEY_LAST_RESET = "last_reset"
        private const val POLL_INTERVAL_MS = 2_000L
    }
}