package com.skynav.domain.inertial

import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.positioning.RoadGraph
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * How far the mark is ahead of the car along the road, from the shape of the last bend.
 * Positive means the mark is ahead. Nothing on a straight road.
 * Thresholds match NoNGPS `BendMatcher`.
 */
class BendMatcher {
    private val history = ArrayDeque<Sample>()
    private var odometer = 0.0
    private var yaw = 0.0

    fun reset() {
        history.clear()
        odometer = 0.0
        yaw = 0.0
    }

    fun onMotion(yawDeltaDeg: Double, distanceM: Double) {
        if (distanceM <= 0.0) return
        if (history.isEmpty()) history.addLast(Sample(odometer, yaw))
        odometer += distanceM
        yaw += yawDeltaDeg
        history.addLast(Sample(odometer, yaw))
        while (history.size > 2 && odometer - history.first().odometer > WINDOW_M + 20.0) history.removeFirst()
    }

    fun rotationDeg(distanceM: Double): Double? {
        if (history.isEmpty() || distanceM <= 0.0) return null
        val from = odometer - distanceM.coerceAtMost(WINDOW_M)
        if (from < history.first().odometer - 1.0) return null
        return yaw - yawAt(from)
    }

    /**
     * Returns how far [position] is ahead of the car. Negative when the mark is behind
     * and should be moved forward along the road.
     */
    fun match(
        graph: RoadGraph,
        frame: LocalFrame,
        position: LatLon,
        bearingDeg: Double,
        maxShiftM: Double,
    ): Double? {
        val window = minOf(WINDOW_M, odometer)
        val gyro = rotationDeg(window) ?: return null
        if (abs(gyro) < MIN_BEND_DEG) return null
        val origin = graph.candidates(frame.toEnu(position), 25.0).minByOrNull { it.distanceM } ?: return null
        val way = graph.ways[origin.wayIndex]
        val total = way.cumLen.last()
        val withWay = abs(angleDiff(bearingDeg, forwardCompass(way, origin.arcM))) <= 90.0
        val driven = if (withWay) 1.0 else -1.0
        var bestAhead = 0.0
        var bestError = Double.MAX_VALUE
        val errors = ArrayList<Pair<Double, Double>>()
        var ahead = -maxShiftM
        while (ahead <= maxShiftM + 0.1) {
            val arc = origin.arcM + ahead
            val start = arc - window * driven
            if (arc in 0.0..total && start in 0.0..total) {
                val road = angleDiff(forwardCompass(way, start), forwardCompass(way, arc))
                val error = abs(angleDiff(gyro, road))
                errors.add(ahead to error)
                if (error < bestError) {
                    bestError = error
                    bestAhead = ahead
                }
            }
            ahead += STEP_M
        }
        if (bestError > MAX_DIFF_DEG) return null
        val limit = maxOf(bestError * CLEAR_RATIO, bestError + MIN_ASIDE_DIFF_DEG)
        val muddy = errors.any { (shift, error) -> abs(shift - bestAhead) >= CLEAR_ASIDE_M && error < limit }
        if (muddy) return null
        return -bestAhead
    }

    private fun yawAt(odometerM: Double): Double {
        var previous = history.first()
        for (sample in history) {
            if (sample.odometer >= odometerM) {
                val span = sample.odometer - previous.odometer
                if (span <= 0.0) return sample.yaw
                val t = ((odometerM - previous.odometer) / span).coerceIn(0.0, 1.0)
                return previous.yaw + (sample.yaw - previous.yaw) * t
            }
            previous = sample
        }
        return yaw
    }

    private data class Sample(val odometer: Double, val yaw: Double)

    companion object {
        const val WINDOW_M = 150.0
        const val STEP_M = 5.0
        const val MIN_BEND_DEG = 20.0
        const val MAX_DIFF_DEG = 4.0
        const val CLEAR_ASIDE_M = 15.0
        const val CLEAR_RATIO = 2.0
        const val MIN_ASIDE_DIFF_DEG = 1.0
    }
}

/**
 * Lays the driven shape onto the roads around the mark and moves the mark when
 * the whole shape lies on one other road. Thresholds match NoNGPS `PathMatcher`.
 */
class PathMatcher {
    private val history = ArrayDeque<Sample>()
    private var odometer = 0.0

    fun reset() {
        history.clear()
        odometer = 0.0
    }

    fun onMotion(yawDeltaDeg: Double, distanceM: Double, headingDeg: Double) {
        if (distanceM < 0.0) return
        if (history.isEmpty()) history.addLast(Sample(0.0, 0.0, headingDeg))
        odometer += distanceM
        history.addLast(Sample(odometer, yawDeltaDeg, headingDeg))
        while (history.size > 2 && odometer - history.first().odometer > WINDOW_M + 20.0) history.removeFirst()
    }

