package com.drivelift.app.feature.whatsappsender.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drivelift.app.Graph
import com.drivelift.app.feature.whatsappsender.model.BatchStrategy
import com.drivelift.app.feature.whatsappsender.model.MAX_WHATSAPP_BATCH_FILES
import com.drivelift.app.feature.whatsappsender.model.PreFlightReport
import com.drivelift.app.feature.whatsappsender.model.QualityMode
import com.drivelift.app.feature.whatsappsender.model.SendLaunchResult
import com.drivelift.app.feature.whatsappsender.model.SendPreset
import com.drivelift.app.feature.whatsappsender.model.WhatsAppAvailability
import com.drivelift.app.feature.whatsappsender.model.WhatsAppSendPayload
import com.drivelift.app.feature.whatsappsender.model.WhatsAppTarget
import com.drivelift.app.feature.whatsappsender.model.ZipPreparationResult
import com.drivelift.app.feature.whatsappsender.model.ZipProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WhatsAppSenderUiState(
    val payload: WhatsAppSendPayload? = null,
    val availability: WhatsAppAvailability = WhatsAppAvailability.NONE,
    val selectedTarget: WhatsAppTarget = WhatsAppTarget.SYSTEM_SHARESHEET,
    val qualityMode: QualityMode = QualityMode.ORIGINAL_QUALITY,
    val caption: String = "",
    val excludeOversized: Boolean = false,
    val batchStrategy: BatchStrategy = BatchStrategy.SINGLE_SEND,
    val currentBatchIndex: Int = 0,
    val sentBatches: Set<Int> = emptySet(),
    val report: PreFlightReport = PreFlightReport(
        eligibleItems = emptyList(),
        oversizedItems = emptyList(),
        skippedZeroByteCount = 0,
        skippedUnreadableCount = 0,
        isFolderSource = false,
    ),
    val zipProgress: ZipProgress? = null,
    val isZipping: Boolean = false,
    val feedbackMessage: String? = null,
    val errorMessage: String? = null,
    val recentPresets: List<SendPreset> = emptyList(),
)

