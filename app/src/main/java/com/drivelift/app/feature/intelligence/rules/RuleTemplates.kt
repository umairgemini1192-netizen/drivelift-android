package com.drivelift.app.feature.intelligence.rules

import com.drivelift.app.feature.intelligence.data.ConflictPolicy
import com.drivelift.app.feature.intelligence.data.RuleActionType
import com.drivelift.app.feature.intelligence.data.SmartRuleEntity

/** A one-tap starting point for a rule. Folders the user must pick are left empty. */
data class RuleTemplate(
    val key: String,
    val title: String,
    val description: String,
    val rule: SmartRuleEntity,
)

object RuleTemplates {
    val all: List<RuleTemplate> = listOf(
        RuleTemplate(
            key = "dcim-4k-upload",
            title = "Upload new 4K videos",
            description = "Camera videos in DCIM at 4K or higher go to Google Drive on Wi-Fi while charging",
            rule = SmartRuleEntity(
                name = "Upload new 4K videos to Drive",
                sourceFolderUri = SmartRuleExecutor.MEDIASTORE_PREFIX + "DCIM/",
                sourceFolderLabel = "DCIM",
                extensionsCsv = "video/",
                minVideoHeightPx = 2160,
                actionType = RuleActionType.UPLOAD_CLOUD.name,
                targetDestinationUri = SmartRuleExecutor.DRIVE_PREFIX + SmartRuleExecutor.DRIVE_AUTO,
                targetDestinationLabel = "DriveLift Backup / phone folder name",
                requireWifi = true,
                requireCharging = true,
            ),
        ),
        RuleTemplate(
            key = "downloads-organize",
            title = "Organize Downloads",
            description = "Move .apk and .pdf files out of Downloads a day after they arrive",
            rule = SmartRuleEntity(
                name = "Organize Downloads",
                extensionsCsv = "apk, pdf",
                minAgeHours = 24,
                actionType = RuleActionType.MOVE_LOCAL.name,
                conflictPolicy = ConflictPolicy.RENAME.name,
            ),
        ),
        RuleTemplate(
            key = "screenshots-archive",
            title = "Archive old screenshots",
            description = "Move screenshots older than 30 days into an archive folder",
            rule = SmartRuleEntity(
                name = "Archive old screenshots",
                extensionsCsv = "image/",
                minAgeHours = 30 * 24,
                actionType = RuleActionType.MOVE_LOCAL.name,
                conflictPolicy = ConflictPolicy.RENAME.name,
            ),
        ),
    )
}
