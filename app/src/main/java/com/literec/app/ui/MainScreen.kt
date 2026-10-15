package com.literec.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.literec.app.data.BitratePreset
import com.literec.app.data.FpsPreset
import com.literec.app.data.RecordConfig
import com.literec.app.data.RecordState
import com.literec.app.data.ResolutionPreset
import com.literec.app.ui.theme.*

@Composable
fun MainScreen(
    state: RecordState,
    config: RecordConfig,
    lastSavedPath: String?,
    onConfigChange: (RecordConfig) -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onOpenVideoClick: (String) -> Unit,
    onShareVideoClick: (String) -> Unit
) {
    val scrollState = rememberScrollState()
    val isRecording = state is RecordState.Recording

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BgDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LiteRec",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                StatusBadge(state = state)
            }

            Spacer(modifier = Modifier.height(20.dp))

            val durationSeconds = if (state is RecordState.Recording) state.durationSeconds else 0L
            val mm = durationSeconds / 60
            val ss = durationSeconds % 60
            val timerText = String.format("%02d:%02d", mm, ss)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isRecording) "RECORDING IN PROGRESS" else "RECORDING DURATION",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isRecording) RecordRed else TextSecondary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = timerText,
                        fontSize = 54.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (isRecording) {
                Button(
                    onClick = onStopClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StopBlue,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "STOP RECORDING",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Button(
                    onClick = onStartClick,
                    enabled = state !is RecordState.Saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RecordRed,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "START RECORDING",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceVariantDark, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Tip: Use 480p + 15fps + Low bitrate on weak tablets.",
                    fontSize = 13.sp,
                    color = StatusAmber,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "ENCODER SETTINGS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary,
                letterSpacing = 1.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))

            SettingLabel(text = "Resolution Presets")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ResolutionPreset.entries.forEach { res ->
                    val selected = config.resolution == res
                    SegmentItem(
                        text = res.title,
                        isSelected = selected,
                        enabled = !isRecording,
                        modifier = Modifier.weight(1f)
                    ) {
                        onConfigChange(config.copy(resolution = res))
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            SettingLabel(text = "Frame Rate (FPS)")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FpsPreset.entries.forEach { fps ->
                    val selected = config.fps == fps
                    SegmentItem(
                        text = fps.label,
                        isSelected = selected,
                        enabled = !isRecording,
                        modifier = Modifier.weight(1f)
                    ) {
                        onConfigChange(config.copy(fps = fps))
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            SettingLabel(text = "Video Bitrate")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BitratePreset.entries.forEach { b ->
                    val selected = config.bitrate == b
                    SegmentItem(
                        text = b.label,
                        isSelected = selected,
                        enabled = !isRecording,
                        modifier = Modifier.weight(1f)
                    ) {
                        onConfigChange(config.copy(bitrate = b))
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Internal audio",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Game/app sound only. Android still shows a Microphone prompt — the mic is not recorded.",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = config.micAudio,
                        enabled = !isRecording,
                        onCheckedChange = { checked ->
                            onConfigChange(config.copy(micAudio = checked))
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = RecordRed
                        )
                    )
                }
            }

            val displayedPath = when (state) {
                is RecordState.Saving -> state.outputPath ?: lastSavedPath
                else -> lastSavedPath
            }

            if (!displayedPath.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "LAST RECORDED VIDEO",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = displayedPath,
                            fontSize = 12.sp,
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { onOpenVideoClick(displayedPath) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariantDark)
                            ) {
                                Text("Open Video", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = { onShareVideoClick(displayedPath) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariantDark)
                            ) {
                                Text("Share", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            if (state is RecordState.Error) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1212)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Error: ${state.message}",
                        color = Color(0xFFFF8A80),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun StatusBadge(state: RecordState) {
    val (bgColor, textColor, text) = when (state) {
        is RecordState.Idle -> Triple(SurfaceVariantDark, TextSecondary, "Idle")
        is RecordState.WaitingPermission -> Triple(StatusAmber.copy(alpha = 0.2f), StatusAmber, "Waiting for permission")
        is RecordState.Recording -> Triple(RecordRed.copy(alpha = 0.2f), RecordRed, "Recording")
        is RecordState.Saving -> Triple(StatusGreen.copy(alpha = 0.2f), StatusGreen, "Saving")
        is RecordState.Error -> Triple(StatusRed.copy(alpha = 0.2f), StatusRed, "Error")
    }

    Box(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}

@Composable
fun SettingLabel(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
    )
}

@Composable
fun SegmentItem(
    text: String,
    isSelected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = if (isSelected) RecordRed else SurfaceDark
    val border = if (isSelected) RecordRed else BorderColor
    val textColor = if (isSelected) Color.White else if (enabled) TextPrimary else TextSecondary

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}