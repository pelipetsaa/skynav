package com.skynav.domain.model

import kotlin.math.sqrt

enum class RadioTechnology { LTE, NR }

/** Globally identifying cell key. Cell ID alone is not unique across operators. */
data class CellKey(
    val mcc: Int,
    val mnc: Int,
    val technology: RadioTechnology,
    val cellId: Long,
)

/** One row of `CellInfo` data as seen by the phone. Null means "not reported". */
data class CellMeasurement(
    val timestampMs: Long,
    val key: CellKey?,
    val tac: Int?,
    val pci: Int?,
    val rsrpDbm: Int?,
    val rsrqDb: Int?,
    val sinrDb: Int?,
    val timingAdvance: Int?,
    val registered: Boolean,
)

/**
 * Known base station sector. Coordinates are the tower position, not the phone position.
 * [verified] is false when accuracy is unknown or the source is crowdsourced.
 */
data class BaseStation(
    val key: CellKey,
    val latitude: Double,
    val longitude: Double,
    val azimuthDeg: Double? = null,
    val sectorWidthDeg: Double? = null,
    val rangeM: Double? = null,
    val accuracyM: Double = DEFAULT_ACCURACY_M,
    val verified: Boolean = false,
) {
    companion object {
        const val DEFAULT_ACCURACY_M = 500.0
    }
}

enum class PositionSource { CELLULAR, FUSED, MAP_MATCHED }

data class Position(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double,
    val timestampMs: Long,
    val source: PositionSource,
)

data class Vec3(val x: Double, val y: Double, val z: Double) {
    fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z
    fun norm(): Double = sqrt(dot(this))
}

/** Synchronized IMU sample. Gravity is taken from TYPE_GRAVITY, linear acceleration from TYPE_LINEAR_ACCELERATION. */
data class ImuSample(
    val timestampMs: Long,
    val linearAcc: Vec3,
    val gyro: Vec3,
    val gravity: Vec3,
)
