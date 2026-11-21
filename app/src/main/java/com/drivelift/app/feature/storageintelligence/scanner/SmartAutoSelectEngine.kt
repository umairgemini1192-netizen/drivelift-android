package com.drivelift.app.feature.storageintelligence.scanner

import com.drivelift.app.feature.storageintelligence.model.AutoSelectPreset
import com.drivelift.app.feature.storageintelligence.model.DuplicateGroupModel
import com.drivelift.app.feature.storageintelligence.model.UnifiedFileNode

data class AutoSelectResult(
    val selectedNodeIds: Set<String>,
    val keptNodeIdsByGroup: Map<String, List<String>>,
)

/**
 * Computes duplicate cleanup selections for one-tap presets while strictly protecting:
 * 1. At least one original copy in every duplicate group (never all copies).
 * 2. Every item currently locked by an active transfer ([protectedNodeIds]).
 */
class SmartAutoSelectEngine {

    fun maxSelectableCopies(
        groups: List<DuplicateGroupModel>,
        protectedNodeIds: Set<String> = emptySet(),
    ): Int {
        var total = 0
        for (group in groups) {
            if (group.items.size < 2) continue
            val protectedCount = group.items.count { it.id in protectedNodeIds }
            val unlockedCount = group.items.size - protectedCount
            total += if (protectedCount >= 1) unlockedCount else (unlockedCount - 1).coerceAtLeast(0)
        }
        return total
    }

    fun selectAllExtraCopies(
        groups: List<DuplicateGroupModel>,
        protectedNodeIds: Set<String> = emptySet(),
    ): AutoSelectResult = selectWithKeeperRule(groups, protectedNodeIds) { group ->
        val protectedItems = group.items.filter { it.id in protectedNodeIds }
        if (protectedItems.isNotEmpty()) {
            protectedItems.map { it.id }
        } else {
            chooseKeepers(group, AutoSelectPreset.KEEP_NEWEST, protectedNodeIds)
        }
    }

    fun selectForPreset(
        groups: List<DuplicateGroupModel>,
        preset: AutoSelectPreset,
        protectedNodeIds: Set<String> = emptySet(),
    ): AutoSelectResult = selectWithKeeperRule(groups, protectedNodeIds) { group ->
        chooseKeepers(group, preset, protectedNodeIds)
    }

    private fun selectWithKeeperRule(
        groups: List<DuplicateGroupModel>,
        protectedNodeIds: Set<String>,
        keeperSelector: (DuplicateGroupModel) -> List<String>,
    ): AutoSelectResult {
        val selected = LinkedHashSet<String>()
        val keptByGroup = LinkedHashMap<String, List<String>>()

        for (group in groups) {
            if (group.items.size < 2) {
                keptByGroup[group.groupKey] = group.items.map { it.id }
                continue
            }
            val keepers = keeperSelector(group).toMutableSet()
            // Always protect active transfer files from automatic selection.
            for (item in group.items) {
                if (item.id in protectedNodeIds) keepers += item.id
            }
            // Absolute guarantee: at least one original copy stays unselected in every group.
            if (keepers.isEmpty()) {
                keepers += group.items.first().id
            }
            val groupSelected = group.items.filter { it.id !in keepers }.map { it.id }
            if (groupSelected.size >= group.items.size) {
                // Defensive safety belt: keep the first item if all somehow became eligible.
                val safeKeep = group.items.first().id
                keepers += safeKeep
                selected += groupSelected.filter { it != safeKeep }
            } else {
                selected += groupSelected
            }
            keptByGroup[group.groupKey] = group.items.filter { it.id in keepers }.map { it.id }
        }

        return AutoSelectResult(selectedNodeIds = selected, keptNodeIdsByGroup = keptByGroup)
    }

    private fun chooseKeepers(
        group: DuplicateGroupModel,
        preset: AutoSelectPreset,
        protectedNodeIds: Set<String>,
    ): List<String> {
        val items = group.items
        return when (preset) {
            AutoSelectPreset.KEEP_NEWEST -> {
                val keeper = items.maxWith(
                    compareBy<UnifiedFileNode> { it.modifiedAt }
                        .thenByDescending { if (it.id in protectedNodeIds) 1 else 0 }
                        .thenByDescending { -it.path.length }
                        .thenByDescending { it.id },
                )
                listOf(keeper.id)
            }
            AutoSelectPreset.KEEP_OLDEST -> {
                val keeper = items.minWith(
                    compareBy<UnifiedFileNode> { it.modifiedAt }
                        .thenBy { if (it.id in protectedNodeIds) 0 else 1 }
                        .thenBy { it.path.length }
                        .thenBy { it.id },
                )
                listOf(keeper.id)
            }
            AutoSelectPreset.KEEP_CLOUD_FREE_LOCAL -> {
                val cloudItems = items.filter { it.isCloud }
                val localItems = items.filter { it.isLocal }
                when {
                    cloudItems.isNotEmpty() && localItems.isNotEmpty() -> cloudItems.map { it.id }
                    localItems.isNotEmpty() -> listOf(
                        localItems.maxWith(
                            compareBy<UnifiedFileNode> { it.modifiedAt }
                                .thenBy { if (it.id in protectedNodeIds) 1 else 0 },
                        ).id,
                    )
                    else -> items.map { it.id }
                }
            }
            AutoSelectPreset.KEEP_LOCAL_COPY -> {
                val localItems = items.filter { it.isLocal }
                val cloudItems = items.filter { it.isCloud }
                when {
                    localItems.isNotEmpty() && cloudItems.isNotEmpty() -> localItems.map { it.id }
                    cloudItems.isNotEmpty() -> listOf(
                        cloudItems.maxWith(compareBy<UnifiedFileNode> { it.modifiedAt }.thenBy { it.id }).id,
                    )
                    else -> items.map { it.id }
                }
            }
            AutoSelectPreset.KEEP_SHORTEST_PATH -> {
                val keeper = items.minWith(
                    compareBy<UnifiedFileNode> { it.path.length }
                        .thenBy { it.path.count { ch -> ch == '/' } }
                        .thenByDescending { it.modifiedAt }
                        .thenBy { it.id },
                )
                listOf(keeper.id)
            }
        }
    }

    /**
     * Manual checkbox rule: a node can only be toggled ON if it is not protected and at least
     * one other node in [group] remains unselected.
     */
    fun canToggleSelect(
        group: DuplicateGroupModel,
        currentSelected: Set<String>,
        nodeId: String,
        protectedNodeIds: Set<String> = emptySet(),
    ): Boolean {
        if (nodeId in currentSelected) return true
        if (nodeId in protectedNodeIds) return false
        return group.items.count { it.id != nodeId && it.id !in currentSelected } >= 1
    }
}
