package com.literec.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.literec.app.data.RecordConfig
import com.literec.app.data.RecordPreferences
import com.literec.app.data.RecordState
import com.literec.app.util.DisplayUtils
import com.literec.app.util.NotificationHelper
import com.literec.app.util.VideoFileUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.literec.app.action.START"
        const val ACTION_STOP = "com.literec.app.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private val _recordState = MutableStateFlow<RecordState>(RecordState.Idle)
        val recordState: StateFlow<RecordState> = _recordState.asStateFlow()

        fun isRecording(): Boolean {
            return _recordState.value is RecordState.Recording
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null

    private var currentOutputFile: File? = null
    private var recordingStartTime: Long = 0L

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
        val action = intent?.action

        when (action) {
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
                    _recordState.value = RecordState.Error("Media projection permission missing")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopRecording()
            }
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

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
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

            val hasMicPermission = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            val recordMic = config.micAudio && hasMicPermission

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                if (recordMic) {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                }
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

                if (recordMic) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(64_000)
                    setAudioSamplingRate(44100)
                    setAudioChannels(1)
                }

                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(dimensions.width, dimensions.height)
                setVideoFrameRate(config.fps.fps)
                setVideoEncodingBitRate(config.bitrate.bitrate)
                setOutputFile(outputFile.absolutePath)

                prepare()
            }

            val recorderSurface = mediaRecorder?.surface
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "LiteRec-VirtualDisplay",
                dimensions.width,
                dimensions.height,
                dimensions.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorderSurface,
                null,
                null
            )

            mediaRecorder?.start()
            recordingStartTime = System.currentTimeMillis()
            _recordState.value = RecordState.Recording(0L)
            mainHandler.post(timerRunnable)

        } catch (e: Exception) {
            releaseAllResources()
            _recordState.value = RecordState.Error("Init failed: ${e.localizedMessage ?: "Unknown error"}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        mainHandler.removeCallbacks(timerRunnable)
        _recordState.value = RecordState.Saving(currentOutputFile?.absolutePath)

        var success = false
        try {
            mediaRecorder?.stop()
            success = true
        } catch (_: Exception) {}

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
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(timerRunnable)
        releaseAllResources()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}