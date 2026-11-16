package com.drivelift.app

import android.app.Application
import android.content.Context
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

/** Process-wide singletons. Small enough that a DI framework would be overhead. */
class Graph(app: Application) {
    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "drivelift.db")
        .fallbackToDestructiveMigration()
        .build()
    val prefs = Prefs(app)
    val media = MediaRepository(app)
    val drive = DriveClient(app, prefs)
    val transfers = TransferRepository(app, db, prefs, media)

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
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
}
