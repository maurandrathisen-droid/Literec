package com.literec.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import androidx.core.content.ContextCompat
import com.literec.app.data.RecordPreferences
import com.literec.app.data.RecordState
import com.literec.app.util.DisplayUtils
import com.literec.app.util.NotificationHelper
import com.literec.app.util.VideoFileUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Screen + internal playback audio in one MP4.
 * Does not open the microphone. Android still requires RECORD_AUDIO
 * for AudioPlaybackCapture, so the permission dialog says "Microphone".
 */
class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.literec.app.action.START"
        const val ACTION_STOP = "com.literec.app.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private val _recordState = MutableStateFlow<RecordState>(RecordState.Idle)
        val recordState: StateFlow<RecordState> = _recordState.asStateFlow()
    }

    private data class PendingSample(
        val isVideo: Boolean,
        val data: ByteArray,
        val ptsUs: Long,
        val flags: Int
    )

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoCodec: MediaCodec? = null
    private var videoSurface: Surface? = null
    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null

    private var currentOutputFile: File? = null
    private var recordingStartTime: Long = 0L
    private var baseNs: Long = 0L

    private val recording = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val wantAudio = AtomicBoolean(false)
    private val audioFailed = AtomicBoolean(false)

    private val muxerLock = Any()
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var sawVideoFormat = false
    private var sawAudioFormat = false
    private val pending = ArrayList<PendingSample>()

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private var audioSampleRate = 44100
    private var audioChannels = 1
    private var audioPcmFrames = 0L
    private var videoPtsOffset = -1L
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (_recordState.value is RecordState.Recording) {
                val elapsedSec = (System.currentTimeMillis() - recordingStartTime) / 1000
                _recordState.value = RecordState.Recording(elapsedSec)
                val mm = elapsedSec / 60
                val ss = elapsedSec % 60
                val formatted = String.format("%02d:%02d", mm, ss)
                val notif = NotificationHelper.buildRecordingNotification(
                    this@ScreenRecordService,
                    formatted
                )
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(NotificationHelper.NOTIFICATION_ID, notif)
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode != 0 && resultData != null) {
                    startRecording(resultCode, resultData)
                } else {
                    _recordState.value = RecordState.Error("Screen capture permission missing")
                    stopSelf()
                }
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun promoteForeground(includeMicType: Boolean) {
        val notification = NotificationHelper.buildRecordingNotification(this, "00:00")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (includeMicType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NotificationHelper.NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    private fun startRecording(resultCode: Int, resultData: Intent) {
        val config = RecordPreferences(this).loadConfig()
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        var captureInternal = config.micAudio &&
            hasAudioPermission &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        try {
            promoteForeground(includeMicType = captureInternal)
        } catch (_: Exception) {
            try {
                promoteForeground(includeMicType = false)
            } catch (e: Exception) {
                _recordState.value = RecordState.Error("Foreground start failed: ${e.localizedMessage ?: "Unknown"}")
                stopSelf()
                return
            }
            captureInternal = false
            audioFailed.set(true)
        }

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        if (mediaProjection == null) {
            _recordState.value = RecordState.Error("Failed to obtain MediaProjection")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { stopRecording() }
            }
        }, mainHandler)

        try {
            val dimensions = DisplayUtils.calculateRecordingDimensions(this, config)
            val outputFile = VideoFileUtils.createOutputFile(this)
            currentOutputFile = outputFile
            baseNs = System.nanoTime()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                dimensions.width,
                dimensions.height
            )
            videoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate.bitrate)
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps.fps)
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            val frameGapUs = (1_000_000L / config.fps.fps.coerceAtLeast(1)).coerceAtLeast(16_666L)
            videoFormat.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, frameGapUs)

            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoCodec?.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            videoSurface = videoCodec?.createInputSurface()
            videoCodec?.start()

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "LiteRec",
                dimensions.width,
                dimensions.height,
                dimensions.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                videoSurface,
                null,
                null
            )

            if (captureInternal) {
                if (!startInternalAudio()) {
                    audioFailed.set(true)
                }
            }

            recording.set(true)
            stopping.set(false)
            recordingStartTime = System.currentTimeMillis()

            videoThread = thread(name = "LiteRec-Video") {
                drainUntilEos(videoCodec, isVideo = true)
            }
            if (audioRecord != null && audioCodec != null) {
                audioThread = thread(name = "LiteRec-Audio") {
                    pumpAudio()
                }
            }

            _recordState.value = RecordState.Recording(0L)
            mainHandler.post(timerRunnable)
        } catch (e: Exception) {
            releaseAll()
            _recordState.value = RecordState.Error("Init failed: ${e.localizedMessage ?: "Unknown"}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startInternalAudio(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val projection = mediaProjection ?: return false
        val record = openPlaybackRecord(projection) ?: return false
        audioRecord = record
        return try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                audioSampleRate,
                audioChannels
            )
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioCodec = codec
            record.startRecording()
            wantAudio.set(true)
            true
        } catch (_: Exception) {
            try {
                record.release()
            } catch (_: Exception) {
            }
            audioRecord = null
            false
        }
    }

    private fun openPlaybackRecord(projection: MediaProjection): AudioRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val usageSets = listOf(
            intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN),
            intArrayOf(AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_MEDIA),
            intArrayOf(AudioAttributes.USAGE_MEDIA),
            intArrayOf(AudioAttributes.USAGE_GAME)
        )
        val rates = intArrayOf(48000, 44100)
        val channels = intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)
        for (usages in usageSets) {
            val cfg = try {
                val b = AudioPlaybackCaptureConfiguration.Builder(projection)
                for (u in usages) b.addMatchingUsage(u)
                b.build()
            } catch (_: Exception) {
                continue
            }
            for (rate in rates) {
                for (mask in channels) {
                    val min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                    if (min <= 0) continue
                    try {
                        val rec = AudioRecord.Builder()
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(rate)
                                    .setChannelMask(mask)
                                    .build()
                            )
                            .setBufferSizeInBytes(min * 2)
                            .setAudioPlaybackCaptureConfig(cfg)
                            .build()
                        if (rec.state == AudioRecord.STATE_INITIALIZED) {
                            audioSampleRate = rate
                            audioChannels = if (mask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
                            return rec
                        }
                        rec.release()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return null
    }

    private fun pumpAudio() {
        val record = audioRecord ?: return
        val codec = audioCodec ?: return
        val min = AudioRecord.getMinBufferSize(
            audioSampleRate,
            if (audioChannels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)
        val buf = ByteArray(min)
        try {
            while (recording.get() && !stopping.get()) {
                val read = record.read(buf, 0, buf.size)
                if (read > 0) {
                    queueAudioPcm(codec, buf, read)
                    drainEncoder(codec, isVideo = false, waitEos = false)
                } else if (read == 0) {
                    drainEncoder(codec, isVideo = false, waitEos = false)
                    try {
                        Thread.sleep(8)
                    } catch (_: InterruptedException) {
                        break
                    }
                } else {
                    audioFailed.set(true)
                    break
                }
            }
            queueAudioEos(codec)
            drainEncoder(codec, isVideo = false, waitEos = true)
        } catch (_: Exception) {
            audioFailed.set(true)
        }
    }

    private fun queueAudioPcm(codec: MediaCodec, buf: ByteArray, size: Int) {
        var index = codec.dequeueInputBuffer(20_000)
        if (index < 0) index = codec.dequeueInputBuffer(80_000)
        if (index < 0) return
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        val toWrite = size.coerceAtMost(input.capacity())
        input.put(buf, 0, toWrite)
        val frames = toWrite / (2 * audioChannels.coerceAtLeast(1))
        val pts = audioPcmFrames * 1_000_000L / audioSampleRate.coerceAtLeast(1)
        audioPcmFrames += frames
        codec.queueInputBuffer(index, 0, toWrite, pts, 0)
    }

    private fun queueAudioEos(codec: MediaCodec) {
        val index = codec.dequeueInputBuffer(20_000)
        if (index >= 0) {
            val pts = audioPcmFrames * 1_000_000L / audioSampleRate.coerceAtLeast(1)
            codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    private fun drainUntilEos(codec: MediaCodec?, isVideo: Boolean) {
        if (codec == null) return
        val info = MediaCodec.BufferInfo()
        var idleAfterStop = 0
        while (true) {
            val index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (_: Exception) {
                break
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (stopping.get()) {
                        idleAfterStop++
                        if (idleAfterStop > 50) break
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onFormat(isVideo, codec.outputFormat)
                }
                index >= 0 -> {
                    idleAfterStop = 0
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val configBuf = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!configBuf && info.size > 0) {
                        val encoded = codec.getOutputBuffer(index)
                        if (encoded != null) {
                            writeSample(isVideo, encoded, info)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (eos) break
                }
            }
        }
    }

    private fun drainEncoder(codec: MediaCodec, isVideo: Boolean, waitEos: Boolean) {
        val info = MediaCodec.BufferInfo()
        var idle = 0
        while (true) {
            val index = try {
                codec.dequeueOutputBuffer(info, if (waitEos) 10_000 else 0)
            } catch (_: Exception) {
                return
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!waitEos) return
                    idle++
                    if (idle > 15) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(isVideo, codec.outputFormat)
                index >= 0 -> {
                    idle = 0
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val configBuf = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!configBuf && info.size > 0) {
                        val encoded = codec.getOutputBuffer(index)
                        if (encoded != null) writeSample(isVideo, encoded, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (eos || !waitEos) return
                }
            }
        }
    }

    private fun onFormat(isVideo: Boolean, format: MediaFormat) {
        synchronized(muxerLock) {
            val m = muxer ?: return
            if (isVideo) {
                if (sawVideoFormat || muxerStarted) return
                videoTrack = m.addTrack(format)
                sawVideoFormat = true
            } else {
                if (sawAudioFormat || muxerStarted) return
                audioTrack = m.addTrack(format)
                sawAudioFormat = true
            }
            tryStartMuxerLocked()
        }
    }

    private fun writeSample(isVideo: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(bytes)
        val relPts = if (isVideo) rebaseVideoPts(info.presentationTimeUs) else info.presentationTimeUs
        synchronized(muxerLock) {
            abandonAudioIfLateLocked()
            if (!muxerStarted) {
                pending.add(PendingSample(isVideo, bytes, relPts, info.flags))
                tryStartMuxerLocked()
                return
            }
            val track = if (isVideo) videoTrack else audioTrack
            if (track < 0) {
                pending.add(PendingSample(isVideo, bytes, relPts, info.flags))
                return
            }
            flushPendingLocked()
            writeNowLocked(track, isVideo, bytes, relPts, info.flags)
        }
    }

    private fun rebaseVideoPts(raw: Long): Long {
        if (videoPtsOffset < 0) videoPtsOffset = raw.coerceAtLeast(0)
        return (raw - videoPtsOffset).coerceAtLeast(0)
    }

    private fun abandonAudioIfLateLocked() {
        if (!wantAudio.get() || sawAudioFormat || audioFailed.get()) return
        if (System.nanoTime() - baseNs > 8_000_000_000L) {
            wantAudio.set(false)
            audioFailed.set(true)
        }
    }

    private fun tryStartMuxerLocked() {
        val m = muxer ?: return
        if (muxerStarted || !sawVideoFormat) return
        abandonAudioIfLateLocked()
        val audioReady = !wantAudio.get() || sawAudioFormat || audioFailed.get()
        if (!audioReady) return
        m.start()
        muxerStarted = true
        flushPendingLocked()
    }

    private fun flushPendingLocked() {
        if (!muxerStarted) return
        val left = ArrayList<PendingSample>()
        for (sample in pending) {
            val track = if (sample.isVideo) videoTrack else audioTrack
            if (track < 0) {
                if (sample.isVideo || wantAudio.get()) left.add(sample)
            } else {
                writeNowLocked(track, sample.isVideo, sample.data, sample.ptsUs, sample.flags)
            }
        }
        pending.clear()
        pending.addAll(left)
    }

    private fun writeNowLocked(track: Int, isVideo: Boolean, data: ByteArray, ptsUs: Long, flags: Int) {
        val m = muxer ?: return
        var safePts = if (ptsUs < 0) 0 else ptsUs
        if (isVideo) {
            if (safePts <= lastVideoPts) safePts = lastVideoPts + 1
            lastVideoPts = safePts
        } else {
            if (safePts <= lastAudioPts) safePts = lastAudioPts + 1
            lastAudioPts = safePts
        }
        val info = MediaCodec.BufferInfo()
        info.set(0, data.size, safePts, flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
        try {
            m.writeSampleData(track, ByteBuffer.wrap(data), info)
        } catch (_: Exception) {
        }
    }

    private fun stopRecording() {
        if (!stopping.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(timerRunnable)
        val path = currentOutputFile?.absolutePath
        _recordState.value = RecordState.Saving(path)
        recording.set(false)

        try {
            videoCodec?.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioThread?.join(4000)
        } catch (_: Exception) {
        }
        try {
            videoThread?.join(4000)
        } catch (_: Exception) {
        }

        synchronized(muxerLock) {
            if (!muxerStarted && sawVideoFormat) {
                wantAudio.set(false)
                tryStartMuxerLocked()
            }
        }

        var success = false
        try {
            if (muxerStarted) muxer?.stop()
            success = true
        } catch (_: Exception) {
        }

        releaseAll()

        val file = path?.let { File(it) }
        if (success && file != null && file.exists() && file.length() > 0) {
            RecordPreferences(this).setLastSavedPath(path)
            if (audioFailed.get()) {
                _recordState.value = RecordState.Error(
                    "Saved video, but this app blocked internal audio. Try YouTube sound first. File: $path"
                )
            } else {
                _recordState.value = RecordState.Saving(path)
            }
        } else {
            _recordState.value = RecordState.Error("Recording failed or was under 1 second")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseAll() {
        recording.set(false)
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            videoSurface?.release()
        } catch (_: Exception) {
        }
        videoSurface = null
        try {
            videoCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            videoCodec?.release()
        } catch (_: Exception) {
        }
        videoCodec = null
        try {
            audioCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            audioCodec?.release()
        } catch (_: Exception) {
        }
        audioCodec = null
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        muxer = null
        muxerStarted = false
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(timerRunnable)
        recording.set(false)
        stopping.set(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
