package com.drivelift.app.feature.storageintelligence.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Self-contained Room database dedicated strictly to Storage Intelligence scan results,
 * keeping [com.drivelift.app.data.AppDatabase] untouched to prevent schema merge conflicts.
 */
@Database(
    entities = [
        StorageScanSessionEntity::class,
        DuplicateGroupEntity::class,
        DuplicateItemEntity::class,
        LargeFileEntity::class,
        OrphanArtifactEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class StorageIntelligenceDatabase : RoomDatabase() {
    abstract fun dao(): StorageIntelligenceDao

    companion object {
        const val DB_NAME = "storage_intelligence.db"
    }
}
