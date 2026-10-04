package com.mckoss.synthradar.radar_camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodec
import android.os.Build
import android.util.Size
import org.json.JSONObject

/** Reads camera characteristics: a full dump, and a compact summary for the UI. */
class CameraInfo(private val manager: CameraManager) {

    /** Every key of every camera (logical and physical) as JSON. */
    fun dumpAll(): JSONObject {
        val root = JSONObject()
        root.put("device", deviceJson())
        val cameras = JSONObject()
        val seen = mutableSetOf<String>()
        for (id in manager.cameraIdList) {
            addCamera(cameras, id, seen)
        }
        root.put("cameras", cameras)
        return root
    }

    private fun addCamera(cameras: JSONObject, id: String, seen: MutableSet<String>) {
        if (!seen.add(id)) return
        val chars = manager.getCameraCharacteristics(id)
        cameras.put(id, characteristicsJson(chars))
        for (physicalId in chars.physicalCameraIds) {
            addCamera(cameras, physicalId, seen)
        }
    }

    fun characteristicsJson(chars: CameraCharacteristics): JSONObject {
        val out = JSONObject()
        for (key in chars.keys) {
            val value = try {
                MetadataJson.toJson(chars.get(key))
            } catch (e: Exception) {
                "error: ${e.message}"
            }
            out.put(key.name, value)
        }
        out.put("physicalCameraIds", MetadataJson.toJson(chars.physicalCameraIds.toList()))
        return out
    }

    /** Compact per-camera summary used by the Flutter UI to pick a camera and mode. */
    fun summaries(): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        val seen = mutableSetOf<String>()
        fun add(id: String, logicalParent: String?) {
            if (!seen.add(id)) return
            val c = manager.getCameraCharacteristics(id)
            result.add(summary(id, c, logicalParent))
            for (p in c.physicalCameraIds) add(p, id)
        }
        for (id in manager.cameraIdList) add(id, null)
        return result
    }

    fun summary(id: String, c: CameraCharacteristics, logicalParent: String?): Map<String, Any?> {
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_BACK -> "back"
            CameraMetadata.LENS_FACING_FRONT -> "front"
            CameraMetadata.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val codecSizes = map?.getOutputSizes(MediaCodec::class.java)?.toList() ?: emptyList()
        val videoModes = mutableListOf<Map<String, Any>>()
        val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList() ?: emptyList()
        for (size in STANDARD_VIDEO_SIZES) {
            if (codecSizes.none { it == size }) continue
            val minDuration = map!!.getOutputMinFrameDuration(MediaCodec::class.java, size)
            for (fps in listOf(30, 60)) {
                val durationOk = minDuration == 0L || minDuration <= 1_000_000_000L / fps + 1000
                val rangeOk = fpsRanges.any { it.lower == fps && it.upper == fps }
                if (durationOk && rangeOk) {
                    videoModes.add(mapOf("width" to size.width, "height" to size.height, "fps" to fps))
                }
            }
        }
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
        return mapOf(
            "id" to id,
            "logicalParent" to logicalParent,
            "facing" to facing,
            "isLogicalMultiCamera" to
                caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA),
            "physicalCameraIds" to c.physicalCameraIds.toList(),
            "sensorOrientation" to c.get(CameraCharacteristics.SENSOR_ORIENTATION),
            "focalLengths" to c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.map { it.toDouble() },
            "sensorPhysicalSize" to c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?.let { listOf(it.width.toDouble(), it.height.toDouble()) },
            "pixelArraySize" to c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                ?.let { listOf(it.width, it.height) },
            "activeArray" to c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?.let { listOf(it.left, it.top, it.right, it.bottom) },
            "intrinsics" to c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                ?.map { it.toDouble() },
            "distortion" to c.get(CameraCharacteristics.LENS_DISTORTION)?.map { it.toDouble() },
            "poseTranslation" to c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
                ?.map { it.toDouble() },
            "poseRotation" to c.get(CameraCharacteristics.LENS_POSE_ROTATION)?.map { it.toDouble() },
            "timestampSource" to when (c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
                else -> "UNKNOWN"
            },
            "videoStabilizationModes" to
                c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList(),
            "opticalStabilizationModes" to
                c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList(),
            "distortionCorrectionModes" to
                c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)?.toList(),
            "zoomRatioRange" to (if (Build.VERSION.SDK_INT >= 30) {
                c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    ?.let { listOf(it.lower.toDouble(), it.upper.toDouble()) }
            } else null),
            "videoModes" to videoModes,
        )
    }

    companion object {
        val STANDARD_VIDEO_SIZES = listOf(Size(3840, 2160), Size(1920, 1080), Size(1280, 720))

        fun deviceJson(): JSONObject = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("product", Build.PRODUCT)
            .put("fingerprint", Build.FINGERPRINT)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
    }
}
