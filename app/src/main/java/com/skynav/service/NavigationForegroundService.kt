package com.skynav.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.room.Room
import com.skynav.BuildConfig
import com.skynav.data.basestation.BaseStationRepository
import com.skynav.data.basestation.SkyNavDatabase
import com.skynav.data.session.NavigationSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Lifecycle only: foreground notification, database opening, and session start/stop.
 * All positioning logic lives in the domain layer.
 */
class NavigationForegroundService : Service() {

    // Single-threaded: IMU and cell callbacks both mutate the same EKF state.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var session: NavigationSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (session == null) {
            scope.launch {
                val db = Room.databaseBuilder(applicationContext, SkyNavDatabase::class.java, "skynav.db")
                    .build()
                val repo = BaseStationRepository(db.baseStationDao())
                importStationsIfEmpty(repo)
                val index = repo.loadIndex()
                session = NavigationSession(applicationContext, index, scope, recordDrive = BuildConfig.DEBUG)
                    .also { it.start() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Loads `stations.csv` once. An external file wins; otherwise the bundled asset is used.
     * The asset holds the OpenCelliD hit for the test serving cell and is marked unverified.
     */
    private suspend fun importStationsIfEmpty(repo: BaseStationRepository) {
        if (repo.count() > 0) return
        val file = File(getExternalFilesDir(null), "stations.csv")
        val text = if (file.exists()) {
            file.readText()
        } else {
            assets.open("stations.csv").bufferedReader().use { it.readText() }
        }
        repo.importCsv(text)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SkyNav")
            .setContentText("Cellular positioning active")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

    companion object {
        const val CHANNEL_ID = "navigation"
        const val NOTIFICATION_ID = 1
    }
}
