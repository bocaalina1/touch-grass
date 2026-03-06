package com.example.digitalwellbeing

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AppSession::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): AppSessionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "touchGrass_db"
                ).build().also { INSTANCE = it }
            }
    }
}