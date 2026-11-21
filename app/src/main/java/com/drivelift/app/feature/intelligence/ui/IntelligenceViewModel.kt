package com.drivelift.app.feature.intelligence.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drivelift.app.Graph
import com.drivelift.app.feature.intelligence.data.ExtractionStatus
import com.drivelift.app.feature.intelligence.data.OcrLanguageMode
import com.drivelift.app.feature.intelligence.data.RuleExecutionLogEntity
import com.drivelift.app.feature.intelligence.data.SmartRuleEntity
import com.drivelift.app.feature.intelligence.nlsearch.ParsedSearchQuery
import com.drivelift.app.feature.intelligence.nlsearch.SearchResult
import com.drivelift.app.feature.intelligence.ocr.IndexingTelemetry
import com.drivelift.app.feature.intelligence.rules.RuleRunReport
import com.drivelift.app.feature.intelligence.rules.RuleTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HubTab { SEARCH, RULES }

data class SearchUiState(
    val query: String = "",
    val parsed: ParsedSearchQuery? = null,
    val results: List<SearchResult> = emptyList(),
    val searching: Boolean = false,
    val searched: Boolean = false,
)

data class DryRunUi(val rule: SmartRuleEntity, val report: RuleRunReport)

class IntelligenceViewModel(app: Application) : AndroidViewModel(app) {
    private val module = Graph.get(app).intelligence
    private val dao = module.db.dao()

    val tab = MutableStateFlow(HubTab.SEARCH)

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    /** Live indexer state with durable counts from the database. */
    val telemetry: StateFlow<IndexingTelemetry> = combine(module.indexingBus.telemetry, dao.observeStatusCounts()) { live, counts ->
        val byStatus = counts.associate { it.extractionStatus to it.count }
        live.copy(
            indexedCount = (byStatus[ExtractionStatus.INDEXED.name] ?: 0) + (byStatus[ExtractionStatus.SKIPPED.name] ?: 0),
            pendingCount = byStatus[ExtractionStatus.PENDING.name] ?: 0,
            failedCount = byStatus[ExtractionStatus.FAILED.name] ?: 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IndexingTelemetry())

    val ocrMode: StateFlow<OcrLanguageMode> = module.prefs.ocrMode
    val chargingOnly: StateFlow<Boolean> = module.prefs.chargingOnly
    val paused: StateFlow<Boolean> = module.prefs.paused
    val documentFolders: StateFlow<List<String>> = module.prefs.documentFolders
    val arabicUrduPackReady: StateFlow<Boolean> = module.tessdata.installed

    val rules: StateFlow<List<SmartRuleEntity>> =
        dao.observeRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val logs: StateFlow<List<RuleExecutionLogEntity>> =
        dao.observeRecentLogs(LOG_SHOWN).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _dryRun = MutableStateFlow<DryRunUi?>(null)
    val dryRun: StateFlow<DryRunUi?> = _dryRun.asStateFlow()

    private val _busyRuleId = MutableStateFlow<Long?>(null)
    val busyRuleId: StateFlow<Long?> = _busyRuleId.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var searchJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { runCatching { module.tessdata.ensureInstalled() } }
        runCatching { module.indexScheduler.schedulePeriodic() }
    }

    // Offline search

    fun onQueryChange(value: String) {
        _search.value = _search.value.copy(query = value)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runSearch(value)
        }
    }

    fun submitQuery() {
        searchJob?.cancel()
        val value = _search.value.query
        searchJob = viewModelScope.launch { runSearch(value) }
    }

    private suspend fun runSearch(value: String) {
        if (value.isBlank()) {
            _search.value = SearchUiState(query = value)
            return
        }
        _search.value = _search.value.copy(searching = true)
        try {
            val parsed = module.search.parse(value)
            val results = withContext(Dispatchers.IO) { module.search.search(parsed) }
            _search.value = _search.value.copy(parsed = parsed, results = results, searching = false, searched = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _search.value = _search.value.copy(results = emptyList(), searching = false, searched = true)
        }
    }

    fun togglePause() {
        val nowPaused = !module.prefs.paused.value
        module.prefs.setPaused(nowPaused)
        if (nowPaused) module.indexScheduler.stop() else module.indexScheduler.scanNow()
    }

    fun scanNow() {
        if (module.prefs.paused.value) module.prefs.setPaused(false)
        module.indexScheduler.scanNow()
        _messages.tryEmit("Scanning on this phone. Nothing is uploaded.")
    }

    fun rebuildIndex() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.markAllPending() }
            scanNow()
        }
    }

    fun setOcrMode(mode: OcrLanguageMode) = module.prefs.setOcrMode(mode)

    fun setChargingOnly(value: Boolean) {
        module.prefs.setChargingOnly(value)
        module.indexScheduler.schedulePeriodic(replace = true)
    }

    fun addDocumentFolder(uri: Uri) {
        module.prefs.addDocumentFolder(uri.toString())
        scanNow()
    }

    fun removeDocumentFolder(uri: String) = module.prefs.removeDocumentFolder(uri)

    fun importArabicUrduPack(uris: List<Uri>) {
        viewModelScope.launch {
            val done = withContext(Dispatchers.IO) { module.tessdata.importFrom(uris) }
            _messages.tryEmit(
                if (module.tessdata.isInstalled()) "Arabic and Urdu reading is ready offline"
                else if (done.isEmpty()) "Pick ara.traineddata and urd.traineddata files"
                else "Installed ${done.joinToString()}; the other language file is still needed",
            )
        }
    }

    // Smart rules

    /** A template starts switched off, so nothing runs until the user has checked it (and picked any folders). */
    fun addFromTemplate(template: RuleTemplate) = saveRule(template.rule.copy(id = 0, enabled = false))

    fun saveRule(rule: SmartRuleEntity) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                if (rule.id == 0L) rule.copy(id = dao.insertRule(rule)) else rule.also { dao.updateRule(it) }
            }
            module.ruleScheduler.sync(saved)
        }
    }

    fun setRuleEnabled(rule: SmartRuleEntity, enabled: Boolean) = saveRule(rule.copy(enabled = enabled))

    fun deleteRule(rule: SmartRuleEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.deleteRule(rule.id) }
            module.ruleScheduler.cancel(rule.id)
        }
    }

    fun previewRule(rule: SmartRuleEntity) = runRule(rule, dryRun = true)

    fun runRuleNow(rule: SmartRuleEntity) = runRule(rule, dryRun = false)

    private fun runRule(rule: SmartRuleEntity, dryRun: Boolean) {
        if (_busyRuleId.value != null) return
        _busyRuleId.value = rule.id
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) { module.ruleExecutor.run(rule, dryRun) }
                if (dryRun) {
                    _dryRun.value = DryRunUi(rule, report)
                } else {
                    val parts = buildList {
                        add("${report.processed} done")
                        if (report.skipped.isNotEmpty()) add("${report.skipped.size} skipped")
                        if (report.failed > 0) add("${report.failed} failed")
                    }
                    _messages.tryEmit("${rule.name}: ${parts.joinToString(", ")}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("${rule.name} could not run: ${e.message ?: "unknown error"}")
            } finally {
                _busyRuleId.value = null
            }
        }
    }

    fun dismissDryRun() {
        _dryRun.value = null
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L
        const val LOG_SHOWN = 50
    }
}
