package com.example.digitalwellbeing

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.launch
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
        setContentView(R.layout.activity_stats) // Link to XML!

        val chartToday = findViewById<BarChart>(R.id.chartToday)
        val chartWeek = findViewById<BarChart>(R.id.chartWeek)
        val chartMonth = findViewById<BarChart>(R.id.chartMonth)

        // Setup base chart appearance
        setupChartAppearance(chartToday)
        setupChartAppearance(chartWeek)
        setupChartAppearance(chartMonth)

        // Load data into charts
        lifecycleScope.launch {
            loadChart(chartToday, "day")
            loadChart(chartWeek, "week")
            loadChart(chartMonth, "month")
        }
    }

    private fun setupChartAppearance(chart: BarChart) {
        chart.setDrawGridBackground(false)
        chart.setDrawBarShadow(false)
        chart.description.isEnabled = false
        chart.legend.isEnabled = true
        chart.legend.textColor = Color.parseColor("#2E7D32")
    }

    private suspend fun loadChart(chart: BarChart, period: String) {
        val db = AppDatabase.get(this)
        val pm = packageManager

        val (startTime, xLabels, bucketFn) = when (period) {
            "week" -> {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                Triple(cal.timeInMillis, listOf("Sun","Mon","Tue","Wed","Thu","Fri","Sat"),
                    { ts: Long ->
                        val c = Calendar.getInstance().apply { timeInMillis = ts }
                        c.get(Calendar.DAY_OF_WEEK) - 1
                    })
            }
            "month" -> {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                Triple(cal.timeInMillis, listOf("Week 1","Week 2","Week 3","Week 4"),
                    { ts: Long ->
                        val c = Calendar.getInstance().apply { timeInMillis = ts }
                        (c.get(Calendar.DAY_OF_MONTH) - 1) / 7
                    })
            }
            else -> { // day: one bar per app, no time buckets
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                Triple(cal.timeInMillis, emptyList<String>(), { _: Long -> 0 })
            }
        }

        val sessions = db.sessionDao().getSessionsSince(startTime)
        val appOrder = appColors.keys.toList()

        if (period == "day") {
            // Sum total minutes per app today
            val totals = mutableMapOf<String, Float>()
            sessions.forEach { session ->
                totals[session.packageName] = (totals[session.packageName] ?: 0f) + session.durationSeconds / 60f
            }

            val activeApps = appOrder.filter { (totals[it] ?: 0f) > 0f }

            if (activeApps.isEmpty()) {
                chart.setNoDataText("No data yet 🌱")
                chart.setNoDataTextColor(Color.parseColor("#388E3C"))
                chart.invalidate()
                return
            }

            val appNames = activeApps.map { pkg ->
                try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                catch (e: Exception) { pkg }
            }

            val entries = activeApps.mapIndexed { i, pkg ->
                BarEntry(i.toFloat(), totals[pkg] ?: 0f)
            }

            val dataSet = BarDataSet(entries, "Today").apply {
                colors = activeApps.map { appColors[it] ?: Color.parseColor("#81C784") }
                setDrawValues(true)
                valueTextColor = Color.parseColor("#2E7D32")
                valueTextSize = 11f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        val mins = value.toInt()
                        return if (mins >= 60) "${mins/60}h ${mins%60}m" else "${mins}m"
                    }
                }
            }

            val barData = BarData(dataSet).apply { barWidth = 0.5f }
            chart.data = barData
            chart.legend.isEnabled = false

            chart.xAxis.apply {
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) =
                        appNames.getOrElse(value.toInt()) { "" }
                }
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                setDrawGridLines(false)
                textColor = Color.parseColor("#2E7D32")
                textSize = 11f
                labelCount = activeApps.size
                axisMinimum = -0.5f
                axisMaximum = activeApps.size - 0.5f
            }

            chart.axisLeft.apply {
                textColor = Color.parseColor("#2E7D32")
                axisMinimum = 0f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "${value.toInt()}m"
                }
            }
            chart.axisRight.isEnabled = false
            chart.animateY(600)
            chart.invalidate()
            return
        }

        // Week and Month: stacked bars per bucket
        val bucketData = mutableMapOf<Int, MutableMap<String, Float>>()
        sessions.forEach { session ->
            val bucket = bucketFn(session.timestamp)
            val appMap = bucketData.getOrPut(bucket) { mutableMapOf() }
            appMap[session.packageName] = (appMap[session.packageName] ?: 0f) + session.durationSeconds / 60f
        }

        val activeApps = appOrder.filter { pkg ->
            bucketData.values.any { (it[pkg] ?: 0f) > 0f }
        }

        if (activeApps.isEmpty()) {
            chart.setNoDataText("No data yet 🌱")
            chart.setNoDataTextColor(Color.parseColor("#388E3C"))
            chart.invalidate()
            return
        }

        val entries = xLabels.indices.map { i ->
            BarEntry(i.toFloat(), activeApps.map { pkg -> bucketData[i]?.get(pkg) ?: 0f }.toFloatArray())
        }

        val appNames = activeApps.map { pkg ->
            try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            catch (e: Exception) { pkg }
        }

        val dataSet = BarDataSet(entries, "").apply {
            colors = activeApps.map { appColors[it] ?: Color.parseColor("#81C784") }
            stackLabels = appNames.toTypedArray()
            setDrawValues(false)
            valueTextColor = Color.parseColor("#2E7D32")
        }

        val barData = BarData(dataSet).apply { barWidth = 0.6f }
        chart.data = barData

        chart.xAxis.apply {
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) =
                    xLabels.getOrElse(value.toInt()) { "" }
            }
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f
            setDrawGridLines(false)
            textColor = Color.parseColor("#2E7D32")
            labelCount = xLabels.size
            axisMinimum = -0.5f
            axisMaximum = xLabels.size - 0.5f
        }

        chart.axisLeft.apply {
            textColor = Color.parseColor("#2E7D32")
            axisMinimum = 0f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = "${value.toInt()}m"
            }
        }

        chart.axisRight.isEnabled = false
        chart.legend.isEnabled = true
        chart.legend.textColor = Color.parseColor("#2E7D32")
        chart.animateY(600)
        chart.invalidate()
    }
}