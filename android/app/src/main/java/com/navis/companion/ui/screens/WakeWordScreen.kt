package com.navis.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navis.companion.ui.components.PulsingCircularRadar
import com.navis.companion.ui.theme.NavisDarkCard
import com.navis.companion.ui.theme.NavisDeepBlack
import com.navis.companion.ui.theme.NavisTextSecondary
import com.navis.companion.ui.theme.NavisWhite

/**
 * Screen 3: Hello NAVIS Wake-Word Detection screen matching the mockup.
 * Immersive dark interface with animated pulsing concentric radar and
 * direct voice-activity trigger.
 */
@Composable
fun WakeWordScreen(
    onDismiss: () -> Unit,
    onWakeWordDetected: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NavisDeepBlack)
            .padding(28.dp)
            .semantics {
                contentDescription = "Wake-word screen. Listening for Hello Navis. Tap center to activate immediately."
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top branding
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(
                    text = "N A V I S",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                    color = NavisWhite
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Listening…",
                    fontSize = 14.sp,
                    color = NavisTextSecondary
                )
            }

            // Center Radar Animation & Tap trigger
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onWakeWordDetected() }
                    .padding(vertical = 12.dp)
            ) {
                PulsingCircularRadar(
                    modifier = Modifier.size(260.dp),
                    color = NavisWhite
                )

                Spacer(modifier = Modifier.height(26.dp))

                Text(
                    text = "Listening for",
                    fontSize = 15.sp,
                    color = NavisTextSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "\"Hello Navis\"",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = NavisWhite
                )
            }

            // Bottom Close 'X' Button matching mockup
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(NavisDarkCard)
                    .clickable { onDismiss() }
                    .semantics { contentDescription = "Close wake-word screen and return to Home" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = NavisWhite,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
