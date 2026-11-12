package com.skynav.domain.inertial

import com.skynav.domain.geo.Geo
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.model.ImuSample
import com.skynav.domain.model.Vec3
import com.skynav.domain.positioning.RoadGraph
import java.util.ArrayDeque
import kotlin.math.abs

enum class GuideSource { GPS, INERTIAL, MANUAL }

fun GuideSource.label(): String = when (this) {
    GuideSource.GPS -> "GPS"
    GuideSource.INERTIAL -> "Инерция"
    GuideSource.MANUAL -> "Вручную"
}

data class GuideFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double,
    val headingDeg: Double?,
    val source: GuideSource,
    val needsMark: Boolean,
    val timestampMs: Long,
    val speedLost: Boolean = false,
    val speedMps: Double = 0.0,
    val paused: Boolean = false,
    val markSequence: Int = 0,
)

/**
 * Between radio fixes the phone leads the mark. Cell and Wi-Fi positions check GPS;
 * they are not the label on the banner. Trusted GPS becomes the source on its own.
 * The accelerometer adds distance only after its axis is learned, scaled by the GPS table.
 */
class InertialGuide {
    val reckoning = DeadReckoning()
    private val calibrator = GyroCalibrator()
    private val accelAxis = AccelAxis()
    private val accelWindow = ArrayDeque<Pair<Long, Vec3>>()
    private val spoofing = GpsSpoofingDetector()
    private val gpsReturn = GpsReturnDetector()
    private val speedTable = SpeedTable()
    private val turnSign = TurnSignChecker()
    private val stillGate = StillGate()
    private val bends = BendMatcher()
    private val path = PathMatcher()

    private var bias: Vec3? = null
    private var pendingBias: Vec3? = null
    private var up = Vec3(0.0, 0.0, 1.0)
    private var lastImuMs: Long? = null
    private var gpsAccuracyM = 0.0
    private var lastOnRoad: LatLon? = null
    private var firstMark: LatLon? = null
    private var firstMarkMs: Long? = null

    private var graph: RoadGraph? = null
    private var roadFrame: LocalFrame? = null
    private var minX = 0.0
    private var maxX = 0.0
    private var minY = 0.0
    private var maxY = 0.0
    private var pendingTap: LatLon? = null
    private var pendingTapMs = 0L

    private var anchor: LatLon? = null
    private var anchorMs: Long? = null
    private var anchorAccuracyM = 0.0
    private var lastTeacherSpeed: Double? = null
    private var roadHeading: Double? = null
    private var heldAtCrossing = false

    var source: GuideSource = GuideSource.INERTIAL
        private set
    var needsMark: Boolean = false
        private set
    var speedLost: Boolean = true
        private set
    var paused: Boolean = false
        private set
    var markSequence: Int = 0
        private set
    var gpsEnabled: Boolean = true
        private set

    fun hasFix(): Boolean = reckoning.hasPosition

    fun setRoads(roads: RoadGraph, frame: LocalFrame, south: Double, west: Double, north: Double, east: Double) {
        graph = roads
        roadFrame = frame
        val corners = listOf(
            frame.toEnu(LatLon(south, west)),
            frame.toEnu(LatLon(south, east)),
            frame.toEnu(LatLon(north, west)),
            frame.toEnu(LatLon(north, east)),
        )
        minX = corners.minOf { it.x }
        maxX = corners.maxOf { it.x }
        minY = corners.minOf { it.y }
        maxY = corners.maxOf { it.y }
        pendingTap?.let { tap ->
            val at = pendingTapMs
            pendingTap = null
            onManual(tap, at)
        }
        if (reckoning.hasPosition) attachToRoad()
    }

