package com.navis.companion.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navis.companion.ui.theme.NavisBlack
import com.navis.companion.ui.theme.NavisDarkCard
import com.navis.companion.ui.theme.NavisDeepBlack
import com.navis.companion.ui.theme.NavisTextSecondary
import com.navis.companion.ui.theme.NavisWhite
import com.navis.companion.ui.viewmodel.NavisUiState

/**
 * Screen 7: Scene Description interface matching mockup Screen 7.
 * Features captured snapshot canvas with detection overlays and a dark
 * high-contrast structured scene summary card with spoken audio controls.
 */
@Composable
fun SceneDescriptionScreen(
    uiState: NavisUiState,
    onBack: () -> Unit,
    onRepeatDescription: () -> Unit,
    onMoreDetails: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
            Column {
                Text(
                    text = "N A V I S",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Analyzing scene…",
                    fontSize = 12.sp,
                    color = NavisTextSecondary
                )
            }
        }

        // Top Half: Camera Snapshot View Canvas matching Screen 7
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF242A33))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Perspective walkway
                drawRect(
                    color = Color(0xFF323B47),
                    topLeft = Offset(0f, h * 0.40f),
                    size = Size(w, h * 0.60f)
                )

                // Pedestrian Bounding Box in Center
                drawRect(
                    color = Color.White.copy(alpha = 0.85f),
                    topLeft = Offset(w * 0.22f, h * 0.25f),
                    size = Size(w * 0.22f, h * 0.45f),
                    style = Stroke(width = 2.dp.toPx())
                )

                // Parked Car Box on Right
                drawRect(
                    color = Color.White.copy(alpha = 0.40f),
                    topLeft = Offset(w * 0.55f, h * 0.35f),
                    size = Size(w * 0.35f, h * 0.28f),
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Bench Outline
                drawRect(
                    color = Color.White.copy(alpha = 0.55f),
                    topLeft = Offset(w * 0.48f, h * 0.58f),
                    size = Size(w * 0.38f, h * 0.22f),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        // Bottom Half: Structured Dark Description Card matching Screen 7
        Card(
            shape = RoundedCornerShape(24.dp, 24.dp, 0.dp, 0.dp),
            colors = CardDefaults.cardColors(containerColor = NavisDeepBlack),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                Text(
                    text = uiState.sceneDescription,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = NavisWhite,
                    modifier = Modifier.semantics {
                        contentDescription = "Scene description: ${uiState.sceneDescription}"
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons Row: [Speaker] [Repeat] [More details]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Speaker quick announce button
                    IconButton(
                        onClick = onRepeatDescription,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(NavisDarkCard)
                            .semantics { contentDescription = "Read scene description aloud" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = NavisWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Repeat Button
                    OutlinedButton(
                        onClick = onRepeatDescription,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = "Repeat", fontSize = 14.sp, color = NavisWhite)
                    }

                    // More Details Button
                    Button(
                        onClick = onMoreDetails,
                        modifier = Modifier.weight(1.2f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NavisWhite,
                            contentColor = NavisBlack
                        )
                    ) {
                        Text(text = "More details", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
