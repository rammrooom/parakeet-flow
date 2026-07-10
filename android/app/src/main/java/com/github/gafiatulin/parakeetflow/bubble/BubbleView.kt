package com.github.gafiatulin.parakeetflow.bubble

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.github.gafiatulin.parakeetflow.core.model.AppPhase
import com.github.gafiatulin.parakeetflow.ui.theme.ParakeetGreen
import com.github.gafiatulin.parakeetflow.ui.theme.ProcessingAmber
import com.github.gafiatulin.parakeetflow.ui.theme.RecordingRed
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val LONG_PRESS_MS = 300L
private const val TAP_SLOP_PX = 30f
private const val PRESSURE_THRESHOLD = 0.3f

@Composable
fun BubbleView(
    phase: AppPhase,
    dismissProgress: Float,
    bubbleSizeDp: Int,
    bubbleColorArgb: Int,
    bubbleOpacity: Float,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onCancel: () -> Unit,
    onPauseResume: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = when (phase) {
            AppPhase.RECORDING -> RecordingRed
            AppPhase.PAUSED -> Color(0xFF757575)
            AppPhase.PROCESSING -> ProcessingAmber
            AppPhase.INSERTING -> ParakeetGreen
            AppPhase.ERROR -> MaterialTheme.colorScheme.error
            AppPhase.IDLE -> Color(bubbleColorArgb)
        },
        label = "bubbleColor"
    )

    // Track hold mode for larger pulsation when finger covers bubble
    var isHoldMode by remember { mutableStateOf(false) }

    // Pulsate while recording: hold mode pulses 1.4x–1.7x, tap mode pulses 1.0x–1.15x
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseMin = if (isHoldMode) 1.4f else 1.0f
    val pulseMax = if (isHoldMode) 1.7f else 1.15f
    val pulseRaw by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isHoldMode) 500 else 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseRaw"
    )

    val baseScale = if (phase == AppPhase.RECORDING) pulseMin + pulseRaw * (pulseMax - pulseMin) else 1.0f
    // Shrink slightly when magnetized to dismiss target
    val dismissScale = 1f - (dismissProgress * 0.2f)

    val animScale by animateFloatAsState(
        targetValue = baseScale * dismissScale,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "bubbleScale"
    )

    val visibleSize = bubbleSizeDp.dp
    // Keep the same 28dp padding on each side the original layout used, so the
    // overlay geometry math in BubbleOverlayManager stays consistent.
    val containerSize = (bubbleSizeDp + 56).dp
    val iconSize = (bubbleSizeDp * 30 / 64).dp
    val showControls = phase == AppPhase.RECORDING || phase == AppPhase.PAUSED

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(containerSize),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(visibleSize)
                    .scale(animScale)
                    .alpha(bubbleOpacity)
                    .shadow(4.dp, CircleShape)
                    .background(bgColor, CircleShape)
                    .pointerInput(Unit) {
                        coroutineScope {
                            awaitPointerEventScope {
                                while (true) {
                                    // Wait for finger down
                                    val down = awaitPointerEvent()
                                    if (down.type != PointerEventType.Press) continue
                                    val downId = down.changes.firstOrNull()?.id ?: continue
                                    val downPressure = down.changes.first().pressure

                                    var isDragging = false
                                    var isHolding = false
                                    var released = false
                                    var totalDrag = Offset.Zero

                                    // Launch a timer for long press detection
                                    val longPressJob = launch {
                                        delay(LONG_PRESS_MS)
                                        if (!isDragging && !released && downPressure >= PRESSURE_THRESHOLD) {
                                            isHolding = true
                                            isHoldMode = true
                                            onHoldStart()
                                        }
                                    }

                                    // Track movement until release
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == downId }

                                        if (change == null || !change.pressed) {
                                            // Finger lifted
                                            released = true
                                            longPressJob.cancel()

                                            when {
                                                isDragging -> onDragEnd()
                                                isHolding -> {
                                                    isHoldMode = false
                                                    onHoldEnd()
                                                }
                                                else -> onTap()
                                            }
                                            // Consume remaining changes
                                            event.changes.forEach { it.consume() }
                                            break
                                        }

                                        val delta = change.position - (change.previousPosition)
                                        totalDrag += delta

                                        if (!isDragging && !isHolding) {
                                            // Check if moved beyond tap slop → start drag
                                            if (abs(totalDrag.x) > TAP_SLOP_PX || abs(totalDrag.y) > TAP_SLOP_PX) {
                                                longPressJob.cancel()
                                                isDragging = true
                                                onDragStart()
                                            }
                                        }

                                        if (isDragging) {
                                            onDrag(delta)
                                        }

                                        change.consume()
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (phase) {
                        AppPhase.RECORDING -> Icons.Default.Mic
                        AppPhase.PAUSED -> Icons.Default.Pause
                        AppPhase.PROCESSING -> Icons.Default.Sync
                        else -> Icons.Default.MicOff
                    },
                    contentDescription = "Dictation",
                    tint = Color.White,
                    modifier = Modifier.size(iconSize)
                )
            }
        }

        // Pause/resume and cancel controls while a recording is active
        if (showControls) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BubbleControlButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Cancel",
                    onClick = onCancel
                )
                BubbleControlButton(
                    icon = if (phase == AppPhase.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (phase == AppPhase.PAUSED) "Resume" else "Pause",
                    onClick = onPauseResume
                )
            }
        }
    }
}

@Composable
private fun BubbleControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .shadow(4.dp, CircleShape)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * Dismiss zone overlay matching the system PiP dismiss target:
 * full dark scrim + light circle with dark X at bottom center.
 */
@Composable
fun DismissZoneView(progress: Float) {
    val alpha by animateFloatAsState(
        targetValue = (progress * 2.5f).coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "dismissAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.5f to Color.Black.copy(alpha = 0.25f),
                    1f to Color.Black.copy(alpha = 0.75f)
                )
            )
    ) {
        // Dismiss circle: matches system PiP style with Material You dynamic color
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 200.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.size(96.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 6.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
    }
}
