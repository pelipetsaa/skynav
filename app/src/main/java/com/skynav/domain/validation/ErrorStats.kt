package com.skynav.domain.validation

import com.skynav.domain.geo.Geo
import com.skynav.domain.geo.LatLon
import com.skynav.domain.replay.GpsTruthEvent
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Errors against GPS ground truth.
 * - [availability]: fraction of truth points that had an estimate within the time gap.
 * - Percentiles are computed over matched points only.
 */
data class ErrorStats(
    val truthCount: Int,
    val availability: Double,
    val medianM: Double,
    val p95M: Double,
    val maxM: Double,
)

object ErrorEvaluator {

    /**
     * @param estimates time-stamped estimates, any order
     * @param truth GPS ground truth points; points worse than [maxTruthAccuracyM] are ignored
     * @param maxMatchGapMs maximum time distance between truth point and estimate
     */
    fun evaluate(
        estimates: List<Pair<Long, LatLon>>,
        truth: List<GpsTruthEvent>,
        maxMatchGapMs: Long = 2_000,
        maxTruthAccuracyM: Double = 20.0,
    ): ErrorStats {
        val sorted = estimates.sortedBy { it.first }
        val times = LongArray(sorted.size) { sorted[it].first }
        val usable = truth.filter { it.accuracyM <= maxTruthAccuracyM }

        val errors = ArrayList<Double>()
        for (g in usable) {
            val idx = nearestIndex(times, g.timestampMs)
            if (idx < 0 || abs(times[idx] - g.timestampMs) > maxMatchGapMs) continue
            errors.add(Geo.haversineM(sorted[idx].second, LatLon(g.latitude, g.longitude)))
        }

        val availability = if (usable.isEmpty()) 0.0 else errors.size.toDouble() / usable.size
        if (errors.isEmpty()) {
            return ErrorStats(usable.size, availability, Double.NaN, Double.NaN, Double.NaN)
        }
        val s = errors.sorted()
        return ErrorStats(
            truthCount = usable.size,
            availability = availability,
            medianM = percentile(s, 0.5),
            p95M = percentile(s, 0.95),
            maxM = s.last(),
        )
    }

    /** Nearest-rank percentile on a sorted list. */
    fun percentile(sorted: List<Double>, p: Double): Double {
        val idx = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun nearestIndex(times: LongArray, t: Long): Int {
        if (times.isEmpty()) return -1
        var lo = 0
        var hi = times.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] < t) lo = mid + 1 else hi = mid
        }
        // lo is the first index with times >= t; the nearest is lo or lo-1.
        if (lo > 0 && abs(times[lo - 1] - t) <= abs(times[lo] - t)) return lo - 1
        return lo
    }
}
