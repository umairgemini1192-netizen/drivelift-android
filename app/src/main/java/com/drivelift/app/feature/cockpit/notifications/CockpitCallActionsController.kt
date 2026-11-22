package com.drivelift.app.feature.cockpit.notifications

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Where the call audio is going right now. */
enum class CallAudioOutput {
    EARPIECE,
    SPEAKER,
    BLUETOOTH,
    WIRED,
}

/** Live mute and audio route state shown on the ongoing call card. */
data class CallAudioUiState(
    val isMuted: Boolean = false,
    val output: CallAudioOutput = CallAudioOutput.EARPIECE,
) {
    /** Bluetooth, earbuds and speaker light up the audio button; the earpiece is the quiet default. */
    val isOutputActive: Boolean get() = output != CallAudioOutput.EARPIECE
}

/**
 * Sends a cached notification [PendingIntent] the way Android 14+ requires. From Android 14 a
 * background app that sends a `PendingIntent` has to opt in to starting an activity, and Android 17
 * tightens that further, so the call buttons on the overlay opt in with
 * `MODE_BACKGROUND_ACTIVITY_START_ALLOWED`.
 */
object CockpitPendingIntents {
    fun send(intent: PendingIntent?): Boolean {
        if (intent == null) return false
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                val options: Bundle = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                    .toBundle()
                intent.send(options)
            } else {
                intent.send()
            }
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}

/**
 * Answers, declines, hangs up, mutes, switches audio output and opens the call for the live call
 * activity. It does not take the dialer role: the call stays in the Google Phone call screen or
 * the VoIP app, and the overlay only fires the `PendingIntent`s that app attached to its call
 * notification (`CallStyle` extras first, then action buttons found by title).
 */
class CockpitCallActionsController(private val context: Context) {

    private data class CallHandles(
        val answer: PendingIntent?,
        val decline: PendingIntent?,
        val hangUp: PendingIntent?,
        val mute: PendingIntent?,
        val muteTitle: CharSequence?,
        val speaker: PendingIntent?,
        val content: PendingIntent?,
    )

