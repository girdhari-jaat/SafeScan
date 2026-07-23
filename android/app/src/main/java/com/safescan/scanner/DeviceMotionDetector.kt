package com.safescan.scanner

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Monitors hardware Accelerometer and Gyroscope sensors to detect device movement/shaking.
 * Used during auto-capture to ensure the phone is steady before taking a photo.
 */
class DeviceMotionDetector(context: Context) : SensorEventListener {

    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var lastAccelX = 0f
    private var lastAccelY = 0f
    private var lastAccelZ = 0f
    private var isFirstAccelSample = true

    // Threshold for linear acceleration delta (m/s^2)
    private val ACCEL_DELTA_THRESHOLD = 0.35f
    // Threshold for angular velocity magnitude (rad/s)
    private val GYRO_VELOCITY_THRESHOLD = 0.20f

    @Volatile
    var isDeviceStable: Boolean = true
        private set

    @Volatile
    var lastAccelDelta: Float = 0f
        private set

    @Volatile
    var lastGyroMagnitude: Float = 0f
        private set

    private var active = false

    fun start() {
        if (active || sensorManager == null) return
        active = true
        isFirstAccelSample = true
        isDeviceStable = true

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        Log.d("DeviceMotionDetector", "Motion detection started")
    }

    fun stop() {
        if (!active || sensorManager == null) return
        active = false
        sensorManager.unregisterListener(this)
        Log.d("DeviceMotionDetector", "Motion detection stopped")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !active) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]

                if (isFirstAccelSample) {
                    lastAccelX = ax
                    lastAccelY = ay
                    lastAccelZ = az
                    isFirstAccelSample = false
                } else {
                    val dx = abs(ax - lastAccelX)
                    val dy = abs(ay - lastAccelY)
                    val dz = abs(az - lastAccelZ)
                    lastAccelDelta = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

                    lastAccelX = ax
                    lastAccelY = ay
                    lastAccelZ = az

                    evaluateStability()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]

                lastGyroMagnitude = sqrt((gx * gx + gy * gy + gz * gz).toDouble()).toFloat()
                evaluateStability()
            }
        }
    }

    private fun evaluateStability() {
        val accelStable = lastAccelDelta < ACCEL_DELTA_THRESHOLD
        val gyroStable = gyroscope == null || lastGyroMagnitude < GYRO_VELOCITY_THRESHOLD

        isDeviceStable = accelStable && gyroStable
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
