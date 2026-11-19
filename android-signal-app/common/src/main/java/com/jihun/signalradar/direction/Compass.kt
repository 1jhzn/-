package com.jihun.signalradar.direction

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Which way the phone is pointing, from the compass sensors.
 *
 * TYPE_ROTATION_VECTOR is the good path (it fuses accelerometer, gyroscope and
 * magnetometer for us). Phones without it fall back to accelerometer + magnetic field,
 * and a phone without either simply reports [isSupported] = false so the caller can say
 * so instead of crashing.
 */
class Compass(context: Context) : SensorEventListener {

    private val manager: SensorManager? =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val rotationVector: Sensor? =
        manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val accelerometer: Sensor? =
        manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val magneticField: Sensor? =
        manager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var usingVector = false
    private var listening = false

    /** Compass bearing of the top of the phone, 0..360, or null until the first reading. */
    var heading: Float? = null
        private set

    /** How far the phone is tilted away from flat, in degrees. */
    var tilt: Float? = null
        private set

    val isSupported: Boolean
        get() = rotationVector != null || (accelerometer != null && magneticField != null)

    val modeText: String
        get() = when {
            rotationVector != null -> "나침반 센서"
            accelerometer != null && magneticField != null -> "가속도·자기 센서"
            else -> "방향 센서 없음"
        }

    fun start(): Boolean {
        val sm = manager ?: return false
        if (listening) return true

        val vector = rotationVector
        if (vector != null) {
            usingVector = true
            if (register(sm, vector)) return true
            usingVector = false
        }

        val accel = accelerometer
        val magnet = magneticField
        if (accel != null && magnet != null) {
            val okAccel = register(sm, accel)
            val okMagnet = register(sm, magnet)
            return okAccel && okMagnet
        }
        return false
    }

    fun stop() {
        val sm = manager ?: return
        if (!listening) return
        try {
            sm.unregisterListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterListener failed", e)
        }
        listening = false
    }

    private fun register(sm: SensorManager, sensor: Sensor): Boolean = try {
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        listening = true
        true
    } catch (e: Exception) {
        Log.w(TAG, "registerListener(${sensor.name}) failed", e)
        false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        try {
            when {
                event.sensor.type == Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
                    applyRotationMatrix()
                }
                !usingVector && event.sensor.type == Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(values, 0, gravity, 0, 3)
                    updateFromGravityAndField()
                }
                !usingVector && event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(values, 0, geomagnetic, 0, 3)
                    updateFromGravityAndField()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "heading update failed", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Nothing to do: a less accurate compass still beats no arrow at all.
    }

    private fun updateFromGravityAndField() {
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
            applyRotationMatrix()
        }
    }

    private fun applyRotationMatrix() {
        SensorManager.getOrientation(rotationMatrix, orientation)
        heading = SignalDirectionTracker.normalize(
            Math.toDegrees(orientation[0].toDouble()).toFloat()
        )
        tilt = Math.toDegrees(orientation[1].toDouble()).toFloat()
    }

    companion object {
        private const val TAG = "Compass"
    }
}
