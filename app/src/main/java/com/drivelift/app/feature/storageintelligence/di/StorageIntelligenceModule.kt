package com.drivelift.app.feature.storageintelligence.di

import android.app.Application
import androidx.room.Room
import com.drivelift.app.Graph
import com.drivelift.app.feature.storageintelligence.data.StorageIntelligenceRepository
import com.drivelift.app.feature.storageintelligence.db.StorageIntelligenceDatabase
import com.drivelift.app.feature.storageintelligence.scanner.CrossSourceDuplicateEngine
import com.drivelift.app.feature.storageintelligence.scanner.DriveLiftTransferLockProvider
import com.drivelift.app.feature.storageintelligence.scanner.GoogleDriveStorageScannerAdapter
import com.drivelift.app.feature.storageintelligence.scanner.HttpGoogleDriveMetadataApi
import com.drivelift.app.feature.storageintelligence.scanner.LargeFileRadarEngine
import com.drivelift.app.feature.storageintelligence.scanner.LocalStorageScannerAdapter
import com.drivelift.app.feature.storageintelligence.scanner.MediaStoreLocalByteSource
import com.drivelift.app.feature.storageintelligence.scanner.OrphanCacheScanner
import com.drivelift.app.feature.storageintelligence.scanner.OrphanSafetyLock
import com.drivelift.app.feature.storageintelligence.scanner.SmartAutoSelectEngine
import com.drivelift.app.feature.storageintelligence.worker.StorageIntelligenceScanBus

/**
 * Self-contained dependency container for Storage Intelligence.
 * Registered lazily once from [Graph] while keeping existing DriveLift singletons untouched.
 */
class StorageIntelligenceModule(
    app: Application,
    graph: Graph,
) {
    val db: StorageIntelligenceDatabase = Room.databaseBuilder(
        app,
        StorageIntelligenceDatabase::class.java,
        StorageIntelligenceDatabase.DB_NAME,
    )
        .fallbackToDestructiveMigration()
        .build()

    val scanBus = StorageIntelligenceScanBus()
    val autoSelectEngine = SmartAutoSelectEngine()
    val orphanSafetyLock = OrphanSafetyLock()

    private val localByteSource = MediaStoreLocalByteSource(
        context = app,
        openOriginalStream = { ctx, uri -> graph.openOriginalStream(ctx, uri) },
    )

    private val localScanner = LocalStorageScannerAdapter(
        context = app,
        mediaRepository = graph.media,
    )

    private val googleDriveMetadataApi = HttpGoogleDriveMetadataApi(
        context = app,
        prefs = graph.prefs,
    )

    private val googleDriveScanner = GoogleDriveStorageScannerAdapter(
        api = googleDriveMetadataApi,
    )

    private val transferLockProvider = DriveLiftTransferLockProvider(
        context = app,
        appDb = graph.db,
    )

    private val duplicateEngine = CrossSourceDuplicateEngine(localByteSource = localByteSource)
    private val largeFileEngine = LargeFileRadarEngine()
    private val orphanScanner = OrphanCacheScanner(
        context = app,
        appDb = graph.db,
        safetyLock = orphanSafetyLock,
        lockProvider = transferLockProvider,
    )

    val repository = StorageIntelligenceRepository(
        context = app,
        db = db,
        localScanner = localScanner,
        cloudScanners = listOf(googleDriveScanner),
        duplicateEngine = duplicateEngine,
        largeFileEngine = largeFileEngine,
        orphanScanner = orphanScanner,
        orphanSafetyLock = orphanSafetyLock,
        lockProvider = transferLockProvider,
        scanBus = scanBus,
        appDb = graph.db,
        transfers = graph.transfers,
        media = graph.media,
        drive = graph.drive,
        prefs = graph.prefs,
        clock = { graph.clock() },
    )
}