    /** Where to load the next road rectangle, or null while the loaded one still covers the car. */
    fun roadsWanted(): LatLon? {
        if (!reckoning.hasPosition) return null
        val place = reckoning.position()
        val frame = roadFrame ?: return place
        if (graph == null) return place
        val e = frame.toEnu(place)
        val nearEdge = e.x - minX < ROAD_RELOAD_M || maxX - e.x < ROAD_RELOAD_M ||
            e.y - minY < ROAD_RELOAD_M || maxY - e.y < ROAD_RELOAD_M
        return if (nearEdge) place else null
    }

    fun onRadio(position: LatLon, accuracyM: Double, nowMs: Long) {
        val previous = anchor
        val previousMs = anchorMs
        val previousSpeed = lastTeacherSpeed
        val accepted = spoofing.onNetwork(position, accuracyM, nowMs)
        if (!accepted) return
        anchor = position
        anchorMs = nowMs
        anchorAccuracyM = accuracyM
        learnAxis(previous, previousMs, previousSpeed, position, nowMs)
        if (source == GuideSource.GPS) return
        reckoning.setPosition(position)
        source = GuideSource.INERTIAL
        needsMark = false
        heldAtCrossing = false
        bends.reset()
        path.reset()
        attachToRoad()
    }

    /**
     * GPS becomes the source when it is not spoofed and it has agreed with the mark,
     * or followed the car, for long enough. A rejected fix leaves the mark where it is.
     */
    /** While GPS is off, fixes do not move the mark and do not become the source. */
    fun setGpsEnabled(enabled: Boolean) {
        gpsEnabled = enabled
        if (!enabled && source == GuideSource.GPS) {
            source = GuideSource.INERTIAL
            gpsReturn.reset()
        }
    }

    fun offerGps(position: LatLon, accuracyM: Double, timeMs: Long, speedMps: Double): Boolean {
        if (!gpsEnabled) return false
        val trusted = spoofing.checkSatellite(position, timeMs)
        if (reckoning.hasHeading && speedMps > 1.0) turnSign.onGpsBearing(bearingFromMotion(position), timeMs)
        if (!trusted) {
            if (source == GuideSource.GPS) source = GuideSource.INERTIAL
            gpsReturn.reset()
            return false
        }
        learnSpeed(speedMps, timeMs)
        if (source != GuideSource.GPS) {
            val own = if (reckoning.hasPosition) reckoning.position() else null
            val onRoad = lastOnRoad != null && own != null && Geo.haversineM(lastOnRoad!!, own) < 30.0
            val back = gpsReturn.onGps(
                position,
                accuracyM,
                timeMs,
                own,
                if (own == null) 0.0 else reckoning.accuracyM(),
                speedMps,
                accelAxis.currentSpeedMps(),
                onRoad,
            )
            if (!back) return false
        }
        reckoning.setPosition(position)
        gpsAccuracyM = accuracyM
        anchor = position
        source = GuideSource.GPS
        needsMark = false
        heldAtCrossing = false
        speedLost = false
        attachToRoad()
        return true
    }

    fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    fun togglePaused() {
        paused = !paused
    }

    /** Turns the car around. The travelled shape is forgotten, because it now points the other way. */
    fun reverse() {
        if (!reckoning.hasHeading) return
        reckoning.setHeading(wrap360(reckoning.headingDeg + 180.0))
        bends.reset()
        path.reset()
    }

    fun speedTableText(): String = speedTable.serialize()

    fun restoreSpeedTable(text: String) {
        speedTable.restore(text)
    }

