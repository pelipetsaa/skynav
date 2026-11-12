package com.skynav.domain.replay

import com.skynav.domain.basestation.BaseStationCsv
import com.skynav.domain.geo.Geo
import com.skynav.domain.geo.LatLon
import com.skynav.domain.geo.LocalFrame
import com.skynav.domain.validation.ErrorEvaluator
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Offline report on a real phone drive. Skipped unless the log exists, so CI stays green.
 * Replays the log through the same [DriveReplay] path as the service and compares with GPS.
 */
class DriveReportTest {

    @Test
    fun replayPhoneDriveAgainstGps() {
        val logFile = File("/tmp/drive-now.csv")
        assumeTrue(logFile.exists())
        val stations = BaseStationCsv.parse(File("src/main/assets/stations.csv").readText()).stations
        val byKey = stations.associateBy { it.key }

        val events = DriveLog.parse(logFile.useLines { it.toList().asSequence() })
        val cellEvents = events.filterIsInstance<CellEvent>()
        val servingKey = cellEvents.map { it.cell }.firstOrNull { it.registered && it.key != null }?.key
        val origin = byKey[servingKey] ?: error("serving cell has no station: $servingKey")
        val frame = LocalFrame(LatLon(origin.latitude, origin.longitude))

        val snapshots = DriveReplay(frame, { byKey[it] }).run(events)
        val gps = events.filterIsInstance<GpsTruthEvent>()
        val estimates = snapshots.map { it.timestampMs to LatLon(it.position.latitude, it.position.longitude) }

        val towerErrors = gps.map { Geo.haversineM(LatLon(origin.latitude, origin.longitude), LatLon(it.latitude, it.longitude)) }
        println("REPORT cells=${cellEvents.size} gps=${gps.size} snapshots=${snapshots.size}")
        println("REPORT gps_accuracy_min=${gps.minOf { it.accuracyM }} max=${gps.maxOf { it.accuracyM }}")
        println("REPORT tower_point_to_gps_median_m=${ErrorEvaluator.percentile(towerErrors.sorted(), 0.5)}")
        for (maxAcc in listOf(20.0, 50.0)) {
            val stats = ErrorEvaluator.evaluate(estimates, gps, maxTruthAccuracyM = maxAcc)
            println("REPORT maxTruthAcc=$maxAcc truth=${stats.truthCount} avail=${stats.availability} median=${stats.medianM} p95=${stats.p95M} max=${stats.maxM}")
        }
        val last = snapshots.lastOrNull()
        if (last != null) {
            println("REPORT last lat=${last.position.latitude} lon=${last.position.longitude} acc=${last.position.accuracyM}")
        }
    }
}
