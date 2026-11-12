package com.skynav.domain.replay

import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.model.BaseStation
import com.skynav.domain.model.CellKey
import com.skynav.domain.model.CellMeasurement
import com.skynav.domain.model.Vec3
import com.skynav.domain.positioning.MapMatcher
import com.skynav.domain.positioning.MatchInput
import com.skynav.domain.positioning.NavigationEngine
import com.skynav.domain.positioning.NavigationSnapshot
import com.skynav.domain.positioning.RoadGraph
import com.skynav.domain.positioning.WifiObservation
import kotlin.math.abs

/**
 * Feeds a recorded drive through [NavigationEngine] in timestamp order.
 * Same code path as the live service, so offline tuning reflects what runs on the phone.
 */
private const val WIFI_MATCH_MS = 10_000L

class DriveReplay(
    private val frame: LocalFrame,
    private val stationLookup: (CellKey) -> BaseStation?,
    private val forwardAxis: Vec3 = Vec3(0.0, 1.0, 0.0),
) {
    fun run(events: List<DriveEvent>): List<NavigationSnapshot> {
        val engine = NavigationEngine(frame, stationLookup, forwardAxis)
        val out = ArrayList<NavigationSnapshot>()
        var i = 0
        while (i < events.size) {
            when (val e = events[i]) {
                is ImuEvent -> {
                    engine.onImu(e.sample)
                    engine.current()?.let { out.add(it) }
                    i++
                }
                is ObdEvent -> {
                    engine.onObdSpeed(e.timestampMs, e.speedMps)
                    i++
                }
                is CellEvent -> {
                    // All CELL lines with the same timestamp form one scan.
                    val t = e.timestampMs
                    val batch = ArrayList<CellMeasurement>()
                    while (i < events.size) {
                        val c = events[i]
                        if (c is CellEvent && c.timestampMs == t) {
                            batch.add(c.cell)
                            i++
                        } else {
                            break
                        }
                    }
                    val wifi = wifiForScan(events, t)
                    engine.onCells(t, batch, wifi)?.let { out.add(it) }
                }
                is WifiEvent -> i++
                is GpsTruthEvent -> i++
            }
        }
        return out
    }

    /**
     * Catalogued access points within 10 s of the cell scan, including lines logged just after it.
     * One row per BSSID.
     */
    private fun wifiForScan(events: List<DriveEvent>, scanMs: Long): List<WifiObservation> {
        val due = events.filterIsInstance<WifiEvent>().filter {
            abs(it.timestampMs - scanMs) <= WIFI_MATCH_MS && it.latitude != null && it.longitude != null
        }
        return due.groupBy { it.bssid }.map { (_, rows) ->
            val row = rows.last()
            WifiObservation(
                latitude = row.latitude!!,
                longitude = row.longitude!!,
                rssiDbm = row.rssiDbm,
                accuracyM = row.accuracyM ?: WifiObservation.CROWDSOURCED_ACCURACY_M,
            )
        }
    }

    /**
     * Map-matches snapshots downsampled to [hz]. Points without a nearby road keep the raw estimate.
     * Ambiguous matches are not filtered here; use [MapMatcher] directly to inspect the flag.
     */
    fun mapMatch(
        snapshots: List<NavigationSnapshot>,
        graph: RoadGraph,
        hz: Double = 1.0,
    ): List<Pair<Long, LatLon>> {
        val sampled = ArrayList<NavigationSnapshot>()
        val periodMs = 1000.0 / hz
        for (s in snapshots) {
            if (sampled.isEmpty() || s.timestampMs - sampled.last().timestampMs >= periodMs) sampled.add(s)
        }
        val inputs = sampled.map { s ->
            MatchInput(
                timestampMs = s.timestampMs,
                position = frame.toEnu(LatLon(s.position.latitude, s.position.longitude)),
                sigmaM = s.position.accuracyM,
                headingRad = s.headingRad,
                speedMps = s.speedMps,
            )
        }
        val matched = MapMatcher(graph).match(inputs)
        return matched.mapIndexed { idx, m ->
            val ll = if (m.wayId != null) frame.toLatLon(m.position) else LatLon(
                sampled[idx].position.latitude,
                sampled[idx].position.longitude,
            )
            m.timestampMs to ll
        }
    }
}
