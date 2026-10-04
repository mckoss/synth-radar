package com.mckoss.synthradar.radar_camera

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Encodes camera frames (via an input Surface) and microphone audio into an MP4.
 *
 * Presentation timestamps are kept on the boot-time clock in microseconds:
 * video PTS come straight from the camera's SENSOR_TIMESTAMP, and audio PTS are
 * derived from AudioRecord.getTimestamp(TIMEBASE_BOOTTIME). The PTS of every
 * encoded video frame is also written to a CSV so analysis tools can join MP4
 * frame indices to per-frame capture metadata and IMU samples.
 */
class ClipWriter(
    private val outputFile: File,
    private val videoPtsFile: File,
    val width: Int,
    val height: Int,
    val fps: Int,
    private val withAudio: Boolean,
) {
    val videoMime = MediaFormat.MIMETYPE_VIDEO_AVC
    val videoBitrate: Int = (width.toLong() * height * fps / 8).coerceAtMost(80_000_000).toInt()
    val audioSampleRate = 48_000

    private val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val muxerLock = Any()
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1
    private val pending = mutableListOf<PendingSample>()

    private val videoThread = HandlerThread("radar-video-enc").also { it.start() }
    private val videoCodec: MediaCodec = MediaCodec.createEncoderByType(videoMime)
    val inputSurface: Surface
    private val videoDone = CountDownLatch(1)
    private val videoPts = mutableListOf<Long>()
    @Volatile var videoFrameCount = 0L
        private set

    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false
    private val audioDone = CountDownLatch(1)
    var audioEnabled = false
        private set

    private class PendingSample(val track: () -> Int, val data: ByteArray, val info: MediaCodec.BufferInfo)

    init {
        val format = MediaFormat.createVideoFormat(videoMime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoCodec.setCallback(VideoCallback(), Handler(videoThread.looper))
        videoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = videoCodec.createInputSurface()
        if (withAudio) setupAudio()
    }

    fun start() {
        videoCodec.start()
        if (audioEnabled) {
            audioRunning = true
            audioCodec!!.start()
            audioRecord!!.startRecording()
            audioThread = Thread(::audioLoop, "radar-audio").also { it.start() }
        } else {
            audioDone.countDown()
        }
    }

    /** Ends both streams, finalizes the MP4, and writes the video PTS list. */
    fun stop() {
        try {
            videoCodec.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "signalEndOfInputStream failed", e)
            videoDone.countDown()
        }
        audioRunning = false
        if (!videoDone.await(5, TimeUnit.SECONDS)) Log.w(TAG, "video EOS timeout")
        audioThread?.join(3000)
        if (!audioDone.await(3, TimeUnit.SECONDS)) Log.w(TAG, "audio EOS timeout")
        release()
        videoPtsFile.bufferedWriter().use { w ->
            w.write("frame_index,pts_us\n")
            videoPts.sorted().forEachIndexed { i, pts -> w.write("$i,$pts\n") }
        }
    }

    private fun release() {
        try { videoCodec.stop() } catch (_: Exception) {}
        videoCodec.release()
        inputSurface.release()
        videoThread.quitSafely()
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        try { audioCodec?.stop() } catch (_: Exception) {}
        audioCodec?.release()
        synchronized(muxerLock) {
            if (muxerStarted) {
                try { muxer.stop() } catch (e: Exception) { Log.e(TAG, "muxer stop", e) }
            }
            muxer.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupAudio() {
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                audioSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val record = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, audioSampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 16384) * 2,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return
            }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioRecord = record
            audioCodec = codec
            audioEnabled = true
        } catch (e: Exception) {
            Log.w(TAG, "audio unavailable", e)
        }
    }

    private fun audioLoop() {
        val record = audioRecord!!
        val codec = audioCodec!!
        val chunk = ByteArray(2048 * 2)
        val ts = AudioTimestamp()
        var framesRead = 0L
        var anchorFrame = -1L
        var anchorNs = 0L
        var lastPtsUs = -1L
        var eosQueued = false
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!eosQueued) {
                    val n = if (audioRunning) record.read(chunk, 0, chunk.size) else 0
                    if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                        anchorFrame = ts.framePosition
                        anchorNs = ts.nanoTime
                    }
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val samples = maxOf(n, 0) / 2
                        var ptsUs = if (anchorFrame >= 0) {
                            (anchorNs + (framesRead - anchorFrame) * 1_000_000_000L / audioSampleRate) / 1000
                        } else {
                            SystemClock.elapsedRealtimeNanos() / 1000 - samples * 1_000_000L / audioSampleRate
                        }
                        if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 1
                        lastPtsUs = ptsUs
                        val buf = codec.getInputBuffer(inIndex)!!
                        buf.clear()
                        if (n > 0) buf.put(chunk, 0, n)
                        if (audioRunning) {
                            codec.queueInputBuffer(inIndex, 0, maxOf(n, 0), ptsUs, 0)
                        } else {
                            codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        }
                        framesRead += samples
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            audioTrack = muxer.addTrack(codec.outputFormat)
                            maybeStartMuxer()
                        }
                    }
                    outIndex >= 0 -> {
                        val out = codec.getOutputBuffer(outIndex)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            writeSample({ audioTrack }, out, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio loop", e)
        } finally {
            audioDone.countDown()
        }
    }

    private fun maybeStartMuxer() {
        if (muxerStarted || videoTrack < 0 || (audioEnabled && audioTrack < 0)) return
        muxer.start()
        muxerStarted = true
        for (s in pending) {
            muxer.writeSampleData(s.track(), ByteBuffer.wrap(s.data), s.info)
        }
        pending.clear()
    }

    private fun writeSample(track: () -> Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        synchronized(muxerLock) {
            if (muxerStarted) {
                muxer.writeSampleData(track(), buffer, info)
            } else {
                val copy = ByteArray(info.size)
                buffer.get(copy)
                val infoCopy = MediaCodec.BufferInfo().apply {
                    set(0, info.size, info.presentationTimeUs, info.flags)
                }
                pending.add(PendingSample(track, copy, infoCopy))
            }
        }
    }

    private inner class VideoCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val out = codec.getOutputBuffer(index)
                if (out != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    writeSample({ videoTrack }, out, info)
                    videoPts.add(info.presentationTimeUs)
                    videoFrameCount++
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                Log.e(TAG, "video output", e)
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) videoDone.countDown()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "video codec error", e)
            videoDone.countDown()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            synchronized(muxerLock) {
                videoTrack = muxer.addTrack(format)
                maybeStartMuxer()
            }
        }
    }

    companion object {
        private const val TAG = "RadarClipWriter"
    }
}
