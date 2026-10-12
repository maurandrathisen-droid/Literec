package com.literec.app.data

import android.content.Context
import android.content.SharedPreferences

class RecordPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("literec_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_RESOLUTION_HEIGHT = "pref_res_height"
        private const val KEY_FPS = "pref_fps"
        private const val KEY_BITRATE = "pref_bitrate"
        private const val KEY_MIC_ENABLED = "pref_mic_enabled"
        private const val KEY_LAST_SAVED_PATH = "pref_last_saved_path"
    }

    fun loadConfig(): RecordConfig {
        val resHeight = prefs.getInt(KEY_RESOLUTION_HEIGHT, ResolutionPreset.RES_720P.height)
        val fps = prefs.getInt(KEY_FPS, FpsPreset.FPS_24.fps)
        val bitrate = prefs.getInt(KEY_BITRATE, BitratePreset.LOW.bitrate)
        val mic = prefs.getBoolean(KEY_MIC_ENABLED, true)

        return RecordConfig(
            resolution = ResolutionPreset.fromHeight(resHeight),
            fps = FpsPreset.fromFps(fps),
            bitrate = BitratePreset.fromBitrate(bitrate),
            micAudio = mic
        )
    }

    fun saveConfig(config: RecordConfig) {
        prefs.edit()
            .putInt(KEY_RESOLUTION_HEIGHT, config.resolution.height)
            .putInt(KEY_FPS, config.fps.fps)
            .putInt(KEY_BITRATE, config.bitrate.bitrate)
            .putBoolean(KEY_MIC_ENABLED, config.micAudio)
            .apply()
    }

    fun getLastSavedPath(): String? {
        return prefs.getString(KEY_LAST_SAVED_PATH, null)
    }

    fun setLastSavedPath(path: String?) {
        prefs.edit().putString(KEY_LAST_SAVED_PATH, path).apply()
    }
}