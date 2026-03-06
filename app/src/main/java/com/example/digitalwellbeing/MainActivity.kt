package com.example.digitalwellbeing

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import java.util.Calendar
import android.widget.Button
/**
 * Entry point of the app. Requests the necessary permissions, starts the
 * [BlockerService], and displays today's per-app usage statistics.
 */
class MainActivity : AppCompatActivity() {

    // Daily limits (minutes) shown alongside usage stats — must mirror BlockerService
    private val appLimits = mapOf(
        "com.whatsapp"          to 200L,
        "com.instagram.android" to 30L,
        "com.naver.linewebtoon" to 25L,
        "com.youtube.android"   to 15L,
        "com.android.settings" to 1L
    )

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        if (hasUsageStatsPermission()) {
            startService(Intent(this, BlockerService::class.java))  // move here
            displayUsageStats()
        } else {
            showPermissionPrompt()
            // only open settings if we haven't already
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Only request overlay + notification here, NOT usage stats
        requestOverlayAndNotificationPermissions()

        findViewById<Button>(R.id.btnStats).setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
    }
    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------
    private fun requestOverlayAndNotificationPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
        }
    }
    private fun requestRequiredPermissions() {
        if (!hasUsageStatsPermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------

    private fun displayUsageStats() {
        val stats = getTodayUsageStats()
        val pm = packageManager
        val sb = StringBuilder()

        stats.entries
            .sortedByDescending { it.value }
            .forEach { (pkg, timeMs) ->
                val minutes = timeMs / 1_000 / 60
                val appName = getAppLabel(pm, pkg)
                val limitText = appLimits[pkg]?.let { " / ${it}m limit" } ?: ""
                sb.appendLine("$pkg — ${minutes}m$limitText")
            }

        findViewById<TextView>(R.id.tvStats).text =
            if (sb.isEmpty()) "No usage data yet today." else sb.toString()
    }

    private fun showPermissionPrompt() {
        findViewById<TextView>(R.id.tvStats).text =
            "Usage Access permission is required.\nPlease grant it in Settings and reopen the app."
    }

    // -------------------------------------------------------------------------
    // Usage data
    // -------------------------------------------------------------------------

    /**
     * Returns today's foreground usage per package (in milliseconds),
     * filtered to apps with non-zero usage.
     */
    private fun getTodayUsageStats(): Map<String, Long> {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            startOfDay,
            System.currentTimeMillis()
        )

        val totals = mutableMapOf<String, Long>()
        stats.forEach { stat ->
            if (stat.totalTimeInForeground > 0) {
                totals[stat.packageName] = (totals[stat.packageName] ?: 0L) + stat.totalTimeInForeground
            }
        }

        return totals
    }
    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun getAppLabel(pm: PackageManager, packageName: String): String = try {
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName // Fall back to package name if app is not installed
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 100
    }
}