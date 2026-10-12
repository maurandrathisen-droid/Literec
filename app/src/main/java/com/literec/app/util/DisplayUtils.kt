package com.literec.app.util

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.literec.app.data.RecordConfig
import kotlin.math.min

data class VideoDimensions(
    val width: Int,
    val height: Int,
    val densityDpi: Int
)

object DisplayUtils {
    fun calculateRecordingDimensions(context: Context, config: RecordConfig): VideoDimensions {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        val displayWidth: Int
        val displayHeight: Int
        val densityDpi: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            displayWidth = bounds.width()
            displayHeight = bounds.height()
            densityDpi = context.resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            displayWidth = metrics.widthPixels
            displayHeight = metrics.heightPixels
            densityDpi = metrics.densityDpi
        }

        val isLandscape = displayWidth > displayHeight
        val targetShortSide = config.resolution.height
        val targetLongSide = config.resolution.width

        val (rawTargetWidth, rawTargetHeight) = if (isLandscape) {
            Pair(targetLongSide, targetShortSide)
        } else {
            Pair(targetShortSide, targetLongSide)
        }

        val scale = min(
            1.0,
            min(
                rawTargetWidth.toDouble() / displayWidth.toDouble(),
                rawTargetHeight.toDouble() / displayHeight.toDouble()
            )
        )

        var finalWidth = (displayWidth * scale).toInt()
        var finalHeight = (displayHeight * scale).toInt()

        if (finalWidth % 2 != 0) finalWidth--
        if (finalHeight % 2 != 0) finalHeight--

        finalWidth = finalWidth.coerceAtLeast(16)
        finalHeight = finalHeight.coerceAtLeast(16)

        return VideoDimensions(
            width = finalWidth,
            height = finalHeight,
            densityDpi = densityDpi
        )
    }
}