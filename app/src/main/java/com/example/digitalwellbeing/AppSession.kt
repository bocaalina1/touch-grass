package com.example.digitalwellbeing

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_sessions")
data class AppSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val durationSeconds: Long,
    val timestamp: Long = System.currentTimeMillis()
)