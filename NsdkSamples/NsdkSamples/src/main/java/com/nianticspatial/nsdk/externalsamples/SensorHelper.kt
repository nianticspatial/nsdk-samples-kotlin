// Copyright 2025 Niantic.

package com.nianticspatial.nsdk.externalsamples

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.nianticspatial.nsdk.Compass

class SensorHelper(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var headingAccuracy: Float = -1f
    private var magneticHeading: Float = 0f
    private var trueHeading: Float = 0f
    private var rawDataX: Float = 0f
    private var rawDataY: Float = 0f
    private var rawDataZ: Float = 0f
    private var timestamp: Long = 0

    fun compass(): Compass {
        return Compass(
            timestamp,
            headingAccuracy,
            magneticHeading,
            rawDataX,
            rawDataY,
            rawDataZ,
            trueHeading
        )
    }

    fun resume() {
        sensorManager.registerListener(
            this,
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
            SensorManager.SENSOR_DELAY_UI
        )
        sensorManager.registerListener(
            this,
            sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR),
            SensorManager.SENSOR_DELAY_UI
        )
        sensorManager.registerListener(
            this,
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
            SensorManager.SENSOR_DELAY_UI
        )
    }

    fun pause() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        timestamp = event.timestamp
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                val orientationAngles = FloatArray(3)

                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)

                // Magnetic heading (relative to device screen top)
                val azimuthInRadians = orientationAngles[0]
                magneticHeading = Math.toDegrees(azimuthInRadians.toDouble()).toFloat()

                // Heading accuracy (in degrees), if available
                headingAccuracy = if (event.values.size >= 5) event.values[4] else -1f

                // Assume true heading is same as magnetic heading (adjustment requires location-based declination)
                trueHeading = magneticHeading
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                // Raw geomagnetic data (X, Y, Z) in microteslas
                rawDataX = event.values[0]
                rawDataY = event.values[1]
                rawDataZ = event.values[2]
            }
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
    }
}
