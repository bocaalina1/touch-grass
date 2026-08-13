package com.example.digitalwellbeing

import android.app.usage.UsageStatsManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class StatsActivity : AppCompatActivity() {

    private val appColors = mapOf(
        "com.whatsapp"          to Color.parseColor("#25D366"),
        "com.instagram.android" to Color.parseColor("#E1306C"),
        "com.naver.linewebtoon" to Color.parseColor("#00B259"),
        "com.youtube.android"   to Color.parseColor("#FF0000")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        val chartToday = findViewById<BarChart>(R.id.chartToday)
        val chartWeek  = findViewById<BarChart>(R.id.chartWeek)
        val chartMonth = findViewById<BarChart>(R.id.chartMonth)

        setupChartAppearance(chartToday)
        setupChartAppearance(chartWeek)
        setupChartAppearance(chartMonth)

        lifecycleScope.launch {
            loadDayChart(chartToday)
            loadWeekChart(chartWeek)
            loadMonthChart(chartMonth)
        }
    }

    // -------------------------------------------------------------------------
    // Chart loaders — all read directly from UsageStatsManager.
    // This means data survives reinstall and is always accurate.
    // -------------------------------------------------------------------------

    private suspend fun loadDayChart(chart: BarChart) {
        val todayMidnight = midnightOf(Calendar.getInstance())
        val totals = withContext(Dispatchers.IO) {
            queryTotalsMs(todayMidnight, System.currentTimeMillis())
                .mapValues { it.value / 1000f / 60f }
        }

        val activeApps = appColors.keys.filter { (totals[it] ?: 0f) > 0f }
        if (activeApps.isEmpty()) { showNoData(chart); return }

        val appNames = activeApps.map { getAppName(it) }
        val entries  = activeApps.mapIndexed { i, pkg -> BarEntry(i.toFloat(), totals[pkg] ?: 0f) }

        val dataSet = BarDataSet(entries, "Today").apply {
            colors = activeApps.map { appColors[it]!! }
            setDrawValues(true)
            valueTextColor = Color.parseColor("#2E7D32")
            valueTextSize  = 11f
            valueFormatter = minuteFormatter()
        }

        chart.apply {
            data = BarData(dataSet).apply { barWidth = 0.5f }
            legend.isEnabled = false
            xAxis.apply {
                valueFormatter  = indexFormatter(appNames)
                position        = XAxis.XAxisPosition.BOTTOM
                granularity     = 1f
                setDrawGridLines(false)
                textColor   = Color.parseColor("#2E7D32")
                textSize    = 11f
                labelCount  = activeApps.size
                axisMinimum = -0.5f
                axisMaximum = activeApps.size - 0.5f
            }
            axisLeft.apply {
                textColor      = Color.parseColor("#2E7D32")
                axisMinimum    = 0f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "${value.toInt()}m"
                }
            }
            axisRight.isEnabled = false
            animateY(600)
            invalidate()
        }
    }

    private suspend fun loadWeekChart(chart: BarChart) {
        val weekStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
        }

        val dayLabels = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        // buckets[dayOfWeek 0=Sun..6=Sat][pkg] = minutes
        val buckets = Array(7) { mutableMapOf<String, Float>() }

        withContext(Dispatchers.IO) {
            for (dayOffset in 0..6) {
                val dayStart = (weekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
                val dayEnd   = (dayStart.clone()  as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
                if (dayStart.timeInMillis > System.currentTimeMillis()) break

                val totals = queryTotalsMs(
                    dayStart.timeInMillis,
                    minOf(dayEnd.timeInMillis, System.currentTimeMillis())
                )
                val bucketIndex = dayStart.get(Calendar.DAY_OF_WEEK) - 1  // 0=Sun
                totals.forEach { (pkg, ms) ->
                    if (appColors.containsKey(pkg))
                        buckets[bucketIndex][pkg] = (buckets[bucketIndex][pkg] ?: 0f) + ms / 1000f / 60f
                }
            }
        }

        renderStackedChart(chart, dayLabels, buckets)
    }

    private suspend fun loadMonthChart(chart: BarChart) {
        val monthStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
        }

        val weekLabels  = listOf("Week 1", "Week 2", "Week 3", "Week 4", "Week 5")
        val buckets     = Array(5) { mutableMapOf<String, Float>() }

        withContext(Dispatchers.IO) {
            val daysInMonth = monthStart.getActualMaximum(Calendar.DAY_OF_MONTH)
            for (dayOffset in 0 until daysInMonth) {
                val dayStart = (monthStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
                val dayEnd   = (dayStart.clone()   as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
                if (dayStart.timeInMillis > System.currentTimeMillis()) break

                val totals = queryTotalsMs(
                    dayStart.timeInMillis,
                    minOf(dayEnd.timeInMillis, System.currentTimeMillis())
                )
                val weekIndex = (dayOffset / 7).coerceAtMost(4)
                totals.forEach { (pkg, ms) ->
                    if (appColors.containsKey(pkg))
                        buckets[weekIndex][pkg] = (buckets[weekIndex][pkg] ?: 0f) + ms / 1000f / 60f
                }
            }
        }

        renderStackedChart(chart, weekLabels, buckets)
    }

    // -------------------------------------------------------------------------
    // Shared stacked bar renderer
    // -------------------------------------------------------------------------

    private fun renderStackedChart(
        chart: BarChart,
        xLabels: List<String>,
        buckets: Array<MutableMap<String, Float>>
    ) {
        val activeApps = appColors.keys.filter { pkg -> buckets.any { (it[pkg] ?: 0f) > 0f } }
        if (activeApps.isEmpty()) { showNoData(chart); return }

        val entries  = xLabels.indices.map { i ->
            BarEntry(i.toFloat(), activeApps.map { pkg -> buckets[i][pkg] ?: 0f }.toFloatArray())
        }
        val appNames = activeApps.map { getAppName(it) }

        val dataSet = BarDataSet(entries, "").apply {
            colors      = activeApps.map { appColors[it]!! }
            stackLabels = appNames.toTypedArray()
            setDrawValues(false)
            valueTextColor = Color.parseColor("#2E7D32")
        }

        chart.apply {
            data = BarData(dataSet).apply { barWidth = 0.6f }
            xAxis.apply {
                valueFormatter  = indexFormatter(xLabels)
                position        = XAxis.XAxisPosition.BOTTOM
                granularity     = 1f
                setDrawGridLines(false)
                textColor   = Color.parseColor("#2E7D32")
                labelCount  = xLabels.size
                axisMinimum = -0.5f
                axisMaximum = xLabels.size - 0.5f
            }
            axisLeft.apply {
                textColor      = Color.parseColor("#2E7D32")
                axisMinimum    = 0f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "${value.toInt()}m"
                }
            }
            axisRight.isEnabled = false
            legend.isEnabled    = true
            legend.textColor    = Color.parseColor("#2E7D32")
            animateY(600)
            invalidate()
        }
    }

    // -------------------------------------------------------------------------
    // UsageStatsManager helper
    // Queries a time window and returns packageName -> total ms in foreground.
    // Aggregates duplicates that INTERVAL_BEST sometimes produces.
    // -------------------------------------------------------------------------
    private fun queryTotalsMs(startMs: Long, endMs: Long): Map<String, Long> {
        val usm   = getSystemService(UsageStatsManager::class.java)
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startMs, endMs)
        val totals = mutableMapOf<String, Long>()
        stats?.forEach { stat ->
            if (stat.totalTimeInForeground > 0)
                totals[stat.packageName] = (totals[stat.packageName] ?: 0L) + stat.totalTimeInForeground
        }
        return totals
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun setupChartAppearance(chart: BarChart) {
        chart.setDrawGridBackground(false)
        chart.setDrawBarShadow(false)
        chart.description.isEnabled = false
        chart.legend.isEnabled      = true
        chart.legend.textColor      = Color.parseColor("#2E7D32")
    }

    private fun showNoData(chart: BarChart) {
        chart.setNoDataText("No data yet 🌱")
        chart.setNoDataTextColor(Color.parseColor("#388E3C"))
        chart.invalidate()
    }

    private fun midnightOf(cal: Calendar): Long = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun getAppName(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) { packageName }
    private fun indexFormatter(labels: List<String>) = object : ValueFormatter() {
        override fun getFormattedValue(value: Float) = labels.getOrElse(value.toInt()) { "" }
    }

    private fun minuteFormatter() = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val mins = value.toInt()
            return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
        }
    }

}