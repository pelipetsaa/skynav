package com.skynav.data.session

import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.hardware.SensorManager
import com.skynav.BuildConfig
import com.skynav.domain.inertial.GuideSource
import com.skynav.data.basestation.StationIndex
import com.skynav.data.logging.DriveRecorder
import com.skynav.data.logging.GpsTruthLogger
import com.skynav.data.lookup.WigleClient
import com.skynav.data.sensors.ImuDataCollector
import com.skynav.data.telephony.CellularDataCollector
import com.skynav.data.wifi.WifiDataCollector
import com.skynav.data.lookup.MapillaryClient
import com.skynav.data.photo.FrameEmbedder
import com.skynav.data.photo.StreetPhotoCheck
import com.skynav.data.routing.OverpassClient
import com.skynav.domain.geo.Geo
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.inertial.InertialGuide
import com.skynav.domain.model.CellMeasurement
import com.skynav.domain.positioning.NavigationEngine
import com.skynav.domain.positioning.NavigationSnapshot
import com.skynav.domain.positioning.RoadGraph
import com.skynav.domain.positioning.WifiObservation
import com.skynav.domain.wifi.WifiNetwork
import kotlin.math.cos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Wires Android collectors into [NavigationEngine]. Holds no algorithms itself.
 *
 * The local frame is anchored at the serving station of the first recognised cell scan,
 * so the engine starts only once a scan resolves to a known station.
 *
 * Between radio fixes the phone inertial guide leads the mark. External speed hardware is not used.
 */