    /** Long press. The second mark within a minute sets the heading between the two. */
    fun onManual(tap: LatLon, nowMs: Long = 0L): Boolean {
        val roads = graph
        val frame = roadFrame
        if (roads == null || frame == null) {
            pendingTap = tap
            pendingTapMs = nowMs
            return false
        }
        val hit = roads.candidates(frame.toEnu(tap), MANUAL_RADIUS_M).minByOrNull { it.distanceM } ?: return false
        val point = frame.toLatLon(hit.point)
        val earlier = firstMark
        val earlierMs = firstMarkMs
        reckoning.setPosition(point)
        var heading = compassDeg(hit.bearingRad)
        if (earlier != null && earlierMs != null && nowMs - earlierMs in 0..MARK_PAIR_MS) {
            val delta = frame.toEnu(point) - frame.toEnu(earlier)
            if (delta.norm() >= MARK_PAIR_MIN_M) {
                heading = nearerHeading(wrap360(Math.toDegrees(kotlin.math.atan2(delta.x, delta.y))), heading)
            }
        }
        reckoning.setHeading(heading)
        reckoning.trustRoad()
        anchor = point
        source = GuideSource.MANUAL
        needsMark = false
        heldAtCrossing = false
        roadHeading = heading
        lastOnRoad = point
        firstMark = point
        firstMarkMs = nowMs
        markSequence++
        bends.reset()
        path.reset()
        gpsReturn.reset()
        return true
    }

    /** Route bearing, radians counter-clockwise from east. Used only while the phone has no heading. */
    fun onRouteBearing(bearingRad: Double) {
        if (reckoning.hasHeading) return
        reckoning.setHeading(wrap360(90.0 - Math.toDegrees(bearingRad)))
    }

    fun onImu(sample: ImuSample): GuideFix? {
        if (!reckoning.hasPosition) return null
        accelWindow.addLast(sample.timestampMs to sample.linearAcc)
        while (accelWindow.isNotEmpty() && sample.timestampMs - accelWindow.first().first > ACCEL_MEMORY_MS) {
            accelWindow.removeFirst()
        }
        calibrate(sample)
        val previousMs = lastImuMs
        lastImuMs = sample.timestampMs
        val dt = if (previousMs == null) 0.0 else (sample.timestampMs - previousMs) / 1000.0
        speedLost = !accelAxis.usable || dt > SPEED_STALE_SEC
        val knownBias = bias
        var yawDelta = 0.0
        if (knownBias != null && dt in 0.0..1.0 && reckoning.hasHeading && !paused) {
            yawDelta = GyroCalibrator.yawRateDeg(sample.gyro, knownBias, up) * dt
            if (turnSign.reversed) yawDelta = -yawDelta
            reckoning.rotate(yawDelta)
            turnSign.onGyro(yawDelta)
        }
        if (!needsMark && !paused && dt in 0.0..1.0 && source != GuideSource.GPS) step(sample.linearAcc, dt, yawDelta)
        return fix(sample.timestampMs)
    }

    fun fix(timestampMs: Long): GuideFix? {
        if (!reckoning.hasPosition) return null
        return GuideFix(
            latitude = reckoning.position().lat,
            longitude = reckoning.position().lon,
            accuracyM = if (source == GuideSource.GPS) gpsAccuracyM else reckoning.accuracyM(),
            headingDeg = if (reckoning.hasHeading) reckoning.headingDeg else null,
            source = source,
            needsMark = needsMark,
            timestampMs = timestampMs,
            speedLost = speedLost && source != GuideSource.GPS,
            speedMps = accelAxis.currentSpeedMps(),
            paused = paused,
            markSequence = markSequence,
        )
    }

