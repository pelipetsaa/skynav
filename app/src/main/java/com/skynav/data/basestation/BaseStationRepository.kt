package com.skynav.data.basestation

import com.skynav.domain.basestation.BaseStationCsv
import com.skynav.domain.model.BaseStation
import com.skynav.domain.model.CellKey
import com.skynav.domain.model.RadioTechnology

/** In-memory index used by the synchronous navigation pipeline. */
class StationIndex(private val byKey: Map<CellKey, BaseStation>) {
    operator fun get(key: CellKey): BaseStation? = byKey[key]
    val size: Int get() = byKey.size

    /** Fraction of [keys] that resolve to a station. Stage 3 exit criterion. */
    fun coverage(keys: Collection<CellKey>): Double {
        if (keys.isEmpty()) return 0.0
        return keys.count { it in byKey }.toDouble() / keys.size
    }
}

class BaseStationRepository(private val dao: BaseStationDao) {

    /** Imports a CSV. Returns parse results; rejected lines are not stored. */
    suspend fun importCsv(text: String): BaseStationCsv.Result {
        val result = BaseStationCsv.parse(text)
        dao.upsertAll(result.stations.map { it.toEntity() })
        return result
    }

    suspend fun loadIndex(): StationIndex =
        StationIndex(dao.all().associate { e -> e.toDomainKey() to e.toDomain() })

    suspend fun count(): Int = dao.count()

    private fun BaseStation.toEntity() = BaseStationEntity(
        mcc = key.mcc,
        mnc = key.mnc,
        technology = key.technology.name,
        cellId = key.cellId,
        latitude = latitude,
        longitude = longitude,
        azimuthDeg = azimuthDeg,
        sectorWidthDeg = sectorWidthDeg,
        rangeM = rangeM,
        accuracyM = accuracyM,
        verified = verified,
    )

    private fun BaseStationEntity.toDomainKey() =
        CellKey(mcc, mnc, RadioTechnology.valueOf(technology), cellId)

    private fun BaseStationEntity.toDomain() = BaseStation(
        key = toDomainKey(),
        latitude = latitude,
        longitude = longitude,
        azimuthDeg = azimuthDeg,
        sectorWidthDeg = sectorWidthDeg,
        rangeM = rangeM,
        accuracyM = accuracyM,
        verified = verified,
    )
}
