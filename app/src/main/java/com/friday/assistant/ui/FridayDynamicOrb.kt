package com.friday.assistant.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.friday.assistant.runtime.FridayUiState
import kotlin.math.cos
import kotlin.math.sin

/** Responsive Compose Canvas HUD orb. The parent controls the render size. */
@Composable
fun FridayDynamicOrb(state: FridayUiState, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "friday-orb")
    val slow by transition.animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(9000, easing = FastOutSlowInEasing)),
        label = "slowRotation"
    )
    val fast by transition.animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(1400)),
        label = "fastRotation"
    )
    val pulse by transition.animateFloat(
        0.92f,
        1.08f,
        infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val executionPulse by transition.animateFloat(
        0.82f,
        1.18f,
        infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "executionPulse"
    )
    val jitter by transition.animateFloat(
        -1f,
        1f,
        infiniteRepeatable(tween(70), RepeatMode.Reverse),
        label = "jitter"
    )

    val stage = state.stage.uppercase()
    val isError = !state.healthy || stage.contains("ERROR") || stage.contains("FAILED") || stage.contains("REJECTED")
    val isExecuting = stage.contains("EXECUT") || stage.contains("VERIFIED")
    val isThinking = stage.contains("THINK") || stage.contains("PLANN") || stage.contains("AI")
    val isListening = stage.contains("LISTEN") || stage.contains("WAKE")
    val baseColor = when {
        isError -> Color(0xFFFF1744)
        isExecuting -> Color(0xFF00FF88)
        isThinking -> Color(0xFF8A2BE2)
        isListening || state.audioAmplitude > 0.05f -> Color(0xFF0077FF)
        else -> Color(0xFF00E5FF)
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.31f
        if (radius <= 0f) return@Canvas
        val ampScale = 1f + state.audioAmplitude.coerceIn(0f, 1f) * 0.18f
        val shake = if (isError) jitter * 5f else 0f
        val centerJitter = Offset(shake, shake * 0.6f)
        val alpha = if (stage == "IDLE") 0.7f else 1f
        val activePulse = if (isExecuting) executionPulse else pulse

        drawCircle(
            Brush.radialGradient(
                listOf(baseColor.copy(alpha = if (isError) 0.34f else 0.24f), Color.Transparent),
                center = center + centerJitter,
                radius = radius * if (isExecuting) 2.5f else 2.15f
            ),
            radius * if (isExecuting) 2.5f else 2.15f,
            center + centerJitter
        )
        drawCircle(baseColor.copy(alpha = alpha * 0.12f), radius * activePulse * ampScale, center + centerJitter)
        drawCircle(
            Color.Transparent,
            radius * 0.88f * ampScale,
            center + centerJitter,
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
        )

        when {
            isThinking -> {
                drawHolographicArc(baseColor, slow, 245f, radius * 1.18f, 3f, centerJitter)
                drawHolographicArc(baseColor.copy(alpha = 0.65f), -slow, 245f, radius * 0.98f, 3f, centerJitter)
            }
            isExecuting -> {
                drawHolographicArc(baseColor, fast * 2f, 300f, radius * 1.2f * activePulse, 3f, centerJitter)
                drawCircle(baseColor.copy(alpha = 0.18f), radius * (1.15f + 0.12f * executionPulse), center + centerJitter, style = Stroke(5.dp.toPx()))
                drawCircle(baseColor.copy(alpha = 0.10f), radius * 1.55f * executionPulse, center + centerJitter, style = Stroke(2.dp.toPx()))
            }
            isError -> drawHolographicArc(baseColor, fast * 3f + jitter * 18f, 280f, radius * 1.16f, 4f, centerJitter)
            isListening -> drawHolographicArc(baseColor, fast, 300f, radius * 1.12f * ampScale, 4f, centerJitter)
            else -> drawHolographicArc(baseColor, slow, 300f, radius * 1.08f * pulse, 3f, centerJitter)
        }

        val dotRadius = radius * 0.07f
        val orbit = radius * 1.34f
        repeat(8) { index ->
            val angle = (slow + index * 45f) * Math.PI / 180.0
            drawCircle(
                baseColor.copy(alpha = if (isError) 0.7f else 0.45f),
                dotRadius,
                Offset(center.x + cos(angle).toFloat() * orbit, center.y + sin(angle).toFloat() * orbit)
            )
        }
    }
}

private fun DrawScope.drawHolographicArc(
    color: Color,
    startAngle: Float,
    sweepAngle: Float,
    arcRadius: Float,
    strokeWidthDp: Float,
    centerJitter: Offset
) {
    val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
    val topLeft = Offset(size.width / 2f - arcRadius, size.height / 2f - arcRadius) + centerJitter
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokeWidthDp.dp.toPx(), cap = StrokeCap.Round)
    )
}
