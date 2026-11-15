package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WatchHistoryEntity::class,
        SavedVideoEntity::class,
        SubscribedChannelEntity::class,
        SearchHistoryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class XemTubeDatabase : RoomDatabase() {
    abstract fun xemTubeDao(): XemTubeDao

    companion object {
        @Volatile
        private var INSTANCE: XemTubeDatabase? = null

        fun getInstance(context: Context): XemTubeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    XemTubeDatabase::class.java,
                    "xemtube_local.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}
