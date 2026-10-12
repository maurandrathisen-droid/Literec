package com.literec.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.literec.app.data.RecordConfig
import com.literec.app.data.RecordPreferences
import com.literec.app.data.RecordState
import com.literec.app.ui.MainScreen
import com.literec.app.ui.theme.LiteRecTheme
import com.literec.app.util.VideoFileUtils

class MainActivity : ComponentActivity() {

    private lateinit var prefs: RecordPreferences
    private var currentConfig by mutableStateOf(RecordConfig())
    private var lastSavedPath by mutableStateOf<String?>(null)

    // Media projection consent launcher
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenRecordService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Mic runtime permission launcher
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Microphone permission denied. Recording video-only.", Toast.LENGTH_SHORT).show()
            // Auto update config to mic disabled so user knows
            currentConfig = currentConfig.copy(micAudio = false)
            prefs.saveConfig(currentConfig)
        }
        launchMediaProjectionConsent()
    }

    // Notification permission launcher (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        checkMicAndProceed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = RecordPreferences(this)
        currentConfig = prefs.loadConfig()
        lastSavedPath = prefs.getLastSavedPath()

        setContent {
            LiteRecTheme {
                val state by ScreenRecordService.recordState.collectAsState()

                MainScreen(
                    state = state,
                    config = currentConfig,
                    lastSavedPath = lastSavedPath,
                    onConfigChange = { newConfig ->
                        currentConfig = newConfig
                        prefs.saveConfig(newConfig)
                    },
                    onStartClick = { startRecordingFlow() },
                    onStopClick = { stopRecording() },
                    onOpenVideoClick = { path ->
                        val success = VideoFileUtils.openVideoFile(this, path)
                        if (!success) {
                            Toast.makeText(this, "No video player found", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onShareVideoClick = { path ->
                        val success = VideoFileUtils.shareVideoFile(this, path)
                        if (!success) {
                            Toast.makeText(this, "Unable to share file", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lastSavedPath = prefs.getLastSavedPath()
    }

    private fun startRecordingFlow() {
        // Step 1: Check notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotifPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasNotifPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        checkMicAndProceed()
    }

    private fun checkMicAndProceed() {
        if (currentConfig.micAudio) {
            val hasMicPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasMicPermission) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
        }

        launchMediaProjectionConsent()
    }

    private fun launchMediaProjectionConsent() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = projectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(captureIntent)
    }

    private fun stopRecording() {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        startService(stopIntent)
    }
}