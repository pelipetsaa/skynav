package com.skynav.data.logging

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.skynav.domain.model.CellMeasurement
import com.skynav.domain.model.ImuSample
import com.skynav.domain.positioning.WifiObservation
import com.skynav.domain.replay.DriveLog
import com.skynav.domain.wifi.WifiNetwork
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Appends [com.skynav.domain.replay.DriveEvent] lines to a per-drive CSV file. */
class DriveRecorder(private val file: File) : Closeable {

    private val writer = file.bufferedWriter()

    @Synchronized
    private fun line(text: String) {
        writer.write(text)
        writer.newLine()
    }

    @Synchronized
    fun imu(s: ImuSample) = line(DriveLog.formatImu(s))

    @Synchronized
    fun obd(timestampMs: Long, speedMps: Double) = line(DriveLog.formatObd(timestampMs, speedMps))

    @Synchronized
    fun cells(cells: List<CellMeasurement>) = cells.forEach { line(DriveLog.formatCell(it)) }

    @Synchronized
    fun wifi(timestampMs: Long, network: WifiNetwork, hit: WifiObservation?) =
        line(
            DriveLog.formatWifi(
                timestampMs = timestampMs,
                bssid = network.bssid,
                rssiDbm = network.rssiDbm,
                frequencyMhz = network.frequencyMhz,
                latitude = hit?.latitude,
                longitude = hit?.longitude,
                accuracyM = hit?.accuracyM,
            ),
        )

    @Synchronized
    fun gps(timestampMs: Long, lat: Double, lon: Double, accuracyM: Float) =
        line(DriveLog.formatGps(timestampMs, lat, lon, accuracyM))

    @Synchronized
    fun flush() = writer.flush()

    @Synchronized
    override fun close() = writer.close()

    companion object {
        /** Creates `drives/drive_<yyyyMMdd_HHmmss>.csv` in the app's external files directory. */
        fun open(context: Context): DriveRecorder {
            val dir = File(context.getExternalFilesDir(null), "drives").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            return DriveRecorder(File(dir, "drive_$stamp.csv"))
        }
    }
}

/**
 * GPS for the trust check. The guide accepts it only when it agrees with the network
 * and with the mark, and ignores a jump no car could have driven.
 * Call only in debug builds.
 */
class GpsTruthLogger(
    private val locationManager: LocationManager,
    private val recorder: DriveRecorder,
    private val onFix: ((lat: Double, lon: Double, accuracyM: Double, speedMps: Double, timeMs: Long) -> Unit)? = null,
) {
    private val listener = LocationListener { loc: Location ->
        val timeMs = loc.elapsedRealtimeNanos / 1_000_000L
        recorder.gps(
            timestampMs = timeMs,
            lat = loc.latitude,
            lon = loc.longitude,
            accuracyM = loc.accuracy,
        )
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        onFix?.invoke(loc.latitude, loc.longitude, loc.accuracy.toDouble(), speed, timeMs)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1_000L,
            0f,
            listener,
            Looper.getMainLooper(),
        )
    }

    fun stop() {
        locationManager.removeUpdates(listener)
    }
}