    private fun step(linearAcc: Vec3, dt: Double, yawDelta: Double) {
        val roads = graph ?: return
        val frame = roadFrame ?: return
        if (!reckoning.hasHeading || speedLost) return
        val raw = accelAxis.distanceM(linearAcc, dt) ?: return
        val distance = raw * speedTable.scale(accelAxis.currentSpeedMps() * 3.6)
        if (distance <= 0.0) return
        val before = reckoning.position()
        val fromHeading = roadHeading ?: reckoning.headingDeg
        if (heldAtCrossing) {
            releaseCrossing(roads, frame, fromHeading)
            return
        }
        val walked = RoadWalker.walk(roads, frame, before, reckoning.headingDeg, distance)
        if (walked == null) {
            lastOnRoad?.let { reckoning.moveTo(it) }
            needsMark = true
            return
        }
        reckoning.moveTo(walked.position)
        reckoning.noteTravel(walked.distanceM)
        reckoning.setHeading(walked.bearingDeg)
        lastOnRoad = walked.position
        source = GuideSource.INERTIAL
        bends.onMotion(yawDelta, walked.distanceM)
        path.onMotion(yawDelta, walked.distanceM, walked.bearingDeg)
        pullAlongShape(roads, frame)
        if (walked.atCrossing) {
            heldAtCrossing = true
            roadHeading = fromHeading
            return
        }
        if (TurnMatcher.isRealTurn(fromHeading, reckoning.headingDeg)) {
            val crossing = TurnMatcher.findCrossing(roads, frame, before, fromHeading, reckoning.headingDeg)
            if (crossing != null) {
                reckoning.moveTo(crossing)
                reckoning.trustRoad()
            }
        }
        roadHeading = reckoning.headingDeg
    }

    private fun pullAlongShape(roads: RoadGraph, frame: LocalFrame) {
        if (!reckoning.hasHeading) return
        val shift = bends.match(roads, frame, reckoning.position(), reckoning.headingDeg, 60.0)
        if (shift != null && abs(shift) >= BendMatcher.STEP_M) {
            val moved = placeAlongRoad(roads, frame, reckoning.position(), -shift)
            if (moved != null) {
                val hit = roads.candidates(frame.toEnu(moved), 25.0).minByOrNull { it.distanceM }
                reckoning.moveTo(moved)
                if (hit != null) reckoning.setHeading(nearerHeading(reckoning.headingDeg, compassDeg(hit.bearingRad)))
                lastOnRoad = moved
                bends.reset()
                return
            }
        }
        val placed = path.match(roads, frame, reckoning.position()) ?: return
        reckoning.moveTo(placed)
        lastOnRoad = placed
        path.reset()
        bends.reset()
    }

    private fun learnSpeed(gpsSpeedMps: Double, timeMs: Long) {
        val car = accelAxis.currentSpeedMps()
        val previousMs = anchorMs
        val dt = if (previousMs == null) 1.0 else (timeMs - previousMs) / 1000.0
        if (car < 1.0 || gpsSpeedMps < 1.0) return
        speedTable.onSample(car * 3.6, gpsSpeedMps, dt.coerceIn(0.1, 2.0), 0.0)
    }

    private fun bearingFromMotion(position: LatLon): Double {
        val origin = if (reckoning.hasPosition) reckoning.position() else position
        val delta = LocalFrame(origin).toEnu(position)
        if (delta.norm() < 5.0 && reckoning.hasHeading) return reckoning.headingDeg
        return wrap360(Math.toDegrees(kotlin.math.atan2(delta.x, delta.y)))
    }

    private fun releaseCrossing(roads: RoadGraph, frame: LocalFrame, arrivalDeg: Double) {
        if (!TurnMatcher.isRealTurn(arrivalDeg, reckoning.headingDeg)) return
        val here = reckoning.position()
        val leaving = roads.candidates(frame.toEnu(here), 15.0)
            .filter { undirectedDiff(compassDeg(it.bearingRad), reckoning.headingDeg) <= TurnMatcher.MAX_ROAD_DIFF_DEG }
            .minByOrNull { undirectedDiff(compassDeg(it.bearingRad), reckoning.headingDeg) }
            ?: return
        reckoning.setHeading(nearerHeading(reckoning.headingDeg, compassDeg(leaving.bearingRad)))
        reckoning.trustRoad()
        heldAtCrossing = false
        roadHeading = reckoning.headingDeg
    }

