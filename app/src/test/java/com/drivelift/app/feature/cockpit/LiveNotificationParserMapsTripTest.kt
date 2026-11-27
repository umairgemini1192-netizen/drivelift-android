package com.drivelift.app.feature.cockpit

import android.app.Notification
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.MapsTurnDirection
import com.drivelift.app.feature.cockpit.notifications.LiveNotificationParser
import com.drivelift.app.feature.cockpit.notifications.NotificationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Google Maps on a Pixel 8 Pro sometimes sends a headline with no turn distance, for example
 * "towards Main University Rd", with the trip facts in the other lines. The pill and the card must
 * still show the live time and distance, and never fall back to a bare "Navigating".
 */
class LiveNotificationParserMapsTripTest {

    private val bullet = "•"
    private val dot = "·"

    private fun maps(title: String, text: String, subText: String) = NotificationSnapshot(
        key = "0|com.google.android.apps.maps|7|null|1020",
        packageName = LiveNotificationParser.PKG_MAPS,
        title = title,
        text = text,
        subText = subText,
        category = Notification.CATEGORY_NAVIGATION,
        isOngoing = true,
        updatedAtMs = 9_000L,
    )

    private fun parse(snapshot: NotificationSnapshot) =
        LiveNotificationParser.parse(snapshot) as CockpitLiveActivity.MapsNavActivity

    @Test
    fun towardsHeadlineShowsTripTimeAndDistanceInsteadOfNavigating() {
        val parsed = parse(maps("towards Main University Rd", "38 min", "20 km $bullet 12:20 am"))

        assertEquals("", parsed.distanceText)
        assertEquals("38 min", parsed.etaText)
        assertEquals("20 km", parsed.tripDistanceText)
        assertEquals("38 min $dot 20 km", parsed.headlineText)
        assertEquals("towards Main University Rd", parsed.maneuverText)
        assertEquals("Main University Rd", parsed.roadText)
        assertEquals(20.0, parsed.remainingTripKm!!, 0.001)
        assertEquals(MapsTurnDirection.STRAIGHT, parsed.turnDirection)
    }

    @Test
    fun tripSummaryIsNeverTakenForTheDestinationLine() {
        val parsed = parse(maps("towards Main University Rd", "38 min", "20 km $bullet 12:20 am"))

        assertEquals("", parsed.destinationText)
    }

    @Test
    fun swappedTextAndSubTextGiveTheSameResult() {
        val parsed = parse(maps("towards Main University Rd", "20 km $bullet 12:20 am", "38 min"))

        assertEquals("", parsed.distanceText)
        assertEquals("38 min", parsed.etaText)
        assertEquals("20 km", parsed.tripDistanceText)
        assertEquals("38 min $dot 20 km", parsed.headlineText)
        assertEquals("", parsed.destinationText)
        assertEquals("Main University Rd", parsed.roadText)
    }

    @Test
    fun aTurnDistanceInTheTitleStaysTheHeadlineAndTheTripKeepsItsOwnFigure() {
        val parsed = parse(
            maps("350 m - Turn left onto Constitution Ave", "38 min $bullet 20 km $bullet 12:20 am", ""),
        )

        assertEquals("350 m", parsed.distanceText)
        assertEquals("350 m", parsed.headlineText)
        assertEquals("38 min", parsed.etaText)
        assertEquals("20 km", parsed.tripDistanceText)
        assertEquals(MapsTurnDirection.LEFT, parsed.turnDirection)
    }

    @Test
    fun anArrivalClockInTheSummaryDoesNotTurnTheArrowIntoAnArrivalFlag() {
        val parsed = parse(maps("towards Main University Rd", "Arrive 12:20 am", "38 min"))

        assertEquals(MapsTurnDirection.STRAIGHT, parsed.turnDirection)
    }

    @Test
    fun milesAreKeptAsTheAppWroteThem() {
        val parsed = parse(maps("toward Market St", "12 min", "6.2 mi $bullet 10:42 AM"))

        assertEquals("6.2 mi", parsed.tripDistanceText)
        assertEquals("12 min $dot 6.2 mi", parsed.headlineText)
        assertTrue(parsed.tripUsesMiles)
        assertEquals(9.98, parsed.remainingTripKm!!, 0.01)
    }

    @Test
    fun headlineFallsBackToTheTimeAloneAndThenToNothing() {
        val timeOnly = parse(maps("towards Main University Rd", "38 min", ""))
        assertEquals("38 min", timeOnly.headlineText)

        val nothing = parse(maps("towards Main University Rd", "", ""))
        assertEquals("", nothing.headlineText)
    }
}
