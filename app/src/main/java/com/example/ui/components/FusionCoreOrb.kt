package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.ChatUiState
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun FusionCoreOrb(
    uiState: ChatUiState,
    emotion: String,
    micVolume: Float,
    modifier: Modifier = Modifier
) {
    // Continuous rotation animation for outer energy rings
    val infiniteTransition = rememberInfiniteTransition(label = "OrbRotation")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Pulsing heartbeat float
    val heartbeatScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heartbeat"
    )

    // Select energy color theme based on emotion status
    val baseColor = when (emotion.uppercase()) {
        "WITTY" -> Color(0xFF00E5FF)       // Vibrant Cyan
        "EMPATHETIC" -> Color(0xFFFF4081)  // Warm Pink
        "ANALYTICAL" -> Color(0xFFFF9100)  // Deep Amber
        "SURPRISED" -> Color(0xFFE040FB)    // Mystic Aurora Purple
        else -> Color(0xFF2979FF)          // Steady Calm Cobalt Blue
    }

    val glowColor = baseColor.copy(alpha = 0.35f)
    val coreGlowColor = baseColor.copy(alpha = 0.15f)

    Canvas(modifier = modifier.fillMaxSize()) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val minDimension = size.width.coerceAtMost(size.height)
        val outerRadius = minDimension * 0.40f
        val innerRadius = minDimension * 0.22f

        // Dynamic voice scale modifier
        // Volume ranges 0f to 1f, amplifying the pulse
        val voiceSwell = if (uiState is ChatUiState.Speaking) 0.12f else (micVolume * 0.45f)
        val currentPulseScale = heartbeatScale + voiceSwell

        // 1. Draw Outer Glowing Atmosphere Aura (Radial Gradient Layer)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(coreGlowColor, Color.Transparent),
                center = Offset(centerX, centerY),
                radius = outerRadius * 1.5f * currentPulseScale
            ),
            radius = outerRadius * 1.5f * currentPulseScale,
            center = Offset(centerX, centerY)
        )

        // 2. Draw Orbit Track Rings
        rotate(degrees = rotationAngle, pivot = Offset(centerX, centerY)) {
            // Main ring
            drawCircle(
                color = baseColor.copy(alpha = 0.5f),
                radius = outerRadius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 2.dp.toPx())
            )

            // Asymmetric second orbit ring tilted
            drawOval(
                color = baseColor.copy(alpha = 0.25f),
                topLeft = Offset(centerX - outerRadius * 1.2f, centerY - outerRadius * 0.5f),
                size = androidx.compose.ui.geometry.Size(outerRadius * 2.4f, outerRadius * 1.0f),
                style = Stroke(width = 1.dp.toPx())
            )

            // Outer charging nodes (little circles flying on paths)
            val angles = listOf(0.0, 120.0, 240.0)
            angles.forEach { angleDeg ->
                val angleRad = Math.toRadians(angleDeg)
                val dotX = centerX + outerRadius * cos(angleRad).toFloat()
                val dotY = centerY + outerRadius * sin(angleRad).toFloat()
                drawCircle(
                    color = baseColor,
                    radius = 5.dp.toPx(),
                    center = Offset(dotX, dotY)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(baseColor, Color.Transparent),
                        center = Offset(dotX, dotY),
                        radius = 12.dp.toPx()
                    ),
                    radius = 12.dp.toPx(),
                    center = Offset(dotX, dotY)
                )
            }
        }

        // 3. Draw Dynamic Energy Flux Waves if Thinking or Speaking
        if (uiState is ChatUiState.Thinking) {
            // Draw secondary reverse-rotating orbit tracks
            rotate(degrees = -rotationAngle * 1.5f, pivot = Offset(centerX, centerY)) {
                val path = Path()
                val steps = 40
                for (i in 0..steps) {
                    val angle = (i.toFloat() / steps) * 2f * Math.PI
                    // Synthesize spiraling waves
                    val wobble = 12.dp.toPx() * sin(angle * 5f).toFloat()
                    val radius = innerRadius + wobble
                    val x = centerX + radius * cos(angle).toFloat()
                    val y = centerY + radius * sin(angle).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(
                    path = path,
                    color = baseColor.copy(alpha = 0.6f),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        // 4. Draw Inner Glowing Fusion Reactor Core (Main Energy Sphere)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(baseColor, glowColor, Color.Transparent),
                center = Offset(centerX, centerY),
                radius = innerRadius * currentPulseScale
            ),
            radius = innerRadius * currentPulseScale,
            center = Offset(centerX, centerY)
        )

        // Draw inner high-intensity focus
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = (innerRadius * 0.45f) * currentPulseScale,
            center = Offset(centerX, centerY)
        )
    }
}
