package com.drivelift.app.feature.desktopdrop.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drivelift.app.Graph
import com.drivelift.app.feature.desktopdrop.model.ConnectivityMode
import com.drivelift.app.feature.desktopdrop.model.DesktopDropSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class DesktopDropViewModel(app: Application) : AndroidViewModel(app) {
    private val controller = Graph.get(app).desktopDrop.controller

    val snapshot: StateFlow<DesktopDropSnapshot> = controller.snapshot

    private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _uiMessages.asSharedFlow()

    init {
        viewModelScope.launch {
            controller.messages.collect { _uiMessages.emit(it) }
        }
    }

    fun missingHotspotPermissions(): List<String> = controller.missingHotspotPermissions()

    fun startServer(mode: ConnectivityMode = snapshot.value.mode) {
        controller.startServer(mode)
    }

    fun stopServer() {
        controller.stopServer()
    }

    fun selectMode(mode: ConnectivityMode) {
        controller.switchMode(mode)
    }

    fun approvePeer(sessionId: String) {
        controller.approvePeer(sessionId)
    }

    fun revokePeer(sessionId: String) {
        controller.revokePeer(sessionId)
    }

    fun addSharedDocuments(uris: List<Uri>) {
        controller.addSharedDocumentUris(uris)
    }

    fun addSharedFolder(treeUri: Uri) {
        controller.addSharedFolderTree(treeUri)
    }

    fun removeSharedItem(itemId: String) {
        controller.removeSharedItem(itemId)
    }

    fun clearSharedItems() {
        controller.clearSharedItems()
    }

    fun setDestinationFolder(treeUri: Uri) {
        controller.setDestinationTree(treeUri)
    }

    fun resetDestinationToDefault() {
        controller.resetDestinationToDefault()
    }

    fun updateSettings(preferredPort: Int, requireApproval: Boolean, idleTimeoutMinutes: Int) {
        controller.updateSettings(preferredPort, requireApproval, idleTimeoutMinutes)
    }

    fun notifyMessage(text: String) {
        _uiMessages.tryEmit(text)
    }
}
