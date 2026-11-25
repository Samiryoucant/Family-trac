package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LocationRecordEntity::class,
        CachedDeviceEntity::class,
        PendingMessageEntity::class,
        PendingGameMoveEntity::class,
        ConsentLogEntity::class,
        SyncStatusEntity::class,
        AppNotificationEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class FamilyTrackDatabase : RoomDatabase() {
    abstract fun locationRecordDao(): LocationRecordDao
    abstract fun cachedDeviceDao(): CachedDeviceDao
    abstract fun pendingMessageDao(): PendingMessageDao
    abstract fun pendingGameMoveDao(): PendingGameMoveDao
    abstract fun consentLogDao(): ConsentLogDao
    abstract fun syncStatusDao(): SyncStatusDao
    abstract fun appNotificationDao(): AppNotificationDao

    companion object {
        @Volatile
        private var INSTANCE: FamilyTrackDatabase? = null

        fun getInstance(context: Context): FamilyTrackDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FamilyTrackDatabase::class.java,
                    "familytrack_bd.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
