package com.drivelift.app.feature.whatsappsender.data

import android.content.Context
import com.drivelift.app.feature.whatsappsender.model.QualityMode
import com.drivelift.app.feature.whatsappsender.model.SendPreset
import com.drivelift.app.feature.whatsappsender.model.WhatsAppTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Isolated preference store for WhatsApp Quick Sender so the main Room database and Prefs
 * remain untouched during parallel feature development.
 */
class WhatsAppSenderPrefs(context: Context) {
    private val sp = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _defaultTarget = MutableStateFlow(
        WhatsAppTarget.fromStored(sp.getString(KEY_TARGET, null)),
    )
    val defaultTarget: StateFlow<WhatsAppTarget> = _defaultTarget.asStateFlow()

    private val _defaultQualityMode = MutableStateFlow(
        QualityMode.fromStored(sp.getString(KEY_QUALITY_MODE, null)),
    )
    val defaultQualityMode: StateFlow<QualityMode> = _defaultQualityMode.asStateFlow()

    private val _recentPresets = MutableStateFlow(decodePresets(sp.getString(KEY_PRESETS, null)))
    val recentPresets: StateFlow<List<SendPreset>> = _recentPresets.asStateFlow()

    fun setDefaultTarget(target: WhatsAppTarget) {
        if (target == WhatsAppTarget.SYSTEM_SHARESHEET) return
        sp.edit().putString(KEY_TARGET, target.name).apply()
        _defaultTarget.value = target
    }

    fun setDefaultQualityMode(mode: QualityMode) {
        sp.edit().putString(KEY_QUALITY_MODE, mode.name).apply()
        _defaultQualityMode.value = mode
    }

    fun recordPreset(preset: SendPreset) {
        val current = _recentPresets.value
        val deduped = current.filterNot {
            it.target == preset.target &&
                it.qualityMode == preset.qualityMode &&
                it.folderLabel.equals(preset.folderLabel, ignoreCase = true)
        }
        val updated = (listOf(preset) + deduped).take(MAX_PRESETS)
        sp.edit().putString(KEY_PRESETS, encodePresets(updated)).apply()
        _recentPresets.value = updated
    }

    fun clearPresets() {
        sp.edit().remove(KEY_PRESETS).apply()
        _recentPresets.value = emptyList()
    }

    private fun encodePresets(presets: List<SendPreset>): String {
        val arr = JSONArray()
        for (preset in presets) {
            arr.put(
                JSONObject().apply {
                    put("label", preset.label)
                    put("target", preset.target.name)
                    put("qualityMode", preset.qualityMode.name)
                    put("folderLabel", preset.folderLabel)
                    put("updatedAtMs", preset.updatedAtMs)
                },
            )
        }
        return arr.toString()
    }

    private fun decodePresets(raw: String?): List<SendPreset> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = ArrayList<SendPreset>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val label = obj.optString("label").takeIf { it.isNotBlank() } ?: continue
                val targetName = obj.optString("target")
                val target = WhatsAppTarget.entries.firstOrNull { it.name == targetName }
                    ?: WhatsAppTarget.PERSONAL
                val qualityMode = QualityMode.fromStored(obj.optString("qualityMode"))
                val folderLabel = obj.optString("folderLabel").ifBlank { label }
                val updatedAtMs = obj.optLong("updatedAtMs", 0L)
                list += SendPreset(
                    label = label,
                    target = target,
                    qualityMode = qualityMode,
                    folderLabel = folderLabel,
                    updatedAtMs = updatedAtMs,
                )
            }
            list.take(MAX_PRESETS)
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFS_FILE = "drivelift_whatsapp_sender_prefs"
        const val KEY_TARGET = "default_target"
        const val KEY_QUALITY_MODE = "default_quality_mode"
        const val KEY_PRESETS = "recent_presets"
        const val MAX_PRESETS = 5
    }
}
