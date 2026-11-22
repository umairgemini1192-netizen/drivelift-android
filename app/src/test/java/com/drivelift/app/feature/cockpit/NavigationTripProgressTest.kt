package com.drivelift.app.feature.cockpit

import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.CockpitStateCoordinator
import com.drivelift.app.feature.cockpit.activities.NavigationTripTracker
import com.drivelift.app.feature.cockpit.activities.TripDistanceFormat
import com.drivelift.app.feature.cockpit.notifications.LiveNotificationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Trip progress for the expanded navigation card: parsed distance, start snapshot, drove and left. */
class NavigationTripProgressTest {

    // ---- tracker ----

    @Test
    fun theFirstDistanceIsTheStartAndLaterOnesGiveKilometresDrove() {
        val tracker = NavigationTripTracker()

        val first = tracker.update(remainingKm = 11.6)!!
        assertEquals(11.6, first.startKm, 1e-9)
        assertEquals(0.0, first.droveKm, 1e-9)

        val later = tracker.update(remainingKm = 8.4)!!
        assertEquals(11.6, later.startKm, 1e-9)
        assertEquals(8.4, later.remainingKm, 1e-9)
        assertEquals(3.2, later.droveKm, 1e-9)
    }

    @Test
    fun droveNeverGoesBelowZeroWhenTheRemainingDistanceGrowsABit() {
        val tracker = NavigationTripTracker()
        tracker.update(10.0)
        val bumped = tracker.update(10.3)!!
        assertEquals(0.0, bumped.droveKm, 1e-9)
        assertEquals(10.0, bumped.startKm, 1e-9)
    }

    @Test
    fun aClearlyLongerRouteStartsANewTrip() {
        val tracker = NavigationTripTracker()
        tracker.update(10.0)
        tracker.update(6.0)
        val reroute = tracker.update(14.0)!!
        assertEquals(14.0, reroute.startKm, 1e-9)
        assertEquals(0.0, reroute.droveKm, 1e-9)
    }

    @Test
    fun aDifferentDestinationStartsANewTrip() {
        val tracker = NavigationTripTracker()
        tracker.update(10.0, routeKey = "maps|Office")
        tracker.update(7.0, routeKey = "maps|Office")
        val other = tracker.update(7.5, routeKey = "maps|Airport")!!
        assertEquals(7.5, other.startKm, 1e-9)
        assertEquals(0.0, other.droveKm, 1e-9)
    }

    @Test
    fun resetClearsTheStartSoTheNextSessionBeginsFresh() {
        val tracker = NavigationTripTracker()
        tracker.update(10.0)
        tracker.update(4.0)
        tracker.reset()
        assertNull(tracker.currentStartKm)
        val next = tracker.update(5.0)!!
        assertEquals(5.0, next.startKm, 1e-9)
        assertEquals(0.0, next.droveKm, 1e-9)
    }

    @Test
    fun missingOrInvalidDistancesKeepTheStartAndReturnNothing() {
        val tracker = NavigationTripTracker()
        tracker.update(10.0)
        assertNull(tracker.update(null))
        assertNull(tracker.update(Double.NaN))
        assertNull(tracker.update(-1.0))
        assertEquals(10.0, tracker.currentStartKm!!, 1e-9)
    }

    @Test
    fun progressFractionIsDroveOverTheWholeTrip() {
        val tracker = NavigationTripTracker()
        tracker.update(10.0)
        assertEquals(0.4f, tracker.update(6.0)!!.fraction, 1e-4f)
    }

    // ---- coordinator: start snapshot and reset on session end ----

    private fun nav(remainingKm: Double?, destination: String = "") = CockpitLiveActivity.MapsNavActivity(
        maneuverText = "North",
        distanceText = "90 ft",
        etaText = "12 min",
        destinationText = destination,
        notificationKey = "nav_key",
        remainingTripKm = remainingKm,
    )

    @Test
    fun theCoordinatorSnapshotsTheStartAndFillsInKilometresDrove() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(nav(remainingKm = 11.6))
        coordinator.upsertActivity(nav(remainingKm = 8.4))

        val current = coordinator.slotState.value.primary as CockpitLiveActivity.MapsNavActivity
        assertEquals(3.2, current.droveTripKm!!, 1e-9)
        assertEquals(8.4, current.remainingTripKm!!, 1e-9)
    }

    @Test
    fun endingNavigationResetsTheTripSoTheNextOneStartsAtZero() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(nav(remainingKm = 11.6))
        coordinator.upsertActivity(nav(remainingKm = 8.4))

        coordinator.removeByNotificationKey("nav_key")
        coordinator.upsertActivity(nav(remainingKm = 20.0))

        val current = coordinator.slotState.value.primary as CockpitLiveActivity.MapsNavActivity
        assertEquals(0.0, current.droveTripKm!!, 1e-9)
    }

    @Test
    fun anUpdateWithoutADistanceLeavesNoProgress() {
        val coordinator = CockpitStateCoordinator()
        coordinator.upsertActivity(nav(remainingKm = null))
        val current = coordinator.slotState.value.primary as CockpitLiveActivity.MapsNavActivity
        assertNull(current.droveTripKm)
    }

    // ---- footer text ----

    @Test
    fun theFooterReadsKilometresDroveAndLeft() {
        assertEquals("3.2 km drove · 8.4 km left", TripDistanceFormat.footer(3.2, 8.4, miles = false))
        assertEquals("0.0 km drove · 120 km left", TripDistanceFormat.footer(0.0, 120.0, miles = false))
    }

    @Test
    fun milesAreShownInMilesWhenTheSourceAppUsesThem() {
        assertEquals("1.0 mi drove · 3.1 mi left", TripDistanceFormat.footer(1.609344, 5.0, miles = true))
    }

    // ---- parsing the remaining distance from the notification text ----

    @Test
    fun theTripDistanceIsReadFromTheSubTextAndNotTheNextTurn() {
        val trip = LiveNotificationParser.extractRemainingTrip(
            title = "90 ft",
            text = "North",
            subText = "8.4 km · 12 min · 4:35 PM",
        )
        assertNotNull(trip)
        assertEquals(8.4, trip!!.km, 1e-9)
        assertEquals(false, trip.imperial)
    }

    @Test
    fun milesAndFeetAreConvertedAndMarkedImperial() {
        val miles = LiveNotificationParser.extractRemainingTrip("0.3 mi", "Turn left", "5.2 mi · 14 min")!!
        assertEquals(5.2 * 1.609344, miles.km, 1e-6)
        assertEquals(true, miles.imperial)
    }

    @Test
    fun theNextTurnDistanceIsNeverMistakenForTheTrip() {
        assertNull(LiveNotificationParser.extractRemainingTrip("350 m", "Turn right onto Main St", "12 min"))
        // The same figure in the sub text is the turn, not the trip.
        assertNull(LiveNotificationParser.extractRemainingTrip("0.4 km", "Turn right", "0.4 km"))
    }

    @Test
    fun minutesAreNotReadAsMeters() {
        assertNull(LiveNotificationParser.extractRemainingTrip("In 90 ft", "North", "12 min · 4:35 PM"))
    }

    @Test
    fun theTripFallsBackToTheTextWhenTheSubTextHasNone() {
        val trip = LiveNotificationParser.extractRemainingTrip("90 ft", "Continue 6.1 km", "")!!
        assertEquals(6.1, trip.km, 1e-9)
    }
}