class NavigationSession(
    private val context: Context,
    private val stations: StationIndex,
    private val scope: CoroutineScope,
    private val recordDrive: Boolean,
) {
    @Volatile
    private var engine: NavigationEngine? = null

    @Volatile
    var latest: NavigationSnapshot? = null
        private set

    private var recorder: DriveRecorder? = null
    private var gpsTruth: GpsTruthLogger? = null
    private val jobs = mutableListOf<Job>()
    private var lastLoggedRadioMs: Long? = null
    private val guide = InertialGuide()
    private val overpass = OverpassClient()
    private var roadBackoffUntilMs = 0L
    private val wigle = WigleClient(BuildConfig.WIGLE_API_NAME, BuildConfig.WIGLE_API_TOKEN)
    private val mapillary = MapillaryClient(BuildConfig.MAPILLARY_TOKEN)
    /** Null means the BSSID was looked up and WiGLE had no coordinate. Not retried this session. */
    private val wifiHits = HashMap<String, WifiObservation?>()

    fun start() {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val cells = CellularDataCollector(telephony)
        val wifi = WifiDataCollector(context)
        wifi.requestScan()
        val imu = ImuDataCollector(context.getSystemService(SensorManager::class.java))
        val speedFile = File(context.filesDir, "speed-table.txt")
        if (speedFile.exists()) guide.restoreSpeedTable(speedFile.readText())

        if (recordDrive) {
            val rec = DriveRecorder.open(context)
            recorder = rec
            if (BuildConfig.DEBUG) {
                gpsTruth = GpsTruthLogger(
                    context.getSystemService(LocationManager::class.java),
                    rec,
                ) { lat, lon, accuracy, speed, time ->
                    NavigationHub.offerGps(lat, lon, accuracy, speed, time)
                }.also { it.start() }
            }
        }

        jobs += scope.launch {
            var lastPublishMs = 0L
            var appliedCue = 0
            imu.samples().collect { sample ->
                recorder?.imu(sample)
                NavigationHub.takeManual()?.let { guide.onManual(it, sample.timestampMs) }
                NavigationHub.takeDriveCommands().let { (reverse, pause) ->
                    if (reverse) guide.reverse()
                    if (pause) guide.togglePaused()
                }
                guide.setGpsEnabled(NavigationHub.gpsEnabled.value)
                if (guide.reckoning.hasPosition && guide.reckoning.hasHeading) {
                    NavigationHub.notePose(
                        guide.reckoning.position(),
                        guide.reckoning.headingDeg,
                        guide.source == GuideSource.GPS,
                    )
                }
                NavigationHub.takeStreetPhoto()?.let { guide.offerStreetPhoto(it, sample.timestampMs) }
                NavigationHub.takeGps()?.let { offer ->
                    val learned = guide.offerGps(
                        LatLon(offer.latitude, offer.longitude),
                        offer.accuracyM,
                        offer.timeMs,
                        offer.speedMps,
                    )
                    if (learned) speedFile.writeText(guide.speedTableText())
                }
                val eng = engine
                if (eng != null) {
                    eng.onImu(sample)
                    val cueGen = NavigationHub.cueGeneration
                    if (cueGen != appliedCue) {
                        appliedCue = cueGen
                        NavigationHub.routeCue?.let { cue ->
                            eng.onRouteCue(cue.bearingRad, cue.bearingVariance, cue.speedMps)
                            if (!guide.needsMark) guide.onRouteBearing(cue.bearingRad)
                        }
                    }
                }
                val guided = guide.onImu(sample)
                if (sample.timestampMs - lastPublishMs >= PUBLISH_PERIOD_MS) {
                    lastPublishMs = sample.timestampMs
                    val snap = eng?.current()
                    if (snap != null || guided != null) NavigationHub.publish(snap, radio = false, guide = guided)
                }
            }
        }

        if (mapillary.configured) {
            jobs += scope.launch(Dispatchers.IO) {
                val checker = runCatching {
                    StreetPhotoCheck(mapillary, FrameEmbedder.load(context))
                }.getOrNull() ?: return@launch
                try {
                    while (isActive) {
                        val frame = NavigationHub.takeCameraFrame()
                        if (frame == null) {
                            delay(500)
                            continue
                        }
                        val pose = NavigationHub.carPose
                        val matched = if (pose == null || pose.gps) {
                            null
                        } else {
                            runCatching { checker.check(frame, pose.position, pose.headingDeg) }.getOrNull()
                        }
                        frame.recycle()
                        if (matched != null) NavigationHub.offerStreetPhoto(matched)
                    }
                } finally {
                    checker.close()
                }
            }
        }

        jobs += scope.launch {
            while (isActive) {
                val place = if (SystemClock.elapsedRealtime() < roadBackoffUntilMs) null else guide.roadsWanted()
                if (place != null) loadRoads(place)
                delay(ROAD_POLL_MS)
            }
        }

        jobs += scope.launch {
            while (isActive) {
                val scan = freshOrCache(cells)
                val visible = runCatching { wifi.snapshot() }.getOrDefault(emptyList())
                val located = locate(visible)
                val now = SystemClock.elapsedRealtime()
                recorder?.let { rec -> visible.forEach { rec.wifi(now, it, located[it.bssid]) } }
                val radioMs = scan.maxOfOrNull { it.timestampMs }
                if (radioMs != null && radioMs != lastLoggedRadioMs) {
                    recorder?.cells(scan)
                    lastLoggedRadioMs = radioMs
                }
                val current = engine ?: createEngine(scan)?.also { engine = it }
                if (current != null) {
                    val before = current.appliedRadioMs()
                    val snap = current.onCells(now, scan, located.values.filterNotNull())
                    if (snap != null) {
                        latest = snap
                        val applied = current.appliedRadioMs() != before
                        if (applied) {
                            guide.onRadio(
                                LatLon(snap.position.latitude, snap.position.longitude),
                                snap.position.accuracyM,
                                now,
                            )
                        }
                        NavigationHub.publish(snap, radio = applied, guide = guide.fix(now))
                    }
                }
                delay(SCAN_PERIOD_MS)
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        gpsTruth?.stop()
        File(context.filesDir, "speed-table.txt").writeText(guide.speedTableText())
        recorder?.close()
        recorder = null
    }

    /**
     * Looks up the strongest unseen BSSIDs once per session. A miss is remembered so a
     * quota error does not turn into a request on every scan.
     */
    private suspend fun locate(visible: List<WifiNetwork>): Map<String, WifiObservation?> {
        val pending = visible.filter { it.bssid !in wifiHits }.take(MAX_WIFI_LOOKUPS)
        for (network in pending) {
            val point = if (!wigle.configured) {
                null
            } else {
                withContext(Dispatchers.IO) { runCatching { wigle.lookup(network.bssid) }.getOrNull() }
            }
            wifiHits[network.bssid] = point?.let {
                WifiObservation(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    rssiDbm = network.rssiDbm,
                    accuracyM = WifiObservation.CROWDSOURCED_ACCURACY_M,
                )
            }
        }
        return visible.mapNotNull { network ->
            val hit = wifiHits[network.bssid] ?: return@mapNotNull null
            network.bssid to hit.copy(rssiDbm = network.rssiDbm)
        }.toMap()
    }

    private suspend fun loadRoads(place: LatLon) {
        val radius = ROAD_RADIUS_M
        val dLat = Math.toDegrees(radius / Geo.EARTH_RADIUS_M)
        val dLon = Math.toDegrees(radius / (Geo.EARTH_RADIUS_M * cos(Math.toRadians(place.lat)).coerceAtLeast(0.2)))
        val south = place.lat - dLat
        val north = place.lat + dLat
        val west = place.lon - dLon
        val east = place.lon + dLon
        val xml = withContext(Dispatchers.IO) { runCatching { overpass.highways(south, west, north, east) }.getOrNull() }
        val frame = LocalFrame(place)
        val graph = xml?.let { runCatching { RoadGraph.fromOsmXml(it, frame) }.getOrNull() }
        if (graph == null || graph.ways.isEmpty()) {
            roadBackoffUntilMs = SystemClock.elapsedRealtime() + ROAD_BACKOFF_MS
            return
        }
        guide.setRoads(graph, frame, south, west, north, east)
    }

    private fun createEngine(scan: List<CellMeasurement>): NavigationEngine? {
        val serving = scan.firstNotNullOfOrNull { m -> m.key?.let { stations[it] } } ?: return null
        val frame = LocalFrame(LatLon(serving.latitude, serving.longitude))
        return NavigationEngine(frame, stations::get)
    }

    private companion object {
        const val SCAN_PERIOD_MS = 5_000L
        const val PUBLISH_PERIOD_MS = 200L
        const val REQUEST_TIMEOUT_MS = 8_000L
        const val MAX_WIFI_LOOKUPS = 5
        const val ROAD_POLL_MS = 2_000L
        const val ROAD_BACKOFF_MS = 20_000L
        const val ROAD_RADIUS_M = 1_500.0

        suspend fun freshOrCache(cells: CellularDataCollector): List<CellMeasurement> =
            runCatching { withTimeout(REQUEST_TIMEOUT_MS) { cells.requestFresh() } }
                .getOrElse { runCatching { cells.snapshot() }.getOrDefault(emptyList()) }
    }
}
