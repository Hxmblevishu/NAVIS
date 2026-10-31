package com.navis.companion.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navis.companion.ui.components.NavisEyeLogo
import com.navis.companion.ui.theme.NavisBlack
import com.navis.companion.ui.theme.NavisTextSecondary
import com.navis.companion.ui.theme.NavisWhite
import kotlin.math.sin

/**
 * Screen 1: App Launch / Splash Screen matching the mockup hero design,
 * plus accessible 3-step Onboarding & Contextual Permissions explanation.
 */
@Composable
fun SplashScreen(
    onGetStarted: () -> Unit
) {
    var showOnboardingDialog by remember { mutableStateOf(false) }
    var currentOnboardingStep by remember { mutableIntStateOf(0) }

    val transition = rememberInfiniteTransition(label = "fluid_waves")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Abstract monochrome fluid lines flowing across background
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            for (curveIndex in 0..7) {
                val path = Path()
                path.moveTo(0f, height * 0.20f + curveIndex * 30f)
                val step = width / 24f
                for (xStep in 0..24) {
                    val x = xStep * step
                    val y = height * 0.38f +
                            sin(phase + (x / width) * 3.2f + curveIndex * 0.35f) * 65f +
                            (curveIndex * 28f)
                    path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = Color.LightGray.copy(alpha = 0.35f - (curveIndex * 0.038f)),
                    style = Stroke(width = 1.6.dp.toPx())
                )
            }
        }

        // Hero Splash Content (Exact layout matching mockup Screen 1)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // Center Branding & Eye Logo
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                NavisEyeLogo(
                    size = 76.dp,
                    tint = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(22.dp))

                Text(
                    text = "N A V I S",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "See Beyond Limits.",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                    color = NavisTextSecondary
                )
            }

            // Bottom Call-to-Action & Subheading
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "AI-Powered\nVoice-Interactive\nNavigation Companion",
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    color = NavisTextSecondary,
                    modifier = Modifier.padding(bottom = 28.dp)
                )

                Button(
                    onClick = { showOnboardingDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .semantics { contentDescription = "Get Started with NAVIS onboarding" },
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NavisBlack,
                        contentColor = NavisWhite
                    )
                ) {
                    Text(
                        text = "Get Started",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        // Contextual Onboarding & Permissions Overlay
        if (showOnboardingDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NavisBlack.copy(alpha = 0.65f))
                    .clickable { /* prevent tap through */ }
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Step indicators
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 20.dp)
                        ) {
                            repeat(3) { index ->
                                Box(
                                    modifier = Modifier
                                        .size(if (currentOnboardingStep == index) 20.dp else 8.dp, 8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (currentOnboardingStep == index) NavisBlack
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                )
                            }
                        }

                        when (currentOnboardingStep) {
                            0 -> {
                                OnboardingStepContent(
                                    icon = Icons.Default.CameraAlt,
                                    title = "Understand Your Surroundings",
                                    description = "Real-time AI camera detection and ESP32 distance sensors spot obstacles, doorways, signs, and vehicles before you reach them.",
                                    permissionNote = "Requires Camera & Sensor access"
                                )
                            }
                            1 -> {
                                OnboardingStepContent(
                                    icon = Icons.Default.Mic,
                                    title = "Interact Naturally via Voice",
                                    description = "Keep NAVIS open and say 'Hello NAVIS' followed by a question or feature command. Press Volume Down or tap the mic as a fallback.",
                                    permissionNote = "Requires Microphone permission"
                                )
                            }
                            else -> {
                                OnboardingStepContent(
                                    icon = Icons.Default.Navigation,
                                    title = "Intelligent Navigation",
                                    description = "Step-by-step pedestrian guidance with verified accessible walkways, drop-off warnings, and spoken turn directions.",
                                    permissionNote = "Requires Location permission"
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (currentOnboardingStep > 0) {
                                OutlinedButton(
                                    onClick = { currentOnboardingStep-- },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Back")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onGetStarted,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Skip")
                                }
                            }

                            Button(
                                onClick = {
                                    if (currentOnboardingStep < 2) {
                                        currentOnboardingStep++
                                    } else {
                                        onGetStarted()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NavisBlack,
                                    contentColor = NavisWhite
                                )
                            ) {
                                Text(if (currentOnboardingStep == 2) "Start NAVIS" else "Next")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OnboardingStepContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    permissionNote: String
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(32.dp)
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = title,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface
    )

    Spacer(modifier = Modifier.height(10.dp))

    Text(
        text = description,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        textAlign = TextAlign.Center,
        color = NavisTextSecondary
    )

    Spacer(modifier = Modifier.height(12.dp))

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = permissionNote,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
