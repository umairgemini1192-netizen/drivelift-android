package com.drivelift.app.feature.intelligence

import com.drivelift.app.feature.intelligence.data.SmartRuleEntity
import com.drivelift.app.feature.intelligence.rules.MatchOutcome
import com.drivelift.app.feature.intelligence.rules.RuleFile
import com.drivelift.app.feature.intelligence.rules.SafetySkip
import com.drivelift.app.feature.intelligence.rules.SmartRuleMatcher
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartRuleMatcherTest {

    private val now = 1_800_000_000_000L
    private val matcher = SmartRuleMatcher(now = { now })
    private val hour = SmartRuleMatcher.HOUR_MS

    private fun file(
        name: String,
        mime: String = "application/octet-stream",
        ageMs: Long = 48 * hour,
        size: Long = 1_000L,
        height: Int? = null,
        uri: String = "content://test/$name",
    ) = RuleFile(uri = uri, name = name, mimeType = mime, sizeBytes = size, lastModifiedMs = now - ageMs, videoHeightPx = height)

    private val rule = SmartRuleEntity(name = "t", sourceFolderUri = "s", targetDestinationUri = "t")

    @Test
    fun fourKVideoHeightFilter() {
        val r = rule.copy(extensionsCsv = "video/", minVideoHeightPx = 2160)
        assertEquals(MatchOutcome.Match, matcher.evaluate(r, file("a.mp4", "video/mp4", height = 2160), emptySet()))
        assertEquals(MatchOutcome.Match, matcher.evaluate(r, file("b.mp4", "video/mp4", height = 4320), emptySet()))
        assertEquals(MatchOutcome.NoMatch, matcher.evaluate(r, file("c.mp4", "video/mp4", height = 1080), emptySet()))
        assertEquals(MatchOutcome.NoMatch, matcher.evaluate(r, file("d.mp4", "video/mp4", height = null), emptySet()))
        assertEquals(MatchOutcome.NoMatch, matcher.evaluate(r, file("e.jpg", "image/jpeg", height = 3000), emptySet()))
    }

    @Test
    fun twentyFourHourAgeThreshold() {
        val r = rule.copy(minAgeHours = 24)
        assertEquals(MatchOutcome.NoMatch, matcher.evaluate(r, file("new.pdf", ageMs = 23 * hour), emptySet()))
        assertEquals(MatchOutcome.Match, matcher.evaluate(r, file("old.pdf", ageMs = 25 * hour), emptySet()))
    }

    @Test
    fun extensionAndMimeMatching() {
        val r = rule.copy(extensionsCsv = "apk, .pdf")
        assertEquals(MatchOutcome.Match, matcher.evaluate(r, file("app.APK"), emptySet()))
        assertEquals(MatchOutcome.Match, matcher.evaluate(r, file("doc.pdf"), emptySet()))
        assertEquals(MatchOutcome.NoMatch, matcher.evaluate(r, file("archive.zip"), emptySet()))
        assertEquals(MatchOutcome.NoMatch, matcher.evaluate(r, file("pdf"), emptySet()))
        val images = rule.copy(extensionsCsv = "image/*")
        assertEquals(MatchOutcome.Match, matcher.evaluate(images, file("s.png", "image/png"), emptySet()))
        assertEquals(MatchOutcome.Match, matcher.evaluate(rule, file("anything.bin"), emptySet()))
    }

    @Test
    fun sizeBounds() {
        val r = rule.copy(minSizeBytes = 100, maxSizeBytes = 200)
        assertEquals(MatchOutcome.NoMatch, matcher.evaluate(r, file("a", size = 99), emptySet()))
        assertEquals(MatchOutcome.Match, matcher.evaluate(r, file("b", size = 150), emptySet()))
        assertEquals(MatchOutcome.NoMatch, matcher.evaluate(r, file("c", size = 201), emptySet()))
    }

    @Test
    fun sixtySecondActiveFileSafetyExclusion() {
        assertEquals(
            MatchOutcome.Skipped(SafetySkip.ACTIVE_FILE),
            matcher.evaluate(rule, file("recording.mp4", "video/mp4", ageMs = 30_000L), emptySet()),
        )
        assertEquals(MatchOutcome.Match, matcher.evaluate(rule, file("done.mp4", "video/mp4", ageMs = 61_000L), emptySet()))
    }

    @Test
    fun transferQueueLockExclusion() {
        val f = file("queued.jpg", "image/jpeg")
        assertEquals(MatchOutcome.Skipped(SafetySkip.TRANSFER_LOCKED), matcher.evaluate(rule, f, setOf(f.uri)))
    }
}
