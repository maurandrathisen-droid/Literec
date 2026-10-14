package com.literec.app.data

enum class ResolutionPreset(val title: String, val width: Int, val height: Int) {
    RES_360P("360p", 640, 360),
    RES_480P("480p (SD)", 854, 480),
    RES_720P("720p (HD)", 1280, 720),
    RES_1080P("1080p (FHD)", 1920, 1080);

    companion object {
        fun fromHeight(height: Int): ResolutionPreset {
            return entries.find { it.height == height } ?: RES_720P
        }
    }
}

enum class FpsPreset(val fps: Int, val label: String) {
    FPS_15(15, "15"),
    FPS_24(24, "24"),
    FPS_30(30, "30"),
    FPS_40(40, "40"),
    FPS_48(48, "48"),
    FPS_60(60, "60");

    companion object {
        fun fromFps(fps: Int): FpsPreset {
            return entries.find { it.fps == fps } ?: FPS_24
        }
    }
}

enum class BitratePreset(val bitrate: Int, val label: String) {
    VERY_LOW(500_000, "0.5M"),
    LOW(1_000_000, "1.0M"),
    MEDIUM(2_500_000, "2.5M"),
    HIGH(5_000_000, "5.0M"),
    ULTRA(8_000_000, "8.0M");

    companion object {
        fun fromBitrate(bitrate: Int): BitratePreset {
            return entries.find { it.bitrate == bitrate } ?: LOW
        }
    }
}

data class RecordConfig(
    val resolution: ResolutionPreset = ResolutionPreset.RES_480P,
    val fps: FpsPreset = FpsPreset.FPS_24,
    val bitrate: BitratePreset = BitratePreset.LOW,
    // Reused as "audio on" — internal audio path when service supports it
    val micAudio: Boolean = true
)

sealed class RecordState {
    data object Idle : RecordState()
    data object WaitingPermission : RecordState()
    data class Recording(val durationSeconds: Long) : RecordState()
    data class Saving(val outputPath: String?) : RecordState()
    data class Error(val message: String) : RecordState()
}
