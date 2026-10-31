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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navis.companion.ui.components.AudioWaveform
import com.navis.companion.ui.components.RadiatingSpeechWave
import com.navis.companion.ui.theme.HazardCritical
import com.navis.companion.ui.theme.NavisBlack
import com.navis.companion.ui.theme.NavisDeepBlack
import com.navis.companion.ui.theme.NavisLightGrey
import com.navis.companion.ui.theme.NavisTextSecondary
import com.navis.companion.ui.theme.NavisWhite
import com.navis.companion.ui.viewmodel.ChatMessage
import com.navis.companion.ui.viewmodel.NavisUiState
import com.navis.companion.ui.viewmodel.VoiceState

/**
 * Screens 4 & 5: Conversational Voice Assistant matching mockups 4 and 5.
 * Displays the radiating "Agent Responds" hero state initially, and transitions
 * seamlessly to the active voice command chat history with embedded map previews.
 */
@Composable
fun VoiceAssistantScreen(
    uiState: NavisUiState,
    onBack: () -> Unit,
    onMicClick: () -> Unit,
    onStopClick: () -> Unit,
    onSuggestedCommand: (String) -> Unit,
    onResponseStyleChange: (String) -> Unit,
    onStartNavigation: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val isInitialGreeting = uiState.conversation.isEmpty()
    val conversationListState = rememberLazyListState()

    LaunchedEffect(uiState.conversation.size, uiState.conversation.lastOrNull()?.text) {
        if (uiState.conversation.isNotEmpty()) {
            // Keep the live response visible as Gemini appends streamed text.
            conversationListState.scrollToItem(uiState.conversation.size + 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 1. Top Bar matching mockup
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "Back to home" }
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "N A V I S",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = when (uiState.voiceState) {
                        VoiceState.LISTENING -> "Listening…"
                        VoiceState.PROCESSING -> "Processing…"
                        VoiceState.SPEAKING -> "Speaking…"
                        VoiceState.ERROR -> "Try again"
                        else -> if (uiState.voiceOutputAvailable == false) "Audio unavailable" else "Online"
                    },
                    fontSize = 11.sp,
                    color = NavisTextSecondary
                )
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.semantics { contentDescription = "Open Settings" }
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        ResponseStylePicker(
            selectedStyle = uiState.responseStyle,
            onSelect = onResponseStyleChange
        )

        if (isInitialGreeting) {
            // Screen 4: Agent Responds Layout
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                // Large Radiating Speech Wave
                RadiatingSpeechWave(
                    isSpeaking = uiState.voiceState == VoiceState.SPEAKING || uiState.voiceState == VoiceState.IDLE
                )

                // Greeting Bubble Card
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Hello! I'm NAVIS.",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Your navigation and assistance companion.",
                                fontSize = 13.sp,
                                color = NavisTextSecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "How can I help you today?",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (uiState.voiceOutputAvailable == false) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Speech output is unavailable. Check the device's Text-to-Speech engine and media volume.",
                                    fontSize = 12.sp,
                                    color = NavisTextSecondary
                                )
                            }
                        }
                    }
                }

                // Suggested Command Grid
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SuggestedChip(
                            label = "Take me to the library",
                            modifier = Modifier.weight(1f),
                            onClick = { onSuggestedCommand("Take me to the library") }
                        )
                        SuggestedChip(
                            label = "What is in front of me?",
                            modifier = Modifier.weight(1f),
                            onClick = { onSuggestedCommand("What is in front of me?") }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SuggestedChip(
                            label = "Read this sign",
                            modifier = Modifier.weight(1f),
                            onClick = { onSuggestedCommand("Read this sign") }
                        )
                        SuggestedChip(
                            label = "Give me the status",
                            modifier = Modifier.weight(1f),
                            onClick = { onSuggestedCommand("Give me the status") }
                        )
                    }
                }

                // Bottom Mic Button
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(NavisBlack)
                        .clickable { onMicClick() }
                        .semantics { contentDescription = "Tap to speak a voice command" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = NavisWhite,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        } else {
            // Screen 5: Active Conversation Stream Layout
            LazyColumn(
                state = conversationListState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Top wave indicator
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .clip(CircleShape)
                                .background(NavisBlack),
                            contentAlignment = Alignment.Center
                        ) {
                            AudioWaveform(
                                modifier = Modifier.padding(14.dp),
                                barCount = 10,
                                color = NavisWhite,
                                isAnimated = uiState.voiceState == VoiceState.SPEAKING || uiState.voiceState == VoiceState.LISTENING,
                                height = 30.dp
                            )
                        }
                    }
                }

                // Chat items
                items(uiState.conversation) { message ->
                    if (message.sender == "user") {
                        UserMessageBubble(message.text)
                    } else {
                        AssistantMessageBubble(
                            message = message,
                            onStartNavigation = onStartNavigation
                        )
                    }
                }

                // Suggested chips item
                item {
                    Text(
                        text = "Suggested Questions",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = NavisTextSecondary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SuggestedChip(
                            label = "Take me to the library",
                            modifier = Modifier.weight(1f),
                            onClick = { onSuggestedCommand("Take me to the library") }
                        )
                        SuggestedChip(
                            label = "What is in front of me?",
                            modifier = Modifier.weight(1f),
                            onClick = { onSuggestedCommand("What is in front of me?") }
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SuggestedChip(
                            label = "Read this sign",
                            modifier = Modifier.weight(1f),
                            onClick = { onSuggestedCommand("Read this sign") }
                        )
                        SuggestedChip(
                            label = "Give me the status",
                            modifier = Modifier.weight(1f),
                            onClick = { onSuggestedCommand("Give me the status") }
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }

            // Bottom Voice Bar matching Screen 5 mockup
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Dark Voice pill with audio waveform
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(26.dp))
                            .background(NavisDeepBlack)
                            .clickable { onMicClick() }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .semantics {
                                contentDescription = if (uiState.isWakeWordListening) {
                                    "Wake word active. Say Hello NAVIS followed by a question or feature command. Tap for direct speech input."
                                } else {
                                    "Microphone listening bar. Tap to speak."
                                }
                            }
                    ) {
                        AudioWaveform(
                            modifier = Modifier.width(60.dp),
                            barCount = 8,
                            color = NavisWhite,
                            isAnimated = uiState.isWakeWordListening || uiState.voiceState == VoiceState.LISTENING || uiState.voiceState == VoiceState.SPEAKING,
                            height = 24.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = when {
                                uiState.awaitingWakeCommand -> "Say a command…"
                                uiState.voiceState == VoiceState.LISTENING -> "Listening…"
                                uiState.isWakeWordListening -> "Say Hello NAVIS…"
                                else -> "Tap to speak…"
                            },
                            fontSize = 13.sp,
                            color = NavisWhite
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Red Stop Button
                    IconButton(
                        onClick = onStopClick,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .semantics { contentDescription = "Stop speaking or listening" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = null,
                            tint = HazardCritical,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResponseStylePicker(
    selectedStyle: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Style",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = NavisTextSecondary
        )
        FilterChip(
            selected = selectedStyle == "auto",
            onClick = { onSelect("auto") },
            label = { Text("Auto", fontSize = 12.sp) },
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            selected = selectedStyle == "quick",
            onClick = { onSelect("quick") },
            label = { Text("Quick", fontSize = 12.sp) },
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            selected = selectedStyle == "detailed",
            onClick = { onSelect("detailed") },
            label = { Text("Detailed", fontSize = 12.sp) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun UserMessageBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            color = NavisDeepBlack,
            modifier = Modifier.padding(start = 40.dp)
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                color = NavisWhite,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(NavisLightGrey),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = NavisBlack,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun AssistantMessageBubble(
    message: ChatMessage,
    onStartNavigation: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(NavisBlack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = null,
                tint = NavisWhite,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = message.text,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            // Embedded Route Preview Card if navigation command (Mockup Screen 5)
            message.routePreview?.let { route ->
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onStartNavigation(route.destination) }
                        .semantics {
                            contentDescription = "Route to ${route.destination}. Distance ${route.distance}. Estimated time ${route.duration}. Tap to start turn-by-turn navigation."
                        }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Stylized Pedestrian Route Canvas matching mockup
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(84.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(NavisLightGrey)
                        ) {
                            val path = Path().apply {
                                moveTo(size.width * 0.15f, size.height * 0.85f)
                                lineTo(size.width * 0.40f, size.height * 0.60f)
                                lineTo(size.width * 0.55f, size.height * 0.70f)
                                lineTo(size.width * 0.80f, size.height * 0.25f)
                            }

                            // Road paths
                            drawLine(
                                color = Color.White,
                                start = Offset(0f, size.height * 0.65f),
                                end = Offset(size.width, size.height * 0.65f),
                                strokeWidth = 10.dp.toPx()
                            )

                            // Route Polyline
                            drawPath(
                                path = path,
                                color = NavisBlack,
                                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                            )

                            // Start point
                            drawCircle(NavisBlack, radius = 5.dp.toPx(), center = Offset(size.width * 0.15f, size.height * 0.85f))
                            drawCircle(NavisWhite, radius = 2.5.dp.toPx(), center = Offset(size.width * 0.15f, size.height * 0.85f))

                            // End Pin
                            drawCircle(NavisBlack, radius = 7.dp.toPx(), center = Offset(size.width * 0.80f, size.height * 0.25f))
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(NavisBlack),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DirectionsWalk,
                                    contentDescription = null,
                                    tint = NavisWhite,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = route.destination,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${route.distance} • ${route.duration}",
                                    fontSize = 11.sp,
                                    color = NavisTextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SuggestedChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
