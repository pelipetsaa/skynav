package com.skynav.data.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.skynav.domain.model.ImuSample
import com.skynav.domain.model.Vec3
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Emits one [ImuSample] per gyroscope event, combined with the latest linear acceleration and gravity.
 * Timestamps are `SensorEvent.timestamp` in ms (elapsed realtime base, same as cell scans).
 */
class ImuDataCollector(private val sensorManager: SensorManager) {

    fun samples(): Flow<ImuSample> = callbackFlow {
        var linAcc = Vec3(0.0, 0.0, 0.0)
        var gravity = Vec3(0.0, 0.0, 9.81)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values
                when (event.sensor.type) {
                    Sensor.TYPE_LINEAR_ACCELERATION -> linAcc = Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
                    Sensor.TYPE_GRAVITY -> gravity = Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
                    Sensor.TYPE_GYROSCOPE -> trySend(
                        ImuSample(
                            timestampMs = event.timestamp / 1_000_000L,
                            linearAcc = linAcc,
                            gyro = Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()),
                            gravity = gravity,
                        ),
                    )
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val handler = Handler(Looper.getMainLooper())
        val types = listOf(Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GRAVITY, Sensor.TYPE_GYROSCOPE)
        for (type in types) {
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, handler)
            }
        }
        awaitClose { sensorManager.unregisterListener(listener) }
    }
}
