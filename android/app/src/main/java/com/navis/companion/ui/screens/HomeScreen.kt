package com.navis.companion.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navis.companion.ui.components.AudioWaveform
import com.navis.companion.ui.theme.HazardCaution
import com.navis.companion.ui.theme.NavisBlack
import com.navis.companion.ui.theme.NavisDeepBlack
import com.navis.companion.ui.theme.NavisLightGrey
import com.navis.companion.ui.theme.NavisTextSecondary
import com.navis.companion.ui.theme.NavisWhite
import com.navis.companion.ui.theme.StatusConnected
import com.navis.companion.ui.viewmodel.NavisUiState

/**
 * Screen 2: Home Dashboard matching the exact pixel-perfect design in the mockup.
 * TalkBack-ready, voice-first companion with clear hardware telemetry.
 */
@Composable
fun HomeScreen(
    uiState: NavisUiState,
    onOpenVoiceAssistant: () -> Unit,
    onOpenLiveAssist: () -> Unit,
    onOpenSceneDescription: () -> Unit,
    onOpenReadText: () -> Unit,
    onOpenNavigation: () -> Unit,
    onRepeatLastAlert: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        // 1. Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "N A V I S",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() }
            )

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.semantics { contentDescription = "Open Settings" }
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        // 2. Device Status Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "NAVIS backend ${if (uiState.isBackendConnected) "connected" else "disconnected"}. Camera ${if (uiState.isCameraActive) "active" else "offline"}. Distance sensor ${if (uiState.distanceSensorActive) "active" else if (uiState.distanceSensorMode == "auto" || uiState.distanceSensorMode == "disabled") "optional and offline" else "offline"}. AI model ${if (uiState.isAiOnline) "loaded" else "unavailable"}. Phone location permission ${if (uiState.isGpsReady) "granted" else "needed"}."
                }
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Top pill: Backend Status
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(NavisBlack),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = null,
                                    tint = NavisWhite,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "NAVIS Backend",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (uiState.isBackendConnected) "Connected • ${uiState.serverUrl}" else "Disconnected",
                                    fontSize = 11.sp,
                                    color = NavisTextSecondary
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (uiState.isBackendConnected) StatusConnected else Color.Red)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 4 Sub-metrics grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricMiniCard(
                        icon = Icons.Default.CameraAlt,
                        title = "Camera",
                        subtitle = if (uiState.isCameraActive) "Active" else "Offline",
                        modifier = Modifier.weight(1f)
                    )
                    MetricMiniCard(
                        icon = Icons.Default.Sensors,
                        title = "Distance Sensor",
                        subtitle = when {
                            uiState.distanceSensorActive -> "Active\n${uiState.currentDistanceM?.let { "%.1f m".format(it) } ?: "No reading"}"
                            uiState.distanceSensorMode == "auto" || uiState.distanceSensorMode == "disabled" -> "Optional\nNo range data"
                            else -> "Offline\nNo reading"
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricMiniCard(
                        icon = Icons.Default.Memory,
                        title = "AI Processing",
                        subtitle = if (uiState.isAiOnline) "Online" else "Offline",
                        modifier = Modifier.weight(1f)
                    )
                    MetricMiniCard(
                        icon = Icons.Default.LocationOn,
                        title = "GPS",
                        subtitle = if (uiState.isGpsReady) "Permission ready" else "Permission needed",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Main Voice Interaction Card (Dark `#111111`) with subtle wave contour background
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = NavisDeepBlack),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenVoiceAssistant() }
                .semantics {
                    contentDescription = "Voice Assistant button. Tap to open NAVIS, hear a greeting, and ask for help."
                }
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Subtle fluid wave contours in card background
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    for (i in 0..3) {
                        val path = Path().apply {
                            moveTo(0f, h * 0.45f + i * 20f)
                            cubicTo(
                                w * 0.35f, h * 0.3f + i * 25f,
                                w * 0.65f, h * 0.75f - i * 15f,
                                w, h * 0.5f + i * 20f
                            )
                        }
                        drawPath(
                            path = path,
                            color = Color.White.copy(alpha = 0.04f + i * 0.02f),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Hello\nI'm NAVIS",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = NavisWhite,
                        textAlign = TextAlign.Center,
                        lineHeight = 28.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = when {
                            uiState.awaitingWakeCommand -> "I'm listening. Ask a question or say\n\"open Read Text\" / \"close it\""
                            uiState.isWakeWordListening -> "Say \"Hello NAVIS\" + a question or\n\"open [feature]\" / \"close it\""
                            uiState.wakeWordEnabled -> "Wake listening is enabled\nTap the mic or press Volume Down"
                            else -> "Tap the mic or press Volume Down\nWake word is off"
                        },
                        fontSize = 13.sp,
                        color = NavisTextSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Animated Center Waveform
                    AudioWaveform(
                        modifier = Modifier.fillMaxWidth(0.70f),
                        barCount = 16,
                        color = NavisWhite,
                        height = 36.dp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // 4. Quick Actions (4 compact action cards matching mockup)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickActionButton(
                icon = Icons.Default.CameraAlt,
                label = "Describe\nSurroundings",
                onClick = onOpenSceneDescription,
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                icon = Icons.Default.Description,
                label = "Read\nText",
                onClick = onOpenReadText,
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                icon = Icons.Default.Navigation,
                label = "Navigate",
                onClick = onOpenNavigation,
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                icon = Icons.Default.Replay,
                label = "Repeat\nLast",
                onClick = onRepeatLastAlert,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 5. Recent Activity / Alert Banner
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onRepeatLastAlert() }
                .semantics {
                    contentDescription = "Recent hazard alert: ${uiState.latestHazardMessage}. Tap to hear aloud."
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(HazardCaution.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = HazardCaution,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Recent Alert",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = NavisTextSecondary
                    )
                    Text(
                        text = uiState.latestHazardMessage,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(onClick = onRepeatLastAlert) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Read alert aloud",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
    }
}

@Composable
fun MetricMiniCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = NavisTextSecondary
                )
            }
        }
    }
}

@Composable
fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .height(96.dp)
            .clickable { onClick() }
            .semantics {
                contentDescription = label.replace("\n", " ")
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 13.sp
            )
        }
    }
}
