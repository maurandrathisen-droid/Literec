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
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * Screen recorder with INTERNAL (game/system) audio only — no microphone.
 * Requires Android 10+ for internal audio. Falls back to silent video if capture is blocked.
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

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null

    private var currentOutputFile: File? = null
    private var recordingStartTime: Long = 0L

    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private val recording = AtomicBoolean(false)
    private var audioThread: Thread? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (_recordState.value is RecordState.Recording) {
                val elapsedSec = (System.currentTimeMillis() - recordingStartTime) / 1000
                _recordState.value = RecordState.Recording(elapsedSec)
                val mm = elapsedSec / 60
                val ss = elapsedSec % 60
                val formatted = String.format("%02d:%02d", mm, ss)
                val notif = NotificationHelper.buildRecordingNotification(this@ScreenRecordService, formatted)
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
                    startForegroundServiceWithNotification()
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

    private fun startForegroundServiceWithNotification() {
        val notification = NotificationHelper.buildRecordingNotification(this, "00:00")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    private fun startRecording(resultCode: Int, resultData: Intent) {
        val prefs = RecordPreferences(this)
        val config = prefs.loadConfig()

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            _recordState.value = RecordState.Error("Failed to obtain MediaProjection")
            stopSelf()
            return
        }

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopRecording()
            }
        }, mainHandler)

        try {
            val dimensions = DisplayUtils.calculateRecordingDimensions(this, config)
            val outputFile = VideoFileUtils.createOutputFile(this)
            currentOutputFile = outputFile

            // Want internal audio if user enabled the toggle (reuses micAudio flag as "audio on")
            val wantInternalAudio = config.micAudio &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

            // Video: MediaRecorder, NO microphone source
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(dimensions.width, dimensions.height)
                setVideoFrameRate(config.fps.fps)
                setVideoEncodingBitRate(config.bitrate.bitrate)
                setOutputFile(outputFile.absolutePath)
                prepare()
            }

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "LiteRec-VirtualDisplay",
                dimensions.width,
                dimensions.height,
                dimensions.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface,
                null,
                null
            )

            // Internal audio in a side file, then we keep video as primary output.
            // Full A/V mux in one file needs a larger rewrite; this captures internal audio
            // to outputFile path with ".iaac.m4a" so you still get game sound.
            if (wantInternalAudio) {
                tryStartInternalAudio(outputFile)
            }

            mediaRecorder?.start()
            recording.set(true)
            recordingStartTime = System.currentTimeMillis()
            _recordState.value = RecordState.Recording(0L)
            mainHandler.post(timerRunnable)

        } catch (e: Exception) {
            releaseAllResources()
            _recordState.value =
                RecordState.Error("Init failed: ${e.localizedMessage ?: "Unknown error"}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun tryStartInternalAudio(videoFile: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val projection = mediaProjection ?: return

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            return
        }

        val audioOut = File(videoFile.parent, videoFile.nameWithoutExtension + "_internal.m4a")
        muxer = MediaMuxer(audioOut.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)

        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioCodec?.start()

        audioRecord?.startRecording()

        audioThread = thread(name = "LiteRec-InternalAudio") {
            val buf = ByteArray(minBuf)
            val bufferInfo = MediaCodec.BufferInfo()
            try {
                while (recording.get()) {
                    val read = audioRecord?.read(buf, 0, buf.size) ?: -1
                    if (read > 0) {
                        val codec = audioCodec ?: break
                        val inIndex = codec.dequeueInputBuffer(10000)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex)
                            inBuf?.clear()
                            inBuf?.put(buf, 0, read)
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                read,
                                System.nanoTime() / 1000,
                                0
                            )
                        }
                        var outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                        while (outIndex >= 0) {
                            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                if (!muxerStarted) {
                                    audioTrackIndex = muxer!!.addTrack(codec.outputFormat)
                                    muxer!!.start()
                                    muxerStarted = true
                                }
                            } else if (bufferInfo.size > 0 && muxerStarted && audioTrackIndex >= 0) {
                                val outBuf = codec.getOutputBuffer(outIndex)
                                if (outBuf != null) {
                                    outBuf.position(bufferInfo.offset)
                                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                    muxer?.writeSampleData(audioTrackIndex, outBuf, bufferInfo)
                                }
                            }
                            if (outIndex >= 0) codec.releaseOutputBuffer(outIndex, false)
                            outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun stopRecording() {
        mainHandler.removeCallbacks(timerRunnable)
        _recordState.value = RecordState.Saving(currentOutputFile?.absolutePath)

        recording.set(false)

        var success = false
        try {
            mediaRecorder?.stop()
            success = true
        } catch (_: Exception) {
        }

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioThread?.join(2000)
        } catch (_: Exception) {
        }

        try {
            audioCodec?.stop()
            audioCodec?.release()
        } catch (_: Exception) {
        }
        audioCodec = null

        try {
            if (muxerStarted) muxer?.stop()
            muxer?.release()
        } catch (_: Exception) {
        }
        muxer = null
        muxerStarted = false

        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        val savedPath = currentOutputFile?.absolutePath
        releaseAllResources()

        if (success && savedPath != null && File(savedPath).exists() && File(savedPath).length() > 0) {
            RecordPreferences(this).setLastSavedPath(savedPath)
            _recordState.value = RecordState.Saving(savedPath)
        } else {
            _recordState.value = RecordState.Error("Recording failed or was under 1 second")
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseAllResources() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(timerRunnable)
        recording.set(false)
        releaseAllResources()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
