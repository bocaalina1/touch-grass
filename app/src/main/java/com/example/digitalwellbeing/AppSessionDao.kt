package com.example.digitalwellbeing

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AppSessionDao {

    @Insert
    suspend fun insert(session: AppSession)

    @Query("""
        SELECT packageName, SUM(durationSeconds) as totalSeconds
        FROM app_sessions
        WHERE timestamp >= :startOfDay
        GROUP BY packageName
        ORDER BY totalSeconds DESC
    """)
    suspend fun getDailyStats(startOfDay: Long): List<AppUsageStat>

    @Query("""
        SELECT packageName, SUM(durationSeconds) as totalSeconds
        FROM app_sessions
        WHERE timestamp >= :startOfWeek
        GROUP BY packageName
        ORDER BY totalSeconds DESC
    """)
    suspend fun getWeeklyStats(startOfWeek: Long): List<AppUsageStat>

    @Query("SELECT * FROM app_sessions WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getSessionsSince(since: Long): List<AppSession>

}
data class AppUsageStat(
    val packageName: String,
    val totalSeconds: Long
)