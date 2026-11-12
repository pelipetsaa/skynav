package com.skynav.domain.routing

import com.skynav.domain.geo.Enu
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import kotlin.math.atan2

/** One OSRM step. [maneuver] is the raw type: depart, turn, arrive, and the other OSRM names. */
data class RouteStep(
    val maneuver: String,
    val instruction: String,
    val roadName: String,
    val distanceM: Double,
    val location: LatLon,
)

data class Route(
    val points: List<LatLon>,
    val steps: List<RouteStep>,
    val distanceM: Double,
    val durationS: Double,
)

/** Where the estimate sits relative to [Route]. [bearingRad] is counter-clockwise from East, like the filter. */
data class RouteProgress(
    val stepIndex: Int,
    val distanceToManeuverM: Double,
    val crossTrackM: Double,
    val alongM: Double,
    val bearingRad: Double,
    /** True when the route lies outside the reported accuracy circle. */
    val offRoute: Boolean,
)

/**
 * Projects a position onto the route polyline and picks the current step from along-track distance.
 * Step distances come from OSRM and are walked independently of the drawn geometry.
 */
class RouteFollower(route: Route) {

    private val points = route.points
    private val steps = route.steps
    private val frame = LocalFrame(route.points.first())
    private val vertices = route.points.map { frame.toEnu(it) }

    fun progress(position: LatLon, accuracyM: Double): RouteProgress {
        val p = frame.toEnu(position)
        var bestCross = Double.POSITIVE_INFINITY
        var bestAlong = 0.0
        var bestBearing = 0.0
        var covered = 0.0
        for (i in 0 until vertices.size - 1) {
            val a = vertices[i]
            val b = vertices[i + 1]
            val seg = b - a
            val len = seg.norm()
            if (len < 1e-6) continue
            val t = (((p.x - a.x) * seg.x + (p.y - a.y) * seg.y) / (len * len)).coerceIn(0.0, 1.0)
            val q = Enu(a.x + seg.x * t, a.y + seg.y * t)
            val cross = (p - q).norm()
            if (cross < bestCross) {
                bestCross = cross
                bestAlong = covered + t * len
                bestBearing = atan2(seg.y, seg.x)
            }
            covered += len
        }
        if (bestCross.isInfinite()) {
            return RouteProgress(0, 0.0, 0.0, 0.0, 0.0, offRoute = true)
        }

        var cursor = 0.0
        var index = steps.lastIndex.coerceAtLeast(0)
        var toManeuver = 0.0
        for (i in steps.indices) {
            val end = cursor + steps[i].distanceM
            if (bestAlong <= end || i == steps.lastIndex) {
                index = i
                toManeuver = (end - bestAlong).coerceAtLeast(0.0)
                break
            }
            cursor = end
        }
        return RouteProgress(
            stepIndex = index,
            distanceToManeuverM = toManeuver,
            crossTrackM = bestCross,
            alongM = bestAlong,
            bearingRad = bestBearing,
            offRoute = bestCross > accuracyM,
        )
    }
}

/** Speed along the route between two radio fixes. Negative progress is reported as stopped. */
fun alongTrackSpeedMps(earlierAlongM: Double, laterAlongM: Double, dtSec: Double): Double? {
    if (dtSec < 1.0) return null
    return ((laterAlongM - earlierAlongM) / dtSec).coerceAtLeast(0.0)
}

/** Banner text. A turn is withheld while the route is outside the accuracy circle. */
fun guidanceText(route: Route, progress: RouteProgress): String {
    if (route.steps.isEmpty()) return ""
    if (progress.offRoute) return "Позиция вне маршрута"
    val current = route.steps[progress.stepIndex.coerceIn(route.steps.indices)]
    if (current.maneuver == "arrive") return "Вы прибыли"
    val upcoming = route.steps.getOrNull(progress.stepIndex + 1) ?: current
    if (upcoming.maneuver == "arrive" && progress.distanceToManeuverM < 40.0) return "Вы прибыли"
    val meters = progress.distanceToManeuverM.toInt()
    val phrase = upcoming.instruction.replaceFirstChar { it.lowercase() }
    return "Через $meters м $phrase"
}
