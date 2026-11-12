package com.skynav.data.basestation

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

@Entity(
    tableName = "base_stations",
    primaryKeys = ["mcc", "mnc", "technology", "cellId"],
)
data class BaseStationEntity(
    val mcc: Int,
    val mnc: Int,
    val technology: String,
    val cellId: Long,
    val latitude: Double,
    val longitude: Double,
    val azimuthDeg: Double?,
    val sectorWidthDeg: Double?,
    val rangeM: Double?,
    val accuracyM: Double,
    val verified: Boolean,
)

@Dao
interface BaseStationDao {

    @Upsert
    suspend fun upsertAll(items: List<BaseStationEntity>)

    @Query("SELECT * FROM base_stations")
    suspend fun all(): List<BaseStationEntity>

    @Query("SELECT COUNT(*) FROM base_stations")
    suspend fun count(): Int
}

@Database(entities = [BaseStationEntity::class], version = 1, exportSchema = false)
abstract class SkyNavDatabase : RoomDatabase() {
    abstract fun baseStationDao(): BaseStationDao
}
