package com.skynav.domain.inertial

import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.positioning.RoadGraph
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class WalkResult(
    val position: LatLon,
    val bearingDeg: Double,
    val distanceM: Double,
    val atCrossing: Boolean,
)

/**
 * Steps of 5 m along the nearest road. Stops at the first crossing when the way on is ambiguous.
 * Thresholds match NoNGPS `RoadWalker`.
 */
object RoadWalker {
    const val STEP_M = 5.0
    const val SNAP_RADIUS_M = 8.0
    const val MAX_BEND_DEG = 45.0
    const val SAME_CROSSING_M = 1.0
    const val CROSSING_ON_ROAD_M = 3.0

    fun walk(
        graph: RoadGraph,
        frame: LocalFrame,
        position: LatLon,
        bearingDeg: Double,
        distanceM: Double,
        stopAtCrossing: Boolean = true,
    ): WalkResult? {
        if (distanceM == 0.0) return null
        val sign = if (distanceM > 0) 1.0 else -1.0
        val crossings = if (stopAtCrossing) junctions(graph, frame) else emptyList()
        val start = frame.toEnu(position)
        var enu = start
        var bearing = bearingDeg
        var applied = 0.0
        val target = abs(distanceM)
        while (applied + 0.01 < target) {
            val step = minOf(STEP_M, target - applied) * sign
            val hit = nearestRoad(graph, frame, enu, bearing, SNAP_RADIUS_M, MAX_BEND_DEG) ?: break
            val roadBearing = nearerHeading(bearing, compassDeg(hit.bearingRad))
            val next = enu + stepEnu(roadBearing, step)
            if (stopAtCrossing && applied > SAME_CROSSING_M) {
                val junction = crossings.firstOrNull { (frame.toEnu(it) - next).norm() <= CROSSING_ON_ROAD_M }
                if (junction != null && (frame.toEnu(junction) - start).norm() > SAME_CROSSING_M) {
                    return WalkResult(junction, roadBearing, applied + step, true)
                }
            }
            enu = next
            bearing = roadBearing
            applied += abs(step)
        }
        if (applied == 0.0) return null
        return WalkResult(frame.toLatLon(enu), bearing, applied, false)
    }
}

data class RoadHit(
    val point: LatLon,
    val bearingDeg: Double,
    val distanceM: Double,
)

fun nearestRoad(
    graph: RoadGraph,
    frame: LocalFrame,
    enu: Enu,
    headingDeg: Double,
    radiusM: Double,
    maxDiffDeg: Double,
): com.skynav.domain.positioning.Candidate? {
    val hits = graph.candidates(enu, radiusM)
    return hits
        .filter { undirectedDiff(headingDeg, compassDeg(it.bearingRad)) <= maxDiffDeg }
        .minByOrNull { it.distanceM }
}

/** Compass degrees clockwise from north, from an east-north bearing in radians. */
fun compassDeg(enuBearingRad: Double): Double = wrap360(90.0 - Math.toDegrees(enuBearingRad))

fun stepEnu(bearingDeg: Double, distanceM: Double): Enu {
    val rad = Math.toRadians(bearingDeg)
    return Enu(distanceM * sin(rad), distanceM * cos(rad))
}

/** Vertices shared by two ways. Those are the crossings a turn can land on. */
fun junctions(graph: RoadGraph, frame: LocalFrame): List<LatLon> {
    val buckets = HashMap<Long, MutableList<Pair<Int, Enu>>>()
    graph.ways.forEachIndexed { wi, way ->
        for (p in way.points) {
            val key = ((p.x / 2.0).toInt().toLong() shl 32) xor (p.y / 2.0).toInt().toLong()
            buckets.getOrPut(key) { mutableListOf() }.add(wi to p)
        }
    }
    val out = ArrayList<LatLon>()
    for (group in buckets.values) {
        val ways = group.map { it.first }.toSet()
        if (ways.size < 2) continue
        val sum = group.fold(Enu(0.0, 0.0)) { a, b -> a + b.second }
        out.add(frame.toLatLon(sum * (1.0 / group.size)))
    }
    return out
}

/**
 * A turn smaller than 35° is a bend of the road the car is already on.
 * A real turn is moved to a crossing 25–60 m ahead whose leaving road matches the new heading.
 */
object TurnMatcher {
    const val MIN_TURN_DEG = 35.0
    const val MAX_TURN_DEG = 150.0
    const val MIN_SEARCH_M = 25.0
    const val MAX_SEARCH_M = 60.0
    const val MAX_SIDE_M = 20.0
    const val MAX_ROAD_DIFF_DEG = 30.0
    const val BEND_END_DIFF_DEG = 25.0

    fun isRealTurn(fromDeg: Double, toDeg: Double): Boolean {
        val turn = abs(angleDiff(fromDeg, toDeg))
        return turn >= MIN_TURN_DEG && turn <= MAX_TURN_DEG
    }

    /** The road under the car already points at the new heading, so this is a bend. */
    fun isBend(graph: RoadGraph, frame: LocalFrame, corner: LatLon, fromDeg: Double, toDeg: Double): Boolean {
        val hit = graph.candidates(frame.toEnu(corner), MAX_SIDE_M).minByOrNull { it.distanceM } ?: return false
        val road = compassDeg(hit.bearingRad)
        return undirectedDiff(road, toDeg) <= BEND_END_DIFF_DEG && undirectedDiff(road, fromDeg) <= MAX_ROAD_DIFF_DEG
    }

    fun findCrossing(
        graph: RoadGraph,
        frame: LocalFrame,
        corner: LatLon,
        fromDeg: Double,
        toDeg: Double,
    ): LatLon? {
        if (!isRealTurn(fromDeg, toDeg)) return null
        if (isBend(graph, frame, corner, fromDeg, toDeg)) return null
        val origin = frame.toEnu(corner)
        var best: LatLon? = null
        var bestAlong = Double.MAX_VALUE
        for (junction in junctions(graph, frame)) {
            val delta = frame.toEnu(junction) - origin
            val along = delta.x * sin(Math.toRadians(fromDeg)) + delta.y * cos(Math.toRadians(fromDeg))
            val aside = abs(-delta.x * cos(Math.toRadians(fromDeg)) + delta.y * sin(Math.toRadians(fromDeg)))
            if (along < MIN_SEARCH_M || along > MAX_SEARCH_M || aside > MAX_SIDE_M) continue
            val leaving = graph.candidates(frame.toEnu(junction), 15.0)
                .any { undirectedDiff(compassDeg(it.bearingRad), toDeg) <= MAX_ROAD_DIFF_DEG }
            if (!leaving) continue
            if (along < bestAlong) {
                bestAlong = along
                best = junction
            }
        }
        return best
    }
}