    fun match(graph: RoadGraph, frame: LocalFrame, position: LatLon): LatLon? {
        val way = way() ?: return null
        val here = misses(graph, frame, position, way)
        var best = position
        var bestMisses = here
        val places = ArrayList<LatLon>()
        places.add(position)
        for (hit in graph.candidates(frame.toEnu(position), SEARCH_M)) {
            val point = frame.toLatLon(hit.point)
            for (along in -SEARCH_ALONG_M.toInt()..SEARCH_ALONG_M.toInt() step SEARCH_STEP_M) {
                places.add(shift(frame, point, compassDeg(hit.bearingRad), along.toDouble()))
            }
        }
        for (place in places) {
            val count = misses(graph, frame, place, way)
            val better = count < bestMisses || (count == bestMisses && GeoDistance.between(position, place) < GeoDistance.between(position, best))
            if (better) {
                best = place
                bestMisses = count
            }
        }
        if (here - bestMisses < MIN_MISSES_TO_MOVE) return null
        if (GeoDistance.between(position, best) < MIN_MOVE_M) return null
        val rival = places.any { place ->
            misses(graph, frame, place, way) <= bestMisses &&
                GeoDistance.between(place, best) > SEARCH_ALONG_M + SAME_PLACE_M
        }
        if (rival) return null
        return best
    }

    private fun way(): List<Step>? {
        if (history.isEmpty() || odometer - history.first().odometer < MIN_WINDOW_M) return null
        val points = ArrayList<Step>()
        var east = 0.0
        var north = 0.0
        var cursor = odometer
        points.add(Step(Enu(0.0, 0.0), headingAt(odometer)))
        while (odometer - cursor < WINDOW_M && cursor - CHECK_STEP_M >= history.first().odometer) {
            val heading = headingAt(cursor)
            val rad = Math.toRadians(heading)
            east -= CHECK_STEP_M * sin(rad)
            north -= CHECK_STEP_M * cos(rad)
            cursor -= CHECK_STEP_M
            points.add(Step(Enu(east, north), headingAt(cursor)))
        }
        return points
    }

    private fun misses(graph: RoadGraph, frame: LocalFrame, end: LatLon, way: List<Step>): Int {
        val local = LocalFrame(end)
        var missed = 0
        for (step in way) {
            val world = frame.toEnu(local.toLatLon(step.offset))
            val onRoad = graph.candidates(world, MAX_ROAD_M).any {
                undirectedDiff(compassDeg(it.bearingRad), step.headingDeg) <= MAX_ROAD_DIFF_DEG
            }
            if (!onRoad) missed++
        }
        return missed
    }

    private fun headingAt(odometerM: Double): Double {
        var heading = history.first().headingDeg
        for (sample in history) {
            heading = sample.headingDeg
            if (sample.odometer >= odometerM) return heading
        }
        return heading
    }

    private data class Sample(val odometer: Double, val yawDeltaDeg: Double, val headingDeg: Double)
    private data class Step(val offset: Enu, val headingDeg: Double)

    companion object {
        const val WINDOW_M = 150.0
        const val MIN_WINDOW_M = 100.0
        const val CHECK_STEP_M = 10
        const val MAX_ROAD_M = 8.0
        const val MAX_ROAD_DIFF_DEG = 20.0
        const val MIN_MISSES_TO_MOVE = 4
        const val SEARCH_M = 60.0
        const val SEARCH_ALONG_M = 30
        const val SEARCH_STEP_M = 10
        const val SAME_PLACE_M = 15.0
        const val MIN_MOVE_M = 20.0
    }
}

private object GeoDistance {
    fun between(a: LatLon, b: LatLon): Double = com.skynav.domain.geo.Geo.haversineM(a, b)
}

private fun shift(frame: LocalFrame, position: LatLon, bearingDeg: Double, meters: Double): LatLon {
    val moved = frame.toEnu(position) + stepEnu(bearingDeg, meters)
    return frame.toLatLon(moved)
}

/** Moves [alongM] metres along the road through [position]. Positive follows the way's point order. */
internal fun placeAlongRoad(graph: RoadGraph, frame: LocalFrame, position: LatLon, alongM: Double): LatLon? {
    val hit = graph.candidates(frame.toEnu(position), 25.0).minByOrNull { it.distanceM } ?: return null
    val way = graph.ways[hit.wayIndex]
    val total = way.cumLen.last()
    val arc = (hit.arcM + alongM).coerceIn(0.0, total)
    if (abs(arc - hit.arcM) < 1.0) return null
    return frame.toLatLon(pointOn(way, arc))
}

private fun pointOn(way: RoadGraph.Way, arc: Double): Enu {
    val index = edgeIndex(way, arc)
    val span = (way.cumLen[index + 1] - way.cumLen[index]).coerceAtLeast(1e-6)
    val t = ((arc - way.cumLen[index]) / span).coerceIn(0.0, 1.0)
    val a = way.points[index]
    val b = way.points[index + 1]
    return Enu(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
}

private fun forwardCompass(way: RoadGraph.Way, arc: Double): Double {
    val index = edgeIndex(way, arc)
    val a = way.points[index]
    val b = way.points[index + 1]
    return compassDeg(atan2(b.y - a.y, b.x - a.x))
}

private fun edgeIndex(way: RoadGraph.Way, arc: Double): Int {
    var index = 0
    while (index < way.cumLen.size - 2 && way.cumLen[index + 1] < arc - 0.05) index++
    return index
}