    private val callHandles = ConcurrentHashMap<String, CallHandles>()
    private val stopIntents = ConcurrentHashMap<String, PendingIntent>()
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)

    private val _audioState = MutableStateFlow(CallAudioUiState())
    val audioState: StateFlow<CallAudioUiState> = _audioState.asStateFlow()

    /** Remembers the live `PendingIntent`s of a call notification, replacing any earlier ones for [key]. */
    fun cacheCall(key: String, notification: Notification) {
        val extras = notification.extras
        var answer = extraIntent(extras, Notification.EXTRA_ANSWER_INTENT)
        var decline = extraIntent(extras, Notification.EXTRA_DECLINE_INTENT)
        var hangUp = extraIntent(extras, Notification.EXTRA_HANG_UP_INTENT)
        var mute: PendingIntent? = null
        var muteTitle: CharSequence? = null
        var speaker: PendingIntent? = null
        for (action in notification.actions.orEmpty()) {
            val intent = action.actionIntent ?: continue
            when (CockpitNotificationActions.classify(action.title)) {
                NotificationActionKind.ANSWER -> if (answer == null) answer = intent
                NotificationActionKind.DECLINE -> if (decline == null) decline = intent
                NotificationActionKind.HANG_UP -> if (hangUp == null) hangUp = intent
                NotificationActionKind.MUTE -> {
                    mute = intent
                    muteTitle = action.title
                }
                NotificationActionKind.SPEAKER -> speaker = intent
                NotificationActionKind.STOP, null -> Unit
            }
        }
        callHandles[key] = CallHandles(
            answer = answer,
            decline = decline,
            hangUp = hangUp,
            mute = mute,
            muteTitle = muteTitle,
            speaker = speaker,
            content = notification.contentIntent,
        )
        refreshAudioState(key)
    }

    /** Remembers the Stop button of a SystemUI screen recording notification. */
    fun cacheScreenRecording(key: String, notification: Notification) {
        val stop = notification.actions.orEmpty()
            .firstOrNull { CockpitNotificationActions.classify(it.title) == NotificationActionKind.STOP }
            ?.actionIntent
        if (stop != null) stopIntents[key] = stop else stopIntents.remove(key)
    }

    /** True when a live `PendingIntent` is cached for [kind] on the notification [key]. */
    fun hasHandle(key: String, kind: NotificationActionKind): Boolean {
        val handles = callHandles[key]
        return when (kind) {
            NotificationActionKind.ANSWER -> handles?.answer != null
            NotificationActionKind.DECLINE -> handles?.decline != null
            NotificationActionKind.HANG_UP -> handles?.hangUp != null
            NotificationActionKind.MUTE -> handles?.mute != null
            NotificationActionKind.SPEAKER -> handles?.speaker != null
            NotificationActionKind.STOP -> stopIntents.containsKey(key)
        }
    }

    fun clear(key: String) {
        callHandles.remove(key)
        stopIntents.remove(key)
    }

    fun clearAll() {
        callHandles.clear()
        stopIntents.clear()
    }

    fun answer(key: String?): Boolean = send(key) { it.answer }

    fun decline(key: String?): Boolean = send(key) { it.decline }

    fun hangUp(key: String?): Boolean = send(key) { it.hangUp ?: it.decline }

    fun openCall(key: String?): Boolean = send(key) { it.content }

    fun stopRecording(key: String?): Boolean =
        key != null && CockpitPendingIntents.send(stopIntents[key])

    /** Prefers the notification's Mute action, falls back to the system microphone mute. */
    fun toggleMute(key: String?): Boolean {
        val wantMuted = !_audioState.value.isMuted
        val viaNotification = send(key) { it.mute }
        val applied = viaNotification || setMicrophoneMuted(wantMuted)
        if (applied) _audioState.value = _audioState.value.copy(isMuted = wantMuted)
        return applied
    }

    /**
     * Prefers the notification's Speaker action. Otherwise cycles Bluetooth or earbuds, speaker and
     * earpiece through `AudioManager` communication devices (Android 12+), or toggles the
     * speakerphone on older phones.
     */
    fun cycleAudioOutput(key: String?): Boolean {
        val current = _audioState.value.output
        if (send(key) { it.speaker }) {
            val next = if (current == CallAudioOutput.EARPIECE) CallAudioOutput.SPEAKER else CallAudioOutput.EARPIECE
            _audioState.value = _audioState.value.copy(output = next)
            return true
        }
        val audio = audioManager ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val devices = audio.availableCommunicationDevices
                val available = devices.mapNotNull { classifyDeviceType(it.type) }.toSet()
                val next = nextOutput(current, available) ?: return@runCatching false
                val target = devices.firstOrNull { classifyDeviceType(it.type) == next }
                    ?: return@runCatching false
                val switched = audio.setCommunicationDevice(target)
                if (switched) _audioState.value = _audioState.value.copy(output = next)
                switched
            } else {
                val speakerOn = !isSpeakerphoneOn(audio)
                setSpeakerphoneOn(audio, speakerOn)
                _audioState.value = _audioState.value.copy(
                    output = if (speakerOn) CallAudioOutput.SPEAKER else CallAudioOutput.EARPIECE,
                )
                true
            }
        }.getOrDefault(false)
    }

    /** Re-reads microphone mute and audio route so the card never shows a stale button state. */
    fun refreshAudioState(key: String? = null) {
        val titleMuted = key?.let { callHandles[it]?.muteTitle }
            ?.let { CockpitNotificationActions.titleMeansMuted(it) } == true
        val micMuted = runCatching { audioManager?.isMicrophoneMute == true }.getOrDefault(false)
        _audioState.value = CallAudioUiState(
            isMuted = titleMuted || micMuted,
            output = currentOutput(),
        )
    }

    private fun send(key: String?, pick: (CallHandles) -> PendingIntent?): Boolean {
        val handles = key?.let { callHandles[it] } ?: return false
        return CockpitPendingIntents.send(pick(handles))
    }

    private fun setMicrophoneMuted(muted: Boolean): Boolean {
        val audio = audioManager ?: return false
        return runCatching {
            audio.isMicrophoneMute = muted
            true
        }.getOrDefault(false)
    }

    private fun currentOutput(): CallAudioOutput {
        val audio = audioManager ?: return CallAudioOutput.EARPIECE
        return runCatching {
            val device: CallAudioOutput? = if (Build.VERSION.SDK_INT >= 31) {
                audio.communicationDevice?.let { classifyDeviceType(it.type) }
            } else {
                null
            }
            device ?: if (isSpeakerphoneOn(audio)) CallAudioOutput.SPEAKER else CallAudioOutput.EARPIECE
        }.getOrDefault(CallAudioOutput.EARPIECE)
    }

    @Suppress("DEPRECATION")
    private fun isSpeakerphoneOn(audio: AudioManager): Boolean = audio.isSpeakerphoneOn

    @Suppress("DEPRECATION")
    private fun setSpeakerphoneOn(audio: AudioManager, on: Boolean) {
        audio.isSpeakerphoneOn = on
    }

    private fun extraIntent(extras: Bundle?, key: String): PendingIntent? {
        if (extras == null || Build.VERSION.SDK_INT < 31) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                extras.getParcelable(key, PendingIntent::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable<PendingIntent>(key)
            }
        }.getOrNull()
    }

    companion object {
        private val CYCLE_ORDER = listOf(
            CallAudioOutput.BLUETOOTH,
            CallAudioOutput.WIRED,
            CallAudioOutput.SPEAKER,
            CallAudioOutput.EARPIECE,
        )

        /** Maps an `AudioDeviceInfo.TYPE_*` value to the output the call card cares about. */
        fun classifyDeviceType(type: Int): CallAudioOutput? = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID -> CallAudioOutput.BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET -> CallAudioOutput.WIRED
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> CallAudioOutput.SPEAKER
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> CallAudioOutput.EARPIECE
            else -> null
        }

        /**
         * The next output after [current] in the order Bluetooth or earbuds, speaker, earpiece,
         * skipping outputs that are not [available]. Null when nothing else can be chosen.
         */
        fun nextOutput(current: CallAudioOutput, available: Set<CallAudioOutput>): CallAudioOutput? {
            val start = CYCLE_ORDER.indexOf(current)
            for (step in 1..CYCLE_ORDER.size) {
                val candidate = CYCLE_ORDER[(start + step) % CYCLE_ORDER.size]
                if (candidate != current && candidate in available) return candidate
            }
            return null
        }
    }
}
