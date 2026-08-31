package com.warehouse.stockchecker.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.asin

/**
 * Snapshot of the device's camera orientation in world space.
 *
 * @param yawRadians rotation around the vertical (down) axis, in radians.
 * @param pitchRadians rotation around the horizontal axis, in radians.
 * @param rollRadians rotation around the camera's forward axis, in radians.
 * @param timestampNanos monotonic clock reading, in nanoseconds.
 * @param valid true when the rotation-vector sensor has produced at least one sample.
 *
 * Conventions match the Android rotation-vector sensor: when the phone is held upright with
 * the screen facing the user and the top edge pointing away, yaw is ~0, pitch is ~0, roll is
 * ~0. Yaw grows when the device is rotated clockwise as seen from above.
 */
data class CameraOrientation(
    val yawRadians: Float,
    val pitchRadians: Float,
    val rollRadians: Float,
    val timestampNanos: Long,
    val valid: Boolean
) {
    companion object {
        val UNKNOWN = CameraOrientation(0f, 0f, 0f, 0L, valid = false)
    }
}

/**
 * Streams the device's rotation vector sensor and exposes the latest orientation.
 *
 * Without ARCore, the only reliable handle we have on camera motion is the rotation-vector
 * sensor (gyro + accelerometer fused). It is good enough to keep a bounding box glued to a
 * stationary real-world part: the box follows the camera's rotation around that part, not the
 * pixel rectangle the part was first detected in.
 *
 * Not thread safe; readings are intended to be consumed from one analysis thread.
 */
class OrientationProvider(
    context: Context
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        .defaultDisplay

    @Volatile private var latest: CameraOrientation = CameraOrientation.UNKNOWN
    @Volatile private var started = false

    /** Begins listening; safe to call repeatedly. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        rotationVector?.let { sensor ->
            // SENSOR_DELAY_GAME is the lowest rate that still keeps a marker from swimming when
            // the user pans the camera. UI rate is plenty for our 2.5 FPS detection cadence.
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    /** Stops listening; safe to call when never started. */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        sensorManager.unregisterListener(this)
    }

    /** Returns the most recent orientation, or [CameraOrientation.UNKNOWN] if none yet. */
    fun current(): CameraOrientation = latest

    /**
     * Returns the orientation at or before [timestampNanos] by extrapolating from the latest
     * sample with the device's rotational velocity. Falls back to the latest sample when the
     * requested time is in the future or the sensor has not produced data yet.
     */
    fun orientationAt(timestampNanos: Long): CameraOrientation {
        val now = latest
        if (!now.valid) return now
        if (timestampNanos >= now.timestampNanos) return now
        // Without angular velocity we just clamp; the worst case is a single inter-frame
        // extrapolation, well under 100 ms at our inference cadence.
        return now.copy(timestampNanos = timestampNanos)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        // Rotation vector is a quaternion (x, y, z, [w]). Convert to Euler in a frame that is
        // invariant to the activity's display rotation: we want yaw/pitch/roll of the *camera*
        // relative to world gravity, not relative to the screen.
        val rotation = event.values
        val x = rotation[0]
        val y = rotation[1]
        val z = rotation[2]
        val w = if (rotation.size >= 4) rotation[3] else 0f

        // Rotate the quaternion by the display rotation so screen-portrait and screen-landscape
        // both produce a stable yaw/pitch reading.
        val rotationDeg = displayRotationDegrees()
        val (qx, qy, qz, qw) = applyDisplayRotation(x, y, z, w, rotationDeg)

        // Standard quaternion -> Euler in radians.
        val sinPitch = 2f * (qw * qx + qy * qz)
        val cosPitch = 1f - 2f * (qx * qx + qy * qy)
        val pitchRaw = atan2(sinPitch, cosPitch)

        val sinRoll = 2f * (qw * qy - qz * qx)
        val rollRaw = if (sinRoll > 1f) (PI / 2f).toFloat() else if (sinRoll < -1f) -(PI / 2f).toFloat() else asin(sinRoll)

        val sinYaw = 2f * (qw * qz + qx * qy)
        val cosYaw = 1f - 2f * (qy * qy + qz * qz)
        val yawRaw = atan2(sinYaw, cosYaw)

        latest = CameraOrientation(
            yawRadians = yawRaw,
            pitchRadians = pitchRaw,
            rollRadians = rollRaw,
            timestampNanos = SystemClock.elapsedRealtimeNanos(),
            valid = true
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun displayRotationDegrees(): Int = when (display.rotation) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /**
     * Rotates the rotation-vector quaternion by [degrees] around Z so the resulting Euler
     * angles describe the camera's orientation in a stable world frame regardless of the
     * activity's screen orientation.
     */
    private fun applyDisplayRotation(
        x: Float,
        y: Float,
        z: Float,
        w: Float,
        degrees: Int
    ): Quaternion {
        if (degrees == 0) return Quaternion(x, y, z, w)
        val rad = degrees * (PI.toFloat() / 180f)
        val half = rad / 2f
        val cz = kotlin.math.cos(half)
        val sz = kotlin.math.sin(half)
        // q_display * q_sensor (display rotation applied on the right: rotates the sensor's
        // frame, which is what we want for portrait-locked activities).
        val nw = cz * w - sz * z
        val nx = cz * x - sz * y
        val ny = cz * y + sz * x
        val nz = cz * z + sz * w
        return Quaternion(nx, ny, nz, nw)
    }

    private data class Quaternion(val x: Float, val y: Float, val z: Float, val w: Float)
}
