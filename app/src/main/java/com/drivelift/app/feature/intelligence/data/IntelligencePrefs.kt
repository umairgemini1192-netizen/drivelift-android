package com.drivelift.app.feature.intelligence.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which scripts on-device OCR reads. English + Arabic + Urdu is the default. */
enum class OcrLanguageMode(val label: String, val detail: String) {
    ALL_SCRIPTS("English, Arabic and Urdu", "Reads every picture with both engines"),
    SMART("English first, Arabic and Urdu when needed", "Faster: the Arabic and Urdu engine runs when little English is found"),
    ENGLISH_ONLY("English only", "Fastest; Arabic and Urdu text is not read from pictures"),
    ;

    val arabicUrduEnabled: Boolean get() = this != ENGLISH_ONLY

    companion object {
        fun fromStored(value: String?): OcrLanguageMode = entries.firstOrNull { it.name == value } ?: ALL_SCRIPTS
    }
}

/** App-private settings for the Intelligence feature. Separate file from DriveLift's own prefs. */
class IntelligencePrefs(context: Context) {
    private val sp = context.getSharedPreferences("drivelift_intelligence", Context.MODE_PRIVATE)

    private val _ocrMode = MutableStateFlow(OcrLanguageMode.fromStored(sp.getString(KEY_OCR_MODE, null)))
    val ocrMode: StateFlow<OcrLanguageMode> = _ocrMode.asStateFlow()

    private val _chargingOnly = MutableStateFlow(sp.getBoolean(KEY_CHARGING_ONLY, false))
    val chargingOnly: StateFlow<Boolean> = _chargingOnly.asStateFlow()

    private val _paused = MutableStateFlow(sp.getBoolean(KEY_PAUSED, false))
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _documentFolders = MutableStateFlow(sp.getStringSet(KEY_FOLDERS, emptySet())!!.toList().sorted())
    /** Folder tree URIs the user picked for PDFs and other documents (Android's folder picker). */
    val documentFolders: StateFlow<List<String>> = _documentFolders.asStateFlow()

    fun setOcrMode(value: OcrLanguageMode) {
        sp.edit().putString(KEY_OCR_MODE, value.name).apply()
        _ocrMode.value = value
    }

    fun setChargingOnly(value: Boolean) {
        sp.edit().putBoolean(KEY_CHARGING_ONLY, value).apply()
        _chargingOnly.value = value
    }

    fun setPaused(value: Boolean) {
        sp.edit().putBoolean(KEY_PAUSED, value).apply()
        _paused.value = value
    }

    fun addDocumentFolder(treeUri: String) = writeFolders(_documentFolders.value.toSet() + treeUri)

    fun removeDocumentFolder(treeUri: String) = writeFolders(_documentFolders.value.toSet() - treeUri)

    private fun writeFolders(values: Set<String>) {
        sp.edit().putStringSet(KEY_FOLDERS, values).apply()
        _documentFolders.value = values.toList().sorted()
    }

    private companion object {
        const val KEY_OCR_MODE = "ocr_mode"
        const val KEY_CHARGING_ONLY = "index_charging_only"
        const val KEY_PAUSED = "index_paused"
        const val KEY_FOLDERS = "document_folders"
    }
}
