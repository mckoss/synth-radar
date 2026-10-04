package com.mckoss.synthradar.radar_camera

import android.graphics.Rect
import android.hardware.camera2.params.BlackLevelPattern
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OisSample
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Rational
import android.util.Size
import android.util.SizeF
import org.json.JSONArray
import org.json.JSONObject

/** Converts Camera2 metadata values into JSON-friendly objects. */
object MetadataJson {
    fun toJson(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Boolean, is Int, is Long, is String -> value
        is Float -> if (value.isFinite()) value.toDouble() else value.toString()
        is Double -> if (value.isFinite()) value else value.toString()
        is Number -> value
        is IntArray -> JSONArray().apply { value.forEach { put(it) } }
        is LongArray -> JSONArray().apply { value.forEach { put(it) } }
        is FloatArray -> JSONArray().apply { value.forEach { put(toJson(it)) } }
        is DoubleArray -> JSONArray().apply { value.forEach { put(toJson(it)) } }
        is BooleanArray -> JSONArray().apply { value.forEach { put(it) } }
        is ByteArray -> JSONArray().apply { value.forEach { put(it.toInt()) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
        is Collection<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
        is Size -> JSONArray().put(value.width).put(value.height)
        is SizeF -> JSONArray().put(value.width.toDouble()).put(value.height.toDouble())
        is Rect -> JSONObject()
            .put("left", value.left).put("top", value.top)
            .put("right", value.right).put("bottom", value.bottom)
        is Range<*> -> JSONArray().put(toJson(value.lower)).put(toJson(value.upper))
        is Rational -> value.toDouble()
        is MeteringRectangle -> JSONObject()
            .put("x", value.x).put("y", value.y)
            .put("width", value.width).put("height", value.height)
            .put("weight", value.meteringWeight)
        is OisSample -> JSONObject()
            .put("timestamp", value.timestamp)
            .put("xshift", value.xshift.toDouble())
            .put("yshift", value.yshift.toDouble())
        is RggbChannelVector -> JSONArray()
            .put(value.red.toDouble()).put(value.greenEven.toDouble())
            .put(value.greenOdd.toDouble()).put(value.blue.toDouble())
        is ColorSpaceTransform -> JSONArray().apply {
            for (r in 0 until 3) for (c in 0 until 3) put(value.getElement(c, r).toDouble())
        }
        is BlackLevelPattern -> JSONArray().apply {
            for (r in 0 until 2) for (c in 0 until 2) put(value.getOffsetForIndex(c, r))
        }
        is StreamConfigurationMap -> streamConfigToJson(value)
        else -> value.toString()
    }

    private fun streamConfigToJson(map: StreamConfigurationMap): JSONObject {
        val out = JSONObject()
        val formats = JSONObject()
        for (format in map.outputFormats) {
            val sizes = JSONArray()
            for (size in map.getOutputSizes(format) ?: emptyArray()) {
                sizes.put(
                    JSONObject()
                        .put("size", toJson(size))
                        .put("minFrameDurationNs", map.getOutputMinFrameDuration(format, size))
                        .put("stallDurationNs", map.getOutputStallDuration(format, size)),
                )
            }
            formats.put("0x" + Integer.toHexString(format), sizes)
        }
        out.put("outputFormats", formats)
        val codecSizes = JSONArray()
        for (size in map.getOutputSizes(android.media.MediaCodec::class.java) ?: emptyArray()) {
            codecSizes.put(
                JSONObject()
                    .put("size", toJson(size))
                    .put(
                        "minFrameDurationNs",
                        map.getOutputMinFrameDuration(android.media.MediaCodec::class.java, size),
                    ),
            )
        }
        out.put("mediaCodecSizes", codecSizes)
        out.put("highSpeedVideoSizes", toJson(map.highSpeedVideoSizes))
        out.put("highSpeedVideoFpsRanges", toJson(map.highSpeedVideoFpsRanges))
        return out
    }
}
