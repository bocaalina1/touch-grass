package com.example.digitalwellbeing

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Full-screen wall shown when the user has exceeded their daily limit for an app.
 *
 * The back button is intentionally disabled so the user must navigate away via
 * the home/recents button — making it meaningfully harder to dismiss.
 */
class BlockerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val blockedApp = intent.getStringExtra(EXTRA_BLOCKED_APP) ?: "that app"

        setContentView(buildLayout(blockedApp))

        // Trap the back button — the user must use the home or recents gesture
        onBackPressedDispatcher.addCallback(this) { /* intentionally empty */ }
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------

    /**
     * Builds the blocker screen programmatically so it has no dependency on
     * a layout XML file and cannot be accidentally bypassed by an Activity restart.
     */
    private fun buildLayout(blockedPackage: String): LinearLayout {
        val appName = getFriendlyAppName(blockedPackage)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFE8F5E9.toInt()) // light green background
            setPadding(64, 64, 64, 64)

            addView(TextView(context).apply {
                text = "🌿"
                textSize = 64f
                gravity = Gravity.CENTER
            })

            addView(TextView(context).apply {
                text = "Time's up for $appName"
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(0xFF2E7D32.toInt()) // dark green
                setPadding(0, 48, 0, 24)
            })

            addView(TextView(context).apply {
                text = "You've reached your daily limit.\nGo touch some grass — your future self will thank you. 🌱"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(0xFF388E3C.toInt()) // medium green
            })
        }
    }
    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun getFriendlyAppName(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        packageName
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    companion object {
        /** Intent extra: the package name of the blocked app. */
        const val EXTRA_BLOCKED_APP = "blocked_app"
    }
}