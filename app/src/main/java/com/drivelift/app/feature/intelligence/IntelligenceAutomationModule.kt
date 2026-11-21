package com.drivelift.app.feature.intelligence

import android.app.Application
import androidx.room.Room
import com.drivelift.app.Graph
import com.drivelift.app.feature.intelligence.data.IntelligenceAutomationDatabase
import com.drivelift.app.feature.intelligence.data.IntelligencePrefs
import com.drivelift.app.feature.intelligence.nlsearch.NaturalLanguageQueryParser
import com.drivelift.app.feature.intelligence.nlsearch.OfflineSearchRepository
import com.drivelift.app.feature.intelligence.ocr.AndroidDocumentSource
import com.drivelift.app.feature.intelligence.ocr.AndroidDocumentTextExtractor
import com.drivelift.app.feature.intelligence.ocr.IndexScheduler
import com.drivelift.app.feature.intelligence.ocr.IndexingBus
import com.drivelift.app.feature.intelligence.ocr.MlKitLatinOcrEngine
import com.drivelift.app.feature.intelligence.ocr.MultiScriptOcrRouter
import com.drivelift.app.feature.intelligence.ocr.OcrIndexer
import com.drivelift.app.feature.intelligence.ocr.TessdataManager
import com.drivelift.app.feature.intelligence.ocr.TesseractArabicUrduOcrEngine
import com.drivelift.app.feature.intelligence.rules.AndroidRuleFileSystem
import com.drivelift.app.feature.intelligence.rules.LedgerTransferLockLookup
import com.drivelift.app.feature.intelligence.rules.SmartRuleExecutor
import com.drivelift.app.feature.intelligence.rules.SmartRuleMatcher
import com.drivelift.app.feature.intelligence.rules.SmartRuleScheduler
import com.drivelift.app.feature.intelligence.rules.TransferQueueUploadAdapter
import com.drivelift.app.feature.storageintelligence.scanner.DriveLiftTransferLockProvider

/**
 * Self-contained container for On-Device Intelligence & Automation (offline OCR search and Smart
 * Folder rules). Created lazily from [Graph]; it only reads DriveLift's shared objects and queues
 * uploads through the existing transfer repository.
 */
class IntelligenceAutomationModule(app: Application, graph: Graph) {
    val db: IntelligenceAutomationDatabase = Room.databaseBuilder(
        app,
        IntelligenceAutomationDatabase::class.java,
        IntelligenceAutomationDatabase.DB_NAME,
    )
        // A rebuildable index and user rules; a downgrade starts fresh rather than crashing.
        .fallbackToDestructiveMigrationOnDowngrade()
        // Own threads, so long OCR index writes never queue behind (or block) other databases.
        .setQueryExecutor(java.util.concurrent.Executors.newFixedThreadPool(2))
        .build()

    val prefs = IntelligencePrefs(app)
    val tessdata = TessdataManager(app)
    val indexingBus = IndexingBus()
    val indexScheduler = IndexScheduler(app, prefs)
    val ruleScheduler = SmartRuleScheduler(app)

    private val latinOcr = MlKitLatinOcrEngine()
    private val arabicUrduOcr = TesseractArabicUrduOcrEngine(tessdata)
    private val router = MultiScriptOcrRouter(latinOcr, arabicUrduOcr) { prefs.ocrMode.value }

    val indexer = OcrIndexer(
        dao = db.dao(),
        source = AndroidDocumentSource(app, prefs),
        extractor = AndroidDocumentTextExtractor(app, router),
        bus = indexingBus,
        clock = { graph.clock() },
        isPaused = { prefs.paused.value },
    )

    val search = OfflineSearchRepository(db.dao(), NaturalLanguageQueryParser(now = { graph.clock() }))

    val ruleMatcher = SmartRuleMatcher(now = { graph.clock() })

    val ruleExecutor = SmartRuleExecutor(
        dao = db.dao(),
        fs = AndroidRuleFileSystem(app),
        uploader = TransferQueueUploadAdapter(graph.transfers),
        locks = LedgerTransferLockLookup(DriveLiftTransferLockProvider(app, graph.db)),
        matcher = ruleMatcher,
        clock = { graph.clock() },
    )
}
