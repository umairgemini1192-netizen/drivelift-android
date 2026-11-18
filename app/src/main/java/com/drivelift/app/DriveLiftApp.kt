package com.drivelift.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.drivelift.app.data.AppDatabase
import com.drivelift.app.data.MediaRepository
import com.drivelift.app.data.Prefs
import com.drivelift.app.drive.DriveClient
import com.drivelift.app.transfer.Notifications
import com.drivelift.app.transfer.TransferRepository
import com.drivelift.app.ui.AppLock
import com.drivelift.app.ui.Authenticator
import com.drivelift.app.ui.BiometricAuthenticator

/** Process-wide singletons. Small enough that a DI framework would be overhead. */
class Graph(app: Application) {
    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "drivelift.db")
        .fallbackToDestructiveMigration()
        .build()
    val prefs = Prefs(app)
    val media = MediaRepository(app)
    val drive = DriveClient(app, prefs)
    /** Keeps a user-directed Drive trash apart from verification and phone removal (issue #36). */
    val driveCopyFence = com.drivelift.app.transfer.DriveCopyFence()
    val transfers = TransferRepository(app, db, prefs, media, driveCopyFence)
    /** In memory only, so a new process starts locked. */
    val lock = AppLock()
    /** Replaced by a fake in tests; the app uses Android's BiometricPrompt. */
    var authenticator: Authenticator = BiometricAuthenticator(lock)
    /** Replaced in tests; the app asks Android whether Media management was granted. */
    var canManageMedia: (Context) -> Boolean = { com.drivelift.app.data.MediaAccess.canManageMedia(it) }
    /**
     * Builds Android's request that moves phone files to the trash (user-directed delete, issue
     * #36). Replaced in tests, which cannot show Android's dialog.
     */
    var trashRequest: (android.content.ContentResolver, List<android.net.Uri>) -> android.app.PendingIntent =
        { resolver, uris -> android.provider.MediaStore.createTrashRequest(resolver, uris, true) }
    /** Reads one file's trash state back from MediaStore after a trash request. Replaced in tests. */
    var trashState: (android.content.ContentResolver, android.net.Uri) -> com.drivelift.app.data.TrashState =
        { resolver, uri -> com.drivelift.app.data.queryTrashState(resolver, uri) }

    /**
     * Opens the unredacted original bytes of a phone file for the duplicate check (issue #42),
     * the same read the verifier uses. Replaced in tests, which have no original-file access.
     */
    var openOriginalStream: (Context, android.net.Uri) -> java.io.InputStream = { context, uri ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(com.drivelift.app.data.MediaAccess.open(context, uri))
    }
    /** Wall clock for the times the Storage screen shows. Fixed in screenshot tests. */
    var clock: () -> Long = System::currentTimeMillis

    companion object {
        fun get(context: Context): Graph = (context.applicationContext as DriveLiftApp).graph
    }
}

class DriveLiftApp : Application(), ImageLoaderFactory {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        Notifications.ensureChannels(this)
        // Pick up anything a reboot, crash or force-stop left unfinished.
        graph.transfers.recover()
        // Turning the screen off ends the unlocked session (owner decision on issue 19).
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = graph.lock.lock()
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
}
