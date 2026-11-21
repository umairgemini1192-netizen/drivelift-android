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
import com.drivelift.app.data.MediaThumbnailFetcher
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
        // Each schema step is a real migration, so an update keeps the transfer ledger. Only a
        // downgrade (an older build installed over a newer one) starts a fresh database.
        .addMigrations(*com.drivelift.app.data.ALL_MIGRATIONS)
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()
    val prefs = Prefs(app)
    val media = MediaRepository(app)
    val drive = DriveClient(app, prefs)
    /** The last Drive folder count, kept for the next launch (issue #14). No backup: private to this phone. */
    val driveFolderStore = com.drivelift.app.data.DriveFolderStore(java.io.File(app.noBackupFilesDir, "drive-folder-counts.txt"))
    /** Keeps a user-directed Drive trash apart from verification and phone removal (issue #36). */
    val driveCopyFence = com.drivelift.app.transfer.DriveCopyFence()
    /** Keeps phone trash requests apart from queuing, removal and kept duplicate copies (#36, #42). */
    val phoneFileFence = com.drivelift.app.transfer.PhoneFileFence()
    val transfers = TransferRepository(app, db, prefs, media, driveCopyFence, phoneFileFence)
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
     * Sets a phone file's name (rename, issue #42). Throws SecurityException when DriveLift does
     * not own the file and Android's write consent is needed first. Replaced in tests.
     */
    var renameMedia: (android.content.ContentResolver, android.net.Uri, String) -> Unit =
        { resolver, uri, name -> com.drivelift.app.data.updateDisplayName(resolver, uri, name) }
    /** Reads a phone file's name back from MediaStore after a rename; null if it cannot. Replaced in tests. */
    var mediaName: (android.content.ContentResolver, android.net.Uri) -> String? =
        { resolver, uri -> com.drivelift.app.data.queryDisplayName(resolver, uri) }
    /** Builds Android's write-consent request for files DriveLift does not own. Replaced in tests. */
    var writeRequest: (android.content.ContentResolver, List<android.net.Uri>) -> android.app.PendingIntent =
        { resolver, uris -> android.provider.MediaStore.createWriteRequest(resolver, uris) }
    /** Moves a phone file into another folder ([relativePath]). Replaced in tests. */
    var moveMedia: (android.content.ContentResolver, android.net.Uri, String) -> Unit =
        { resolver, uri, path -> com.drivelift.app.data.updateRelativePath(resolver, uri, path) }
    /** Reads a phone file's folder back from MediaStore after a move; null if it cannot. Replaced in tests. */
    var mediaFolder: (android.content.ContentResolver, android.net.Uri) -> String? =
        { resolver, uri -> com.drivelift.app.data.queryRelativePath(resolver, uri) }
    /** Names of the files MediaStore lists in a folder ([relativePath]), for the rename collision check. */
    var folderNames: (android.content.ContentResolver, String) -> List<String> =
        { resolver, path -> com.drivelift.app.data.queryFolderNames(resolver, path) }

    /**
     * Opens the unredacted original bytes of a phone file for the duplicate check (issue #42),
     * the same read the verifier uses. Replaced in tests, which have no original-file access.
     */
    var openOriginalStream: (Context, android.net.Uri) -> java.io.InputStream = { context, uri ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(com.drivelift.app.data.MediaAccess.open(context, uri))
    }
    /** Wall clock for the times the Storage screen shows. Fixed in screenshot tests. */
    var clock: () -> Long = System::currentTimeMillis

    /** Self-contained Storage Intelligence module (local + multi-cloud analysis). */
    val storageIntelligence: com.drivelift.app.feature.storageintelligence.di.StorageIntelligenceModule by lazy {
        com.drivelift.app.feature.storageintelligence.di.StorageIntelligenceModule(app, this)
    }

    /** Self-contained Zero-Config Peer & Desktop Drop module (LAN + Hotspot file drop). */
    val desktopDrop: com.drivelift.app.feature.desktopdrop.di.DesktopDropModule by lazy {
        com.drivelift.app.feature.desktopdrop.di.DesktopDropModule(app, this)
    }

    /** Self-contained On-Device Intelligence & Automation module (offline OCR search, Smart rules). */
    val intelligence: com.drivelift.app.feature.intelligence.IntelligenceAutomationModule by lazy {
        com.drivelift.app.feature.intelligence.IntelligenceAutomationModule(app, this)
    }

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
            // Android's cached thumbnails first; the original file is decoded only when Android
            // has none for it.
            .components {
                add(MediaThumbnailFetcher.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
}