    private fun attachToRoad() {
        val roads = graph ?: return
        val frame = roadFrame ?: return
        if (!reckoning.hasPosition) return
        val here = frame.toEnu(reckoning.position())
        val hit = roads.candidates(here, DeadReckoning.MAX_ASIDE_ON_ROAD_M).minByOrNull { it.distanceM }
        if (hit == null) {
            needsMark = true
            return
        }
        val point = frame.toLatLon(hit.point)
        if (!reckoning.hasHeading) {
            reckoning.moveTo(point)
            needsMark = false
            return
        }
        val ok = reckoning.snapToRoad(point, compassDeg(hit.bearingRad))
        needsMark = !ok
        if (ok) {
            roadHeading = reckoning.headingDeg
            lastOnRoad = reckoning.position()
        } else {
            lastOnRoad?.let { reckoning.moveTo(it) }
        }
    }

    private fun learnAxis(
        previous: LatLon?,
        previousMs: Long?,
        previousSpeed: Double?,
        position: LatLon,
        nowMs: Long,
    ) {
        if (previous == null || previousMs == null) return
        val dt = (nowMs - previousMs) / 1000.0
        val speed = teacherSpeedMps(alongRoad(previous, position), dt) ?: return
        if (previousSpeed != null) {
            val carAccel = teacherAccel(previousSpeed, speed, dt)
            val mean = meanAccel(previousMs, nowMs)
            if (carAccel != null && mean != null) accelAxis.onComparison(mean, carAccel)
        }
        lastTeacherSpeed = speed
        accelAxis.resetSpeed(speed)
        reckoning.setSpeed(speed)
    }

    private fun alongRoad(a: LatLon, b: LatLon): Double {
        val roads = graph
        val frame = roadFrame
        if (roads == null || frame == null) return Geo.haversineM(a, b)
        val ha = roads.candidates(frame.toEnu(a), DeadReckoning.MAX_ASIDE_ON_ROAD_M).minByOrNull { it.distanceM }
        val hb = roads.candidates(frame.toEnu(b), DeadReckoning.MAX_ASIDE_ON_ROAD_M).minByOrNull { it.distanceM }
        if (ha == null || hb == null) return Geo.haversineM(a, b)
        if (ha.wayIndex == hb.wayIndex) return abs(hb.arcM - ha.arcM)
        return Geo.haversineM(frame.toLatLon(ha.point), frame.toLatLon(hb.point))
    }

    private fun meanAccel(fromMs: Long, toMs: Long): Vec3? {
        val window = accelWindow.filter { it.first in fromMs..toMs }
        if (window.isEmpty()) return null
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for ((_, a) in window) {
            x += a.x
            y += a.y
            z += a.z
        }
        val n = window.size.toDouble()
        return Vec3(x / n, y / n, z / n)
    }

    private fun calibrate(sample: ImuSample) {
        val still = sample.gyro.norm() <= MOVING_GYRO
        if (!stillGate.ready(still, sample.timestampMs)) {
            calibrator.reset()
            return
        }
        calibrator.add(sample.gyro, sample.gravity)
        if (!calibrator.isComplete()) return
        if (calibrator.isStill()) {
            val next = calibrator.bias()
            val axis = calibrator.up()
            val stopped = StillGate.DELAY_MS
            if (GyroCalibrator.biasChangeAllowed(bias, next, axis, stopped)) {
                val waiting = pendingBias
                if (waiting == null) {
                    pendingBias = next
                    up = axis
                } else {
                    val confirmed = GyroCalibrator.confirm(waiting, next, axis)
                    if (confirmed != null && GyroCalibrator.biasChangeAllowed(bias, confirmed, axis, stopped)) {
                        bias = confirmed
                        up = axis
                        pendingBias = null
                    } else {
                        pendingBias = next
                    }
                }
            }
        }
        calibrator.reset()
    }

    private companion object {
        const val ROAD_RELOAD_M = 400.0
        const val MANUAL_RADIUS_M = 80.0
        const val ACCEL_MEMORY_MS = 30_000L
        const val MOVING_GYRO = 0.2
        const val SPEED_STALE_SEC = 2.0
        const val MARK_PAIR_MS = 60_000L
        const val MARK_PAIR_MIN_M = 15.0
    }
}
