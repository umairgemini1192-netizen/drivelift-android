package com.drivelift.app.feature.storageintelligence

import com.drivelift.app.feature.storageintelligence.model.AutoSelectPreset
import com.drivelift.app.feature.storageintelligence.model.DuplicateGroupModel
import com.drivelift.app.feature.storageintelligence.model.DuplicateMatchKind
import com.drivelift.app.feature.storageintelligence.model.HashAlgorithm
import com.drivelift.app.feature.storageintelligence.model.LOCAL_SOURCE_ID
import com.drivelift.app.feature.storageintelligence.model.StorageSourceType
import com.drivelift.app.feature.storageintelligence.model.UnifiedFileNode
import com.drivelift.app.feature.storageintelligence.scanner.SmartAutoSelectEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAutoSelectEngineTest {

    private val engine = SmartAutoSelectEngine()

    private fun node(
        id: String,
        sourceType: StorageSourceType,
        path: String,
        modifiedAt: Long,
    ) = UnifiedFileNode(
        id = id,
        nativeId = id.substringAfter(':'),
        sourceId = if (sourceType == StorageSourceType.LOCAL) LOCAL_SOURCE_ID else "gdrive:1",
        sourceLabel = if (sourceType == StorageSourceType.LOCAL) "This phone" else "Google Drive",
        sourceType = sourceType,
        name = path.substringAfterLast('/'),
        path = path,
        sizeBytes = 5_000L,
        mimeType = "image/jpeg",
        modifiedAt = modifiedAt,
    )

    private fun mixedGroup(): DuplicateGroupModel {
        val localOld = node("local:1", StorageSourceType.LOCAL, "DCIM/Camera/Deep/IMG_1.jpg", modifiedAt = 100L)
        val localShort = node("local:2", StorageSourceType.LOCAL, "DCIM/IMG_1.jpg", modifiedAt = 300L)
        val cloudMid = node("gdrive:3", StorageSourceType.GOOGLE_DRIVE, "Drive/Backup/IMG_1.jpg", modifiedAt = 200L)
        return DuplicateGroupModel(
            groupKey = "exact:5000:SHA256:abc",
            matchKind = DuplicateMatchKind.EXACT_HASH,
            hashAlgorithm = HashAlgorithm.SHA256,
            hashValue = "abc",
            normalizedName = null,
            sizeBytes = 5_000L,
            reclaimableBytes = 10_000L,
            isCrossSource = true,
            items = listOf(localOld, localShort, cloudMid),
        )
    }

    @Test
    fun keepNewestAndKeepOldestSelectAllOthersWhileKeepingOne() {
        val group = mixedGroup()

        val newest = engine.selectForPreset(listOf(group), AutoSelectPreset.KEEP_NEWEST)
        assertEquals(setOf("local:1", "gdrive:3"), newest.selectedNodeIds)
        assertEquals(listOf("local:2"), newest.keptNodeIdsByGroup[group.groupKey])

        val oldest = engine.selectForPreset(listOf(group), AutoSelectPreset.KEEP_OLDEST)
        assertEquals(setOf("local:2", "gdrive:3"), oldest.selectedNodeIds)
        assertEquals(listOf("local:1"), oldest.keptNodeIdsByGroup[group.groupKey])
    }

    @Test
    fun keepCloudAndKeepLocalRespectSourcePreferences() {
        val group = mixedGroup()

        val freeLocal = engine.selectForPreset(listOf(group), AutoSelectPreset.KEEP_CLOUD_FREE_LOCAL)
        assertEquals(setOf("local:1", "local:2"), freeLocal.selectedNodeIds)
        assertEquals(listOf("gdrive:3"), freeLocal.keptNodeIdsByGroup[group.groupKey])

        val keepLocal = engine.selectForPreset(listOf(group), AutoSelectPreset.KEEP_LOCAL_COPY)
        assertEquals(setOf("gdrive:3"), keepLocal.selectedNodeIds)
        assertEquals(setOf("local:1", "local:2"), keepLocal.keptNodeIdsByGroup[group.groupKey]?.toSet())
    }

    @Test
    fun keepShortestPathKeepsShortestPathCopy() {
        val group = mixedGroup()
        val shortest = engine.selectForPreset(listOf(group), AutoSelectPreset.KEEP_SHORTEST_PATH)
        assertEquals(setOf("local:1", "gdrive:3"), shortest.selectedNodeIds)
        assertEquals(listOf("local:2"), shortest.keptNodeIdsByGroup[group.groupKey])
    }

    @Test
    fun everyPresetAlwaysProtectsAtLeastOneCopyAndProtectedTransferFiles() {
        val group = mixedGroup()
        val protectedIds = setOf("local:1")
        for (preset in AutoSelectPreset.entries) {
            val result = engine.selectForPreset(listOf(group), preset, protectedIds)
            assertFalse("Protected node must never be selected in $preset", "local:1" in result.selectedNodeIds)
            val keptCount = group.items.count { it.id !in result.selectedNodeIds }
            assertTrue("At least 1 copy must stay unselected in $preset", keptCount >= 1)
        }
    }

    @Test
    fun manualSelectionNeverAllowsSelectingLastRemainingCopyOrProtectedItem() {
        val group = mixedGroup()
        val protectedIds = setOf("local:1")
        val selected = setOf("local:2")
        // local:1 is protected by active transfer
        assertFalse(engine.canToggleSelect(group, selected, "local:1", protectedIds))
        // gdrive:3 is selectable while local:1 remains unselected
        assertTrue(engine.canToggleSelect(group, selected, "gdrive:3", protectedIds))
        // If only gdrive:3 remains unselected (without protection), it cannot be selected
        val selectedBothLocal = setOf("local:1", "local:2")
        assertFalse(engine.canToggleSelect(group, selectedBothLocal, "gdrive:3"))
        // Existing selected item can always be toggled off
        assertTrue(engine.canToggleSelect(group, selectedBothLocal, "local:2"))
    }

    @Test
    fun maxSelectableCopiesAndSelectAllExtraCopiesRespectProtectionAndKeepOneCopy() {
        val group = mixedGroup()

        assertEquals(2, engine.maxSelectableCopies(listOf(group)))
        val allNormal = engine.selectAllExtraCopies(listOf(group))
        assertEquals(setOf("local:1", "gdrive:3"), allNormal.selectedNodeIds)
        assertEquals(listOf("local:2"), allNormal.keptNodeIdsByGroup[group.groupKey])

        // 1 protected node -> protected item stays unselected; 2 unlocked items can be selected.
        val protectedOne = setOf("local:1")
        assertEquals(2, engine.maxSelectableCopies(listOf(group), protectedOne))
        val allWithProtected = engine.selectAllExtraCopies(listOf(group), protectedOne)
        assertEquals(setOf("local:2", "gdrive:3"), allWithProtected.selectedNodeIds)
        assertFalse("local:1" in allWithProtected.selectedNodeIds)
        assertTrue(group.items.count { it.id !in allWithProtected.selectedNodeIds } >= 1)

        // All items protected -> nothing selectable.
        val allProtected = setOf("local:1", "local:2", "gdrive:3")
        assertEquals(0, engine.maxSelectableCopies(listOf(group), allProtected))
        val noneSelected = engine.selectAllExtraCopies(listOf(group), allProtected)
        assertTrue(noneSelected.selectedNodeIds.isEmpty())
    }
}
