package com.drivelift.app.feature.cockpit

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.test.core.app.ApplicationProvider
import com.drivelift.app.feature.cockpit.notifications.CockpitCallActionsController
import com.drivelift.app.feature.cockpit.notifications.NotificationActionKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Builds real notifications (Notification.CallStyle and plain action buttons) and checks which live
 * `PendingIntent`s the controller caches for them. Sending the intents is not covered here: it
 * needs a phone with a real call or screen recording.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CockpitCallActionsControllerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun pending(name: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        name.hashCode(),
        Intent("drivelift.test.$name").setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(title: String): Notification.Action = Notification.Action.Builder(
        Icon.createWithResource(context, android.R.drawable.ic_menu_call),
        title,
        pending("action_$title"),
    ).build()

    private fun caller(): Person = Person.Builder().setName("Amina Khan").build()

    private fun builder(): Notification.Builder = Notification.Builder(context, "drivelift-test")
        .setSmallIcon(android.R.drawable.ic_menu_call)
        .setCategory(Notification.CATEGORY_CALL)
        .setContentIntent(pending("content"))

    @Test
    fun callStyleIncomingCachesAnswerAndDeclineFromTheExtras() {
        val notification = builder()
            .setStyle(Notification.CallStyle.forIncomingCall(caller(), pending("decline"), pending("answer")))
            .build()
        val controller = CockpitCallActionsController(context)
        controller.cacheCall("incoming", notification)

        assertTrue(controller.hasHandle("incoming", NotificationActionKind.ANSWER))
        assertTrue(controller.hasHandle("incoming", NotificationActionKind.DECLINE))
        assertFalse(controller.hasHandle("incoming", NotificationActionKind.HANG_UP))
    }

    @Test
    fun callStyleOngoingCachesHangUpAndMuteAndSpeakerButtons() {
        val notification = builder()
            .setStyle(Notification.CallStyle.forOngoingCall(caller(), pending("hangup")))
            .addAction(action("Mute"))
            .addAction(action("Speaker"))
            .build()
        val controller = CockpitCallActionsController(context)
        controller.cacheCall("ongoing", notification)

        assertTrue(controller.hasHandle("ongoing", NotificationActionKind.HANG_UP))
        assertTrue(controller.hasHandle("ongoing", NotificationActionKind.MUTE))
        assertTrue(controller.hasHandle("ongoing", NotificationActionKind.SPEAKER))
        assertFalse(controller.hasHandle("ongoing", NotificationActionKind.ANSWER))
    }

    @Test
    fun plainActionButtonsAreMatchedByTitleForVoipApps() {
        val notification = builder()
            .addAction(action("Decline"))
            .addAction(action("Answer"))
            .build()
        val controller = CockpitCallActionsController(context)
        controller.cacheCall("voip", notification)

        assertTrue(controller.hasHandle("voip", NotificationActionKind.ANSWER))
        assertTrue(controller.hasHandle("voip", NotificationActionKind.DECLINE))
        assertFalse(controller.hasHandle("voip", NotificationActionKind.MUTE))
    }

    @Test
    fun screenRecordingStopActionIsCachedAndClearedWithTheNotification() {
        val notification = Notification.Builder(context, "drivelift-test")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .addAction(action("Share"))
            .addAction(action("Stop"))
            .build()
        val controller = CockpitCallActionsController(context)
        controller.cacheScreenRecording("recording", notification)
        assertTrue(controller.hasHandle("recording", NotificationActionKind.STOP))

        controller.clear("recording")
        assertFalse(controller.hasHandle("recording", NotificationActionKind.STOP))
    }

    @Test
    fun notificationWithoutAStopButtonCachesNothing() {
        val notification = Notification.Builder(context, "drivelift-test")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .addAction(action("Share"))
            .build()
        val controller = CockpitCallActionsController(context)
        controller.cacheScreenRecording("recording", notification)
        assertFalse(controller.hasHandle("recording", NotificationActionKind.STOP))
    }
}
