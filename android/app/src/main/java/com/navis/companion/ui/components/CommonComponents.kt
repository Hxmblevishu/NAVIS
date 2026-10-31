package com.navis.companion.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navis.companion.ui.theme.NavisBlack
import com.navis.companion.ui.theme.NavisTextSecondary
import com.navis.companion.ui.theme.NavisWhite
import com.navis.companion.ui.theme.StatusConnected
import kotlin.math.sin

/**
 * Animated audio waveform visualizer for listening/speaking states.
 */
@Composable
fun AudioWaveform(
    modifier: Modifier = Modifier,
    barCount: Int = 18,
    color: Color = NavisWhite,
    isAnimated: Boolean = true,
    height: Dp = 48.dp
) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(
        modifier = modifier
            .height(height)
            .semantics { contentDescription = "Active voice audio waveform indicator" }
    ) {
        val width = size.width
        val canvasHeight = size.height
        val barWidth = width / (barCount * 1.8f)
        val gap = barWidth * 0.8f

        for (i in 0 until barCount) {
            val progress = i.toFloat() / barCount
            val ampMultiplier = if (isAnimated) {
                0.25f + 0.75f * ((sin(phase + progress * 4.5).toFloat() + 1f) / 2f)
            } else {
                0.2f
            }
            val barHeight = (canvasHeight * 0.9f * ampMultiplier).coerceAtLeast(6f)
            val startX = i * (barWidth + gap) + gap
            val startY = (canvasHeight - barHeight) / 2f

            drawLine(
                color = color,
                start = Offset(startX, startY),
                end = Offset(startX, startY + barHeight),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Concentric pulsing rings for wake word ("Hello NAVIS") listening state (Screen 3).
 */
@Composable
fun PulsingCircularRadar(
    modifier: Modifier = Modifier,
    color: Color = NavisWhite
) {
    val transition = rememberInfiniteTransition(label = "radar")
    val pulse1 by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse1"
    )
    val pulse2 by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, delayMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse2"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(260.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val maxRadius = size.width / 2

            // Outer ring 1
            drawCircle(
                color = color.copy(alpha = (1f - pulse1) * 0.45f),
                radius = maxRadius * pulse1,
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )
            // Outer ring 2
            drawCircle(
                color = color.copy(alpha = (1f - pulse2) * 0.35f),
                radius = maxRadius * pulse2,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
            // Inner core ring
            drawCircle(
                color = color.copy(alpha = 0.15f),
                radius = maxRadius * 0.48f,
                center = center
            )
        }

        // Center Waveform Icon
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(NavisBlack),
            contentAlignment = Alignment.Center
        ) {
            AudioWaveform(
                modifier = Modifier.padding(16.dp),
                barCount = 10,
                color = NavisWhite,
                height = 36.dp
            )
        }
    }
}

/**
 * Soft radiating concentric circular waveform for "NAVIS Speaking..." (Screen 4).
 */
@Composable
fun RadiatingSpeechWave(
    modifier: Modifier = Modifier,
    isSpeaking: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "speech_echo")
    val waveScale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wave_scale"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(240.dp)) {
            val center = Offset(size.width / 2, size.height / 2)

            // Ring 3 (Outer most soft grey)
            drawCircle(
                color = Color.LightGray.copy(alpha = 0.20f),
                radius = (110.dp.toPx()) * (if (isSpeaking) waveScale else 1f),
                center = center
            )
            // Ring 2 (Middle grey)
            drawCircle(
                color = Color.LightGray.copy(alpha = 0.35f),
                radius = 85.dp.toPx(),
                center = center
            )
            // Ring 1 (Inner dark ring)
            drawCircle(
                color = Color.DarkGray.copy(alpha = 0.25f),
                radius = 65.dp.toPx(),
                center = center
            )
        }

        // Center Black Avatar Disc with White Waveform
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(NavisBlack),
            contentAlignment = Alignment.Center
        ) {
            AudioWaveform(
                modifier = Modifier.padding(14.dp),
                barCount = 10,
                color = NavisWhite,
                isAnimated = isSpeaking,
                height = 32.dp
            )
        }
    }
}

/**
 * Stylized Eye Logo representing NAVIS: "See Beyond Limits"
 */
@Composable
fun NavisEyeLogo(
    modifier: Modifier = Modifier,
    size: Dp = 68.dp,
    tint: Color = NavisBlack
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tint),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.6f)) {
            val w = this.size.width
            val h = this.size.height

            // Top arc of the eye
            val topPath = Path().apply {
                moveTo(0f, h * 0.5f)
                cubicTo(w * 0.25f, 0f, w * 0.75f, 0f, w, h * 0.5f)
                cubicTo(w * 0.75f, h, w * 0.25f, h, 0f, h * 0.5f)
                close()
            }
            drawPath(
                path = topPath,
                color = NavisWhite,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )

            // Center pupil circle
            drawCircle(
                color = NavisWhite,
                radius = w * 0.20f,
                center = Offset(w * 0.5f, h * 0.5f)
            )
            // Inner aperture dot
            drawCircle(
                color = NavisBlack,
                radius = w * 0.08f,
                center = Offset(w * 0.5f, h * 0.5f)
            )
        }
    }
}

/**
 * Persistent Bottom Navigation Bar with 4 core destinations.
 * TalkBack and high-contrast accessible.
 */
@Composable
fun NavisBottomBar(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp
        ) {
            val items = listOf(
                NavigationItem("home", "Home", Icons.Default.Home),
                NavigationItem("live_assist", "Live Assist", Icons.Default.CameraAlt),
                NavigationItem("navigate", "Navigate", Icons.Default.Navigation),
                NavigationItem("history", "History", Icons.Default.History)
            )

            items.forEach { item ->
                val selected = currentRoute == item.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onNavigate(item.route) },
                    icon = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = "${item.label} screen",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = {
                        Text(
                            text = item.label,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSurface,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                        unselectedIconColor = NavisTextSecondary,
                        unselectedTextColor = NavisTextSecondary
                    )
                )
            }
        }
    }
}

data class NavigationItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

/**
 * Status indicator pill (e.g. "ESP32-CAM Connected • 192.168.1.45").
 */
@Composable
fun DeviceConnectionBadge(
    title: String = "ESP32-CAM",
    ipAddress: String = "192.168.1.45",
    isConnected: Boolean = true
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) StatusConnected else Color.Red)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isConnected) "$title • $ipAddress" else "$title • Disconnected",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
