package com.navis.companion.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navis.companion.ui.viewmodel.NavisUiState

@Composable
fun LiveAssistScreen(
    uiState: NavisUiState,
    onBack: () -> Unit,
    onSpeakAlert: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
            }
            Text("Live assist", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (uiState.cameraPreview != null) "Live camera connected"
                    else if (uiState.isCameraActive) "Camera connected · waiting for frame"
                    else "Camera offline",
                    fontWeight = FontWeight.Bold
                )
                if (uiState.cameraPreview != null) {
                    Image(
                        bitmap = uiState.cameraPreview.asImageBitmap(),
                        contentDescription = "Live image from the connected ESP32 camera",
                        modifier = Modifier.fillMaxWidth().height(210.dp),
                        contentScale = ContentScale.Fit
                    )
                    Text(uiState.cameraPreviewMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(210.dp).background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(12.dp)
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            uiState.cameraPreviewMessage,
                            modifier = Modifier.padding(20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(uiState.perceptionMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Camera detections do not confirm distance or prove that a path is clear.", fontSize = 12.sp)
            }
        }

        Text("Detected objects", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (uiState.detectedObjects.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Text(
                    if (uiState.isCameraActive) "No objects are available from the latest perception result." else "Connect a camera to receive object detections.",
                    modifier = Modifier.padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            uiState.detectedObjects.forEach { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.label.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                            Text("${item.position} of camera view", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            item.distanceM?.let { "%.1f m".format(it) } ?: "Distance unverified",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(2.dp))
        Button(
            onClick = { onSpeakAlert(uiState.latestHazardMessage) },
            enabled = uiState.latestHazardMessage != "No live hazard alert received.",
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Repeat latest live hazard alert"
            }
        ) {
            Icon(Icons.Default.VolumeUp, contentDescription = null)
            Text("  Repeat latest alert")
        }
    }
}
