package com.mckoss.synthradar.radar_camera

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Logs motion sensors at the fastest rate to a CSV file.
 *
 * Columns: sensor,timestamp_ns,v0,v1,v2[,v3,v4,v5]. Event timestamps use the
 * elapsedRealtimeNanos (boot-time) clock, the same clock as camera SENSOR_TIMESTAMP
 * when the camera's timestamp source is REALTIME.
 */
class SensorLogger(private val sensorManager: SensorManager) : SensorEventListener {
    private var thread: HandlerThread? = null
    private var writer: BufferedWriter? = null
    private val lock = Any()
    val gyroCount = AtomicLong()
    val totalCount = AtomicLong()

    fun availableSensors(): List<Sensor> = SENSOR_TYPES.mapNotNull { sensorManager.getDefaultSensor(it) }

    fun start(file: File) {
        stop()
        gyroCount.set(0)
        totalCount.set(0)
        writer = file.bufferedWriter(bufferSize = 1 shl 16).also {
            it.write("sensor,timestamp_ns,v0,v1,v2,v3,v4,v5\n")
        }
        val t = HandlerThread("radar-sensors").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        for (sensor in availableSensors()) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        thread?.quitSafely()
        thread?.join(1000)
        thread = null
        synchronized(lock) {
            writer?.close()
            writer = null
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val name = SENSOR_NAMES[event.sensor.type] ?: return
        val sb = StringBuilder(96).append(name).append(',').append(event.timestamp)
        for (i in 0 until 6) {
            sb.append(',')
            if (i < event.values.size) sb.append(event.values[i])
        }
        sb.append('\n')
        synchronized(lock) { writer?.write(sb.toString()) }
        totalCount.incrementAndGet()
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) gyroCount.incrementAndGet()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    companion object {
        val SENSOR_TYPES = listOf(
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR,
        )
        val SENSOR_NAMES = mapOf(
            Sensor.TYPE_GYROSCOPE to "gyro",
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED to "gyro_uncal",
            Sensor.TYPE_ACCELEROMETER to "accel",
            Sensor.TYPE_GRAVITY to "gravity",
            Sensor.TYPE_GAME_ROTATION_VECTOR to "game_rv",
            Sensor.TYPE_ROTATION_VECTOR to "rv",
        )
    }
}
