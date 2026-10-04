package com.mckoss.synthradar.radar_camera

import android.content.Context
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.util.concurrent.Executors

/** Flutter entry point. Blocking work runs on a single worker thread, results return on main. */
class RadarCameraPlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var channel: MethodChannel
    private lateinit var events: EventChannel
    private lateinit var context: Context
    private lateinit var cameraInfo: CameraInfo
    private var controller: CameraController? = null
    private var eventSink: EventChannel.EventSink? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        cameraInfo = CameraInfo(cameraManager)
        controller = CameraController(cameraManager, sensorManager, binding.textureRegistry) { event ->
            main.post { eventSink?.success(event) }
        }
        channel = MethodChannel(binding.binaryMessenger, "radar_camera")
        channel.setMethodCallHandler(this)
        events = EventChannel(binding.binaryMessenger, "radar_camera/events")
        events.setStreamHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        events.setStreamHandler(null)
        worker.execute { controller?.dispose() }
        worker.shutdown()
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        worker.execute {
            try {
                val value = handle(call)
                main.post { result.success(value) }
            } catch (e: NotImplementedError) {
                main.post { result.notImplemented() }
            } catch (e: Throwable) {
                main.post { result.error(e.javaClass.simpleName, e.message, e.stackTraceToString()) }
            }
        }
    }

    private fun handle(call: MethodCall): Any? {
        val c = controller!!
        return when (call.method) {
            "listCameras" -> cameraInfo.summaries()
            "dumpCharacteristics" -> {
                val path = call.argument<String>("path")!!
                val json = cameraInfo.dumpAll().toString(1)
                File(path).apply { parentFile?.mkdirs() }.writeText(json)
                path
            }
            "open" -> c.open(
                call.argument<String>("cameraId")!!,
                call.argument<Int>("width")!!,
                call.argument<Int>("height")!!,
                call.argument<Int>("fps")!!,
                (call.argument<Double>("zoomRatio") ?: 1.0).toFloat(),
            )
            "startRecording" -> c.startRecording(
                File(call.argument<String>("dir")!!),
                call.argument<Boolean>("audio") ?: true,
            )
            "stopRecording" -> c.stopRecording()
            "close" -> { c.close(); null }
            else -> throw NotImplementedError()
        }
    }

    override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
        eventSink = sink
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }
}
