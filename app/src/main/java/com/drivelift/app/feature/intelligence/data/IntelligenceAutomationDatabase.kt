package com.drivelift.app.feature.intelligence.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Self-contained Room database for the offline OCR / FTS4 index, Smart Folder rules and their
 * execution log. DriveLift's main `AppDatabase` (the transfer ledger) is never touched.
 */
@Database(
    entities = [
        IndexedDocumentEntity::class,
        IndexedDocumentFtsEntity::class,
        SmartRuleEntity::class,
        RuleExecutionLogEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class IntelligenceAutomationDatabase : RoomDatabase() {
    abstract fun dao(): IntelligenceDao

    companion object {
        const val DB_NAME = "intelligence_automation.db"
    }
}
