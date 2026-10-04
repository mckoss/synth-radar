package com.mckoss.synthradar.radar_camera

import android.annotation.SuppressLint
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import io.flutter.view.TextureRegistry
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Owns one open camera: a live preview into a Flutter texture, and optional
 * recording of a measurement bundle (MP4 + per-frame metadata + IMU log).
 *
 * Measurement settings: fixed zoom ratio (1x main lens, or e.g. 5x telephoto), electronic video stabilization OFF,
 * fixed frame rate, and per-frame lens calibration / OIS reporting when supported.
 */
class CameraController(
    private val manager: CameraManager,
    private val sensorManager: SensorManager,
    private val textures: TextureRegistry,
    private val onStatus: (Map<String, Any?>) -> Unit,
) {
    private val thread = HandlerThread("radar-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraId: String = ""
    private lateinit var characteristics: CameraCharacteristics
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var producer: TextureRegistry.SurfaceProducer? = null
    var width = 1920; private set
    var height = 1080; private set
    var fps = 30; private set
    var zoomRatio = 1.0f; private set
    var opticalStabilization = true; private set

    private var recording: Recording? = null
    private val sensorLogger = SensorLogger(sensorManager)

    // Live statistics for the status stream.
    private var frameCount = 0L
    private var lastStatusNs = 0L
    private var framesAtLastStatus = 0L
    private var gyroAtLastStatus = 0L
    private var lastResult: TotalCaptureResult? = null

    private class Recording(
        val dir: File,
        val writer: ClipWriter,
        val frames: BufferedWriter,
        val startElapsedNs: Long,
        val startWallMs: Long,
        var frameResults: Long = 0,
        var firstSensorTs: Long = -1,
        var lastSensorTs: Long = -1,
    )

    /** Opens the camera and starts the preview. Returns texture and orientation info. */
    @SuppressLint("MissingPermission")
    fun open(
        id: String,
        width: Int,
        height: Int,
        fps: Int,
        zoomRatio: Float,
        opticalStabilization: Boolean,
    ): Map<String, Any?> {
        close()
        cameraId = id
        characteristics = manager.getCameraCharacteristics(id)
        this.width = width
        this.height = height
        this.fps = fps
        this.zoomRatio = zoomRatio
        this.opticalStabilization = opticalStabilization
        // Texture registration must happen on the platform (main) thread.
        val p = runOnMain { textures.createSurfaceProducer() }
        p.setSize(PREVIEW_W, PREVIEW_H)
        p.setCallback(object : TextureRegistry.SurfaceProducer.Callback {
            override fun onSurfaceAvailable() {
                handler.post { if (recording == null) createSession(null) }
            }

            override fun onSurfaceCleanup() {
                handler.post {
                    if (recording == null) {
                        session?.close()
                        session = null
                    }
                }
            }
        })
        producer = p
        val latch = CountDownLatch(1)
        var error: String? = null
        manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                latch.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (device == camera) device = null
                error = "disconnected"
                latch.countDown()
                emit(mapOf("event" to "error", "message" to "camera disconnected"))
            }

            override fun onError(camera: CameraDevice, code: Int) {
                camera.close()
                if (device == camera) device = null
                error = "camera error $code"
                latch.countDown()
                emit(mapOf("event" to "error", "message" to "camera error $code"))
            }
        })
        if (!latch.await(5, TimeUnit.SECONDS)) throw IllegalStateException("camera open timeout")
        error?.let { throw IllegalStateException(it) }
        runOnCamera { createSession(null) }
        return mapOf(
            "textureId" to p.id(),
            "previewWidth" to PREVIEW_W,
            "previewHeight" to PREVIEW_H,
            "sensorOrientation" to characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION),
            "handlesCropAndRotation" to p.handlesCropAndRotation(),
        )
    }

    fun close() {
        if (recording != null) stopRecording()
        runOnCamera {
            session?.close()
            session = null
            device?.close()
            device = null
        }
        producer?.let { p -> runOnMain { p.release() } }
        producer = null
    }

    fun dispose() {
        close()
        thread.quitSafely()
    }

    /** Starts recording a bundle into [dir]. */
    fun startRecording(dir: File, withAudio: Boolean): Map<String, Any?> {
        check(device != null) { "camera not open" }
        check(recording == null) { "already recording" }
        dir.mkdirs()
        val writer = ClipWriter(
            File(dir, "video.mp4"), File(dir, "video_frames.csv"), width, height, fps, withAudio,
        )
        sensorLogger.start(File(dir, "imu.csv"))
        val rec = Recording(
            dir = dir,
            writer = writer,
            frames = File(dir, "frames.jsonl").bufferedWriter(bufferSize = 1 shl 16),
            startElapsedNs = SystemClock.elapsedRealtimeNanos(),
            startWallMs = System.currentTimeMillis(),
        )
        writer.start()
        runOnCamera {
            recording = rec
            createSession(writer.inputSurface)
        }
        File(dir, "camera_characteristics.json").writeText(
            CameraInfo(manager).dumpAll().toString(1),
        )
        return mapOf("dir" to dir.absolutePath, "audio" to writer.audioEnabled)
    }

    /** Stops recording and finalizes the bundle. Returns a summary. */
    fun stopRecording(): Map<String, Any?> {
        val rec = recording ?: throw IllegalStateException("not recording")
        runOnCamera {
            recording = null
            createSession(null)
        }
        rec.writer.stop()
        sensorLogger.stop()
        synchronized(rec) { rec.frames.close() }
        val stopElapsedNs = SystemClock.elapsedRealtimeNanos()
        val stopWallMs = System.currentTimeMillis()
        val summary = mapOf(
            "dir" to rec.dir.absolutePath,
            "videoFrames" to rec.writer.videoFrameCount,
            "captureResults" to rec.frameResults,
            "durationS" to (rec.lastSensorTs - rec.firstSensorTs) / 1e9,
            "imuSamples" to sensorLogger.totalCount.get(),
            "gyroSamples" to sensorLogger.gyroCount.get(),
        )
        File(rec.dir, "session.json").writeText(
            sessionJson(rec, stopElapsedNs, stopWallMs, summary).toString(2),
        )
        return summary
    }

    private fun sessionJson(
        rec: Recording,
        stopElapsedNs: Long,
        stopWallMs: Long,
        summary: Map<String, Any?>,
    ): JSONObject {
        val c = characteristics
        return JSONObject()
            .put("format", "synth-radar-recording")
            .put("formatVersion", 1)
            .put("device", CameraInfo.deviceJson())
            .put("cameraId", cameraId)
            .put("camera", JSONObject(CameraInfo(manager).summary(cameraId, c, null).mapValues {
                MetadataJson.toJson(it.value)
            }))
            .put("video", JSONObject()
                .put("file", "video.mp4")
                .put("width", width).put("height", height).put("fps", fps)
                .put("zoomRatio", zoomRatio.toDouble())
                .put("opticalStabilization", opticalStabilization)
                .put("mime", rec.writer.videoMime).put("bitrate", rec.writer.videoBitrate)
                .put("ptsClock", "elapsedRealtime (boot time), microseconds"))
            .put("audio", JSONObject()
                .put("enabled", rec.writer.audioEnabled)
                .put("sampleRate", rec.writer.audioSampleRate)
                .put("source", "CAMCORDER"))
            .put("clock", JSONObject()
                .put("startElapsedRealtimeNs", rec.startElapsedNs)
                .put("startWallClockMs", rec.startWallMs)
                .put("stopElapsedRealtimeNs", stopElapsedNs)
                .put("stopWallClockMs", stopWallMs))
            .put("summary", JSONObject(summary.mapValues { MetadataJson.toJson(it.value) }))
            .put("files", JSONObject()
                .put("video", "video.mp4")
                .put("videoFrames", "video_frames.csv")
                .put("captureResults", "frames.jsonl")
                .put("imu", "imu.csv")
                .put("characteristics", "camera_characteristics.json"))
    }

    private fun <T> runOnMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var value: T? = null
        var failure: Throwable? = null
        mainHandler.post {
            try { value = block() } catch (t: Throwable) { failure = t } finally { latch.countDown() }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) throw IllegalStateException("main thread timeout")
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun runOnCamera(block: () -> Unit) {
        if (Thread.currentThread() == thread) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try { block() } catch (t: Throwable) { failure = t } finally { latch.countDown() }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) throw IllegalStateException("camera thread timeout")
        failure?.let { throw it }
    }

    /** (Re)creates the capture session with the preview and an optional encoder surface. */
    private fun createSession(encoderSurface: Surface?) {
        val dev = device ?: return
        val previewSurface = producer?.surface ?: return
        session?.close()
        session = null
        val targets = listOfNotNull(previewSurface, encoderSurface)
        val outputs = targets.map { OutputConfiguration(it) }
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val request = buildRequest(dev, targets)
                        s.setRepeatingRequest(request, captureCallback, handler)
                    } catch (e: Exception) {
                        Log.e(TAG, "repeating request", e)
                        emit(mapOf("event" to "error", "message" to "request failed: ${e.message}"))
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    emit(mapOf("event" to "error", "message" to "session configuration failed"))
                }
            },
        )
        dev.createCaptureSession(config)
    }

    private fun buildRequest(dev: CameraDevice, targets: List<Surface>): CaptureRequest {
        val c = characteristics
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        targets.forEach { b.addTarget(it) }
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        b.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        // OIS reduces blur from hand shake but shifts the image by up to tens of pixels;
        // its per-frame shift is logged (STATISTICS_OIS_SAMPLES) where supported.
        val oisMode = if (opticalStabilization) {
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
        } else {
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
        }
        if (ois?.contains(oisMode) == true) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, oisMode)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val oisData = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES)
            if (oisData?.contains(CameraMetadata.STATISTICS_OIS_DATA_MODE_ON) == true) {
                b.set(CaptureRequest.STATISTICS_OIS_DATA_MODE, CameraMetadata.STATISTICS_OIS_DATA_MODE_ON)
            }
        }
        return b.build()
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            frameCount++
            lastResult = result
            val rec = recording
            if (rec != null) {
                val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: -1L
                if (rec.firstSensorTs < 0) rec.firstSensorTs = ts
                rec.lastSensorTs = ts
                rec.frameResults++
                val line = frameJson(result).toString()
                synchronized(rec) {
                    try {
                        rec.frames.write(line)
                        rec.frames.write("\n")
                    } catch (_: Exception) {
                        // Writer may already be closed while the last results drain.
                    }
                }
            }
            maybeEmitStatus()
        }
    }

    private fun frameJson(r: TotalCaptureResult): JSONObject {
        val o = JSONObject()
        o.put("frameNumber", r.frameNumber)
        for (key in FRAME_KEYS) {
            val v = try { r.get(key) } catch (_: Exception) { null } ?: continue
            o.put(key.name, MetadataJson.toJson(v))
        }
        if (Build.VERSION.SDK_INT >= 29) {
            r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)?.let {
                o.put("activePhysicalId", it)
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            r.get(CaptureResult.CONTROL_ZOOM_RATIO)?.let { o.put("zoomRatio", it.toDouble()) }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            r.get(CaptureResult.STATISTICS_OIS_SAMPLES)?.let { o.put("oisSamples", MetadataJson.toJson(it)) }
        }
        return o
    }

    private fun maybeEmitStatus() {
        val now = SystemClock.elapsedRealtimeNanos()
        if (lastStatusNs == 0L) {
            lastStatusNs = now
            framesAtLastStatus = frameCount
            return
        }
        val dt = (now - lastStatusNs) / 1e9
        if (dt < 0.5) return
        val gyro = sensorLogger.gyroCount.get()
        val r = lastResult
        val rec = recording
        val status = mutableMapOf<String, Any?>(
            "event" to "status",
            "fps" to (frameCount - framesAtLastStatus) / dt,
            "recording" to (rec != null),
        )
        if (rec != null) {
            status["elapsedS"] = (now - rec.startElapsedNs) / 1e9
            status["encodedFrames"] = rec.writer.videoFrameCount
            status["gyroHz"] = (gyro - gyroAtLastStatus).coerceAtLeast(0) / dt
        }
        if (r != null) {
            status["exposureMs"] = r.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { it / 1e6 }
            status["iso"] = r.get(CaptureResult.SENSOR_SENSITIVITY)
            status["focusDistanceDiopters"] = r.get(CaptureResult.LENS_FOCUS_DISTANCE)?.toDouble()
            status["focalLengthMm"] = r.get(CaptureResult.LENS_FOCAL_LENGTH)?.toDouble()
            status["stabilization"] = r.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
            if (Build.VERSION.SDK_INT >= 29) {
                status["activePhysicalId"] = r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            }
        }
        lastStatusNs = now
        framesAtLastStatus = frameCount
        gyroAtLastStatus = gyro
        emit(status)
    }

    private fun emit(event: Map<String, Any?>) = onStatus(event)

    companion object {
        private const val TAG = "RadarCamera"
        const val PREVIEW_W = 1920
        const val PREVIEW_H = 1080

        /** Per-frame capture result keys saved to frames.jsonl. */
        val FRAME_KEYS: List<CaptureResult.Key<*>> = listOf(
            CaptureResult.SENSOR_TIMESTAMP,
            CaptureResult.SENSOR_EXPOSURE_TIME,
            CaptureResult.SENSOR_FRAME_DURATION,
            CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW,
            CaptureResult.SENSOR_SENSITIVITY,
            CaptureResult.LENS_FOCAL_LENGTH,
            CaptureResult.LENS_FOCUS_DISTANCE,
            CaptureResult.LENS_APERTURE,
            CaptureResult.LENS_STATE,
            CaptureResult.LENS_OPTICAL_STABILIZATION_MODE,
            CaptureResult.LENS_INTRINSIC_CALIBRATION,
            CaptureResult.LENS_DISTORTION,
            CaptureResult.LENS_POSE_ROTATION,
            CaptureResult.LENS_POSE_TRANSLATION,
            CaptureResult.SCALER_CROP_REGION,
            CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureResult.CONTROL_AE_STATE,
            CaptureResult.CONTROL_AF_STATE,
            CaptureResult.DISTORTION_CORRECTION_MODE,
        )
    }
}