/** Manages pre-flight validation, quality/target preferences, batching, and ZIP staging for WhatsApp sends. */
class WhatsAppSenderViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = Graph.get(app)
    private val module = graph.whatsAppSender
    private val prefs = module.prefs

    private val _state = MutableStateFlow(
        WhatsAppSenderUiState(
            availability = module.availabilityChecker.check(),
            selectedTarget = module.availabilityChecker.check().resolveTarget(prefs.defaultTarget.value),
            qualityMode = prefs.defaultQualityMode.value,
            recentPresets = prefs.recentPresets.value,
        ),
    )
    val state: StateFlow<WhatsAppSenderUiState> = _state.asStateFlow()

    private var activeZipJob: Job? = null

    init {
        viewModelScope.launch {
            prefs.recentPresets.collect { presets ->
                _state.update { it.copy(recentPresets = presets) }
            }
        }
    }

    fun refreshAvailability() {
        val availability = module.availabilityChecker.check()
        _state.update { current ->
            val target = availability.resolveTarget(
                if (current.selectedTarget != WhatsAppTarget.SYSTEM_SHARESHEET) current.selectedTarget
                else prefs.defaultTarget.value,
            )
            current.copy(availability = availability, selectedTarget = target)
        }
    }

    fun loadPayload(payload: WhatsAppSendPayload) {
        activeZipJob?.cancel()
        val availability = module.availabilityChecker.check()
        val target = availability.resolveTarget(prefs.defaultTarget.value)
        val quality = prefs.defaultQualityMode.value
        val report = module.validator.validate(payload.items, payload.isFolderSource)
        val defaultStrategy = when {
            report.exceedsBatchLimit(excludeOversized = false) -> BatchStrategy.ZIP_ARCHIVE
            else -> BatchStrategy.SINGLE_SEND
        }
        _state.update {
            it.copy(
                payload = payload,
                availability = availability,
                selectedTarget = target,
                qualityMode = quality,
                caption = "",
                excludeOversized = false,
                batchStrategy = defaultStrategy,
                currentBatchIndex = 0,
                sentBatches = emptySet(),
                report = report,
                zipProgress = null,
                isZipping = false,
                feedbackMessage = null,
                errorMessage = null,
            )
        }
    }

    fun selectTarget(target: WhatsAppTarget) {
        prefs.setDefaultTarget(target)
        _state.update { it.copy(selectedTarget = target, errorMessage = null) }
    }

    fun selectQualityMode(mode: QualityMode) {
        prefs.setDefaultQualityMode(mode)
        _state.update { it.copy(qualityMode = mode, errorMessage = null) }
    }

    fun updateCaption(caption: String) {
        _state.update { it.copy(caption = caption, errorMessage = null) }
    }

    fun toggleExcludeOversized() {
        _state.update { current ->
            val nextExclude = !current.excludeOversized
            val exceeds = current.report.exceedsBatchLimit(nextExclude)
            val nextStrategy = when {
                !exceeds && current.batchStrategy == BatchStrategy.SPLIT_BATCHES_30 -> BatchStrategy.SINGLE_SEND
                exceeds && current.batchStrategy == BatchStrategy.SINGLE_SEND -> BatchStrategy.ZIP_ARCHIVE
                else -> current.batchStrategy
            }
            current.copy(
                excludeOversized = nextExclude,
                batchStrategy = nextStrategy,
                currentBatchIndex = 0,
                sentBatches = emptySet(),
                errorMessage = null,
            )
        }
    }

    fun selectBatchStrategy(strategy: BatchStrategy) {
        _state.update { current ->
            val resolved = if (strategy == BatchStrategy.SPLIT_BATCHES_30 &&
                !current.report.exceedsBatchLimit(current.excludeOversized)
            ) {
                BatchStrategy.SINGLE_SEND
            } else {
                strategy
            }
            current.copy(
                batchStrategy = resolved,
                currentBatchIndex = 0,
                sentBatches = emptySet(),
                errorMessage = null,
            )
        }
    }

    fun toggleFolderZip(enableZip: Boolean) {
        _state.update { current ->
            val nextStrategy = if (enableZip) {
                BatchStrategy.ZIP_ARCHIVE
            } else if (current.report.exceedsBatchLimit(current.excludeOversized)) {
                BatchStrategy.SPLIT_BATCHES_30
            } else {
                BatchStrategy.SINGLE_SEND
            }
            current.copy(
                batchStrategy = nextStrategy,
                currentBatchIndex = 0,
                sentBatches = emptySet(),
                errorMessage = null,
            )
        }
    }

    fun applyPreset(preset: SendPreset) {
        val availability = module.availabilityChecker.check()
        val resolvedTarget = availability.resolveTarget(preset.target)
        prefs.setDefaultTarget(resolvedTarget)
        prefs.setDefaultQualityMode(preset.qualityMode)
        _state.update {
            it.copy(
                availability = availability,
                selectedTarget = resolvedTarget,
                qualityMode = preset.qualityMode,
                errorMessage = null,
            )
        }
    }

    fun cancelZip() {
        activeZipJob?.cancel()
        activeZipJob = null
        _state.update {
            it.copy(
                isZipping = false,
                zipProgress = null,
                feedbackMessage = "Zip preparation cancelled.",
            )
        }
    }

    fun send(
        context: Context,
        onCompleted: (String) -> Unit,
    ) {
        val current = _state.value
        val payload = current.payload ?: return
        val items = current.report.effectiveItems(current.excludeOversized)
        if (items.isEmpty()) {
            _state.update { it.copy(errorMessage = "No sendable files remain.") }
            return
        }

        val engine = module.shareEngine()
        prefs.recordPreset(
            SendPreset(
                label = "${current.selectedTarget.label} - ${shortQualityLabel(current.qualityMode)}",
                target = current.selectedTarget,
                qualityMode = current.qualityMode,
                folderLabel = payload.title,
                updatedAtMs = graph.clock(),
            ),
        )

        when (current.batchStrategy) {
            BatchStrategy.ZIP_ARCHIVE -> {
                activeZipJob?.cancel()
                _state.update { it.copy(isZipping = true, zipProgress = null, errorMessage = null, feedbackMessage = null) }
                activeZipJob = viewModelScope.launch {
                    try {
                        val zipResult = engine.zipPreparer.prepareZip(
                            folderName = payload.title,
                            items = items,
                            onProgress = { progress ->
                                _state.update { it.copy(zipProgress = progress) }
                            },
                        )
                        when (zipResult) {
                            is ZipPreparationResult.Success -> {
                                val launch = engine.sendStagedArchive(
                                    context = context,
                                    archiveUri = zipResult.archiveUri,
                                    target = current.selectedTarget,
                                    caption = current.caption,
                                )
                                _state.update { it.copy(isZipping = false, zipProgress = null) }
                                handleLaunchOutcome(
                                    launch = launch,
                                    target = current.selectedTarget,
                                    count = zipResult.fileCount,
                                    isArchive = true,
                                    onSuccess = onCompleted,
                                )
                            }
                            is ZipPreparationResult.Failure -> {
                                _state.update {
                                    it.copy(
                                        isZipping = false,
                                        zipProgress = null,
                                        errorMessage = zipResult.message,
                                    )
                                }
                            }
                        }
                    } catch (ce: CancellationException) {
                        _state.update { it.copy(isZipping = false, zipProgress = null) }
                        throw ce
                    }
                }
            }

            BatchStrategy.SPLIT_BATCHES_30 -> {
                val batches = engine.validator.splitIntoBatches(items, MAX_WHATSAPP_BATCH_FILES)
                val idx = current.currentBatchIndex.coerceIn(0, (batches.size - 1).coerceAtLeast(0))
                val batch = batches.getOrNull(idx).orEmpty()
                val launch = engine.sendItems(
                    context = context,
                    items = batch,
                    target = current.selectedTarget,
                    qualityMode = current.qualityMode,
                    caption = if (idx == 0) current.caption else null,
                )
                when (launch) {
                    SendLaunchResult.LAUNCHED_WHATSAPP, SendLaunchResult.LAUNCHED_SHARESHEET -> {
                        val updatedSent = current.sentBatches + idx
                        val nextIdx = idx + 1
                        if (nextIdx >= batches.size) {
                            onCompleted(
                                "Sent all ${batches.size} batches to ${targetDisplay(launch, current.selectedTarget)}.",
                            )
                        } else {
                            _state.update {
                                it.copy(
                                    sentBatches = updatedSent,
                                    currentBatchIndex = nextIdx,
                                    feedbackMessage = "Batch ${idx + 1} of ${batches.size} opened. Send batch ${nextIdx + 1} next.",
                                    errorMessage = null,
                                )
                            }
                        }
                    }
                    else -> handleLaunchOutcome(
                        launch = launch,
                        target = current.selectedTarget,
                        count = batch.size,
                        isArchive = false,
                        onSuccess = onCompleted,
                    )
                }
            }

            BatchStrategy.SINGLE_SEND -> {
                val launch = engine.sendItems(
                    context = context,
                    items = items,
                    target = current.selectedTarget,
                    qualityMode = current.qualityMode,
                    caption = current.caption,
                )
                handleLaunchOutcome(
                    launch = launch,
                    target = current.selectedTarget,
                    count = items.size,
                    isArchive = false,
                    onSuccess = onCompleted,
                )
            }
        }
    }

    private fun handleLaunchOutcome(
        launch: SendLaunchResult,
        target: WhatsAppTarget,
        count: Int,
        isArchive: Boolean,
        onSuccess: (String) -> Unit,
    ) {
        when (launch) {
            SendLaunchResult.LAUNCHED_WHATSAPP, SendLaunchResult.LAUNCHED_SHARESHEET -> {
                val dest = targetDisplay(launch, target)
                val what = if (isArchive) {
                    "archive ($count ${if (count == 1) "file" else "files"})"
                } else {
                    "$count ${if (count == 1) "file" else "files"}"
                }
                onSuccess("Prepared $what for $dest.")
            }
            SendLaunchResult.NO_TARGET_ACTIVITY -> {
                _state.update {
                    it.copy(errorMessage = "No compatible share app is available on this device.")
                }
            }
            SendLaunchResult.EMPTY_SELECTION -> {
                _state.update { it.copy(errorMessage = "No sendable files remain.") }
            }
            SendLaunchResult.ERROR -> {
                _state.update {
                    it.copy(errorMessage = "Could not open share target for the selected files.")
                }
            }
        }
    }

    private fun targetDisplay(launch: SendLaunchResult, target: WhatsAppTarget): String =
        if (launch == SendLaunchResult.LAUNCHED_SHARESHEET) "System share" else target.label

    private fun shortQualityLabel(mode: QualityMode): String = when (mode) {
        QualityMode.ORIGINAL_QUALITY -> "Original"
        QualityMode.STANDARD_MEDIA -> "Standard"
    }
}
