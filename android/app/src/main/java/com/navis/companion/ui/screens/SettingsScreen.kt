package com.navis.companion.ui.screens

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navis.companion.ui.theme.NavisBlack
import com.navis.companion.ui.theme.NavisTextSecondary
import com.navis.companion.ui.theme.NavisWhite
import com.navis.companion.ui.viewmodel.NavisUiState

/**
 * Screen 11: Settings & Accessibility interface matching mockup Screen 11.
 * Controls Appearance, Voice & Audio parameters, Navigation preferences,
 * and Backend hardware connectivity.
 */
@Composable
fun SettingsScreen(
    uiState: NavisUiState,
    onBack: () -> Unit,
    onThemeToggle: (Boolean) -> Unit,
    onWakeWordToggle: (Boolean) -> Unit,
    onUpdateServerUrl: (String) -> Unit,
    onUpdateApiToken: (String) -> Unit
) {
    val scrollState = rememberScrollState()
    var serverUrlInput by remember { mutableStateOf(uiState.serverUrl) }
    var apiTokenInput by remember { mutableStateOf(uiState.apiToken) }
    var selectedTheme by remember { mutableStateOf(if (uiState.isDarkTheme) "Dark" else "Light") }
    var selectedTextSizeIndex by remember { mutableStateOf(2) } // 0=Small, 1=Medium, 2=Large
    var voiceSpeed by remember { mutableFloatStateOf(uiState.voiceSpeed) }
    var volume by remember { mutableFloatStateOf(uiState.volumeLevel) }
    var selectedAlertStyle by remember { mutableStateOf("Voice") }
    var selectedUnits by remember { mutableStateOf("Metres") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
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
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Settings",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Section 1: Appearance matching Screen 11
        SettingsSectionHeader("Appearance")

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Theme Toggle: Light, Dark, System
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SegmentOption(
                        label = "Light",
                        selected = selectedTheme == "Light",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedTheme = "Light"
                            onThemeToggle(false)
                        }
                    )
                    SegmentOption(
                        label = "Dark",
                        selected = selectedTheme == "Dark",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedTheme = "Dark"
                            onThemeToggle(true)
                        }
                    )
                    SegmentOption(
                        label = "System",
                        selected = selectedTheme == "System",
                        modifier = Modifier.weight(1f),
                        onClick = { selectedTheme = "System" }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Text Size Selector: A (Small), A (Default), A (Large)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Text Size", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextSizePill(
                            label = "A",
                            fontSize = 12.sp,
                            selected = selectedTextSizeIndex == 0,
                            onClick = { selectedTextSizeIndex = 0 }
                        )
                        TextSizePill(
                            label = "A",
                            fontSize = 15.sp,
                            selected = selectedTextSizeIndex == 1,
                            onClick = { selectedTextSizeIndex = 1 }
                        )
                        TextSizePill(
                            label = "A",
                            fontSize = 19.sp,
                            selected = selectedTextSizeIndex == 2,
                            onClick = { selectedTextSizeIndex = 2 }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 2: Voice & Audio matching Screen 11
        SettingsSectionHeader("Voice & Audio")

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Voice Speed Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.VolumeDown, contentDescription = null, tint = NavisTextSecondary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Voice Speed", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(text = "${(voiceSpeed * 100).toInt()}%", fontSize = 12.sp, color = NavisTextSecondary)
                }
                Slider(
                    value = voiceSpeed,
                    onValueChange = { voiceSpeed = it },
                    valueRange = 0.5f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = NavisBlack, activeTrackColor = NavisBlack)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Volume Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.VolumeUp, contentDescription = null, tint = NavisTextSecondary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Volume", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(text = "${(volume * 100).toInt()}%", fontSize = 12.sp, color = NavisTextSecondary)
                }
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    valueRange = 0.0f..1.0f,
                    colors = SliderDefaults.colors(thumbColor = NavisBlack, activeTrackColor = NavisBlack)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Alert Style Toggle: Voice, Tone
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Alert Style", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SegmentOption(
                            label = "Voice",
                            selected = selectedAlertStyle == "Voice",
                            modifier = Modifier.width(80.dp),
                            onClick = { selectedAlertStyle = "Voice" }
                        )
                        SegmentOption(
                            label = "Tone",
                            selected = selectedAlertStyle == "Tone",
                            modifier = Modifier.width(80.dp),
                            onClick = { selectedAlertStyle = "Tone" }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Language
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Language", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = uiState.preferredLanguage, fontSize = 13.sp, color = NavisTextSecondary)
                        Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = NavisTextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Hello NAVIS wake word", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (uiState.isWakeWordListening) "Listening only while NAVIS is open"
                            else "Wake listening stops when you leave NAVIS",
                            fontSize = 12.sp,
                            color = NavisTextSecondary
                        )
                    }
                    Switch(
                        checked = uiState.wakeWordEnabled,
                        onCheckedChange = onWakeWordToggle,
                        modifier = Modifier.semantics { contentDescription = "Enable Hello NAVIS wake word" }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 3: Navigation matching Screen 11
        SettingsSectionHeader("Navigation")

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Units: Metres, Feet
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Units", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SegmentOption(
                            label = "Metres",
                            selected = selectedUnits == "Metres",
                            modifier = Modifier.width(90.dp),
                            onClick = { selectedUnits = "Metres" }
                        )
                        SegmentOption(
                            label = "Feet",
                            selected = selectedUnits == "Feet",
                            modifier = Modifier.width(80.dp),
                            onClick = { selectedUnits = "Feet" }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(text = "Route mode: Walking", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Wheelchair-specific routing is not available in this prototype.",
                    fontSize = 12.sp,
                    color = NavisTextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 4: AI Backend Connection
        SettingsSectionHeader("Connected Devices & Backend")

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Backend Server URL",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverUrlInput,
                    onValueChange = { serverUrlInput = it },
                    label = { Text("http://10.0.2.2:8000 or laptop IP") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiTokenInput,
                    onValueChange = { apiTokenInput = it },
                    label = { Text("API token (if configured on backend)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Backend: ${uiState.backendStatus}",
                    fontSize = 12.sp,
                    color = NavisTextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        onUpdateServerUrl(serverUrlInput)
                        onUpdateApiToken(apiTokenInput)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NavisBlack, contentColor = NavisWhite)
                ) {
                    Text("Save & Connect")
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 5: About & Help
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.HelpOutline, contentDescription = null, tint = NavisBlack)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Help & Guide", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = NavisTextSecondary)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = NavisBlack)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "About NAVIS", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(text = "Version 1.0.0 • See Beyond Limits", fontSize = 12.sp, color = NavisTextSecondary)
                    }
                    Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = NavisTextSecondary)
                }
            }
        }

        Spacer(modifier = Modifier.height(26.dp))
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = NavisTextSecondary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
fun SegmentOption(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) NavisBlack else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) NavisWhite else NavisTextSecondary
            )
        }
    }
}

@Composable
fun TextSizePill(
    label: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) NavisBlack else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .size(36.dp)
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                color = if (selected) NavisWhite else NavisTextSecondary
            )
        }
    }
}
