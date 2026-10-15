package com.nextgen.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Represents an action item located on one of the wheel spokes.
 */
data class WheelItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val accentColor: Color = Indigo,
    val onClick: () -> Unit
)

enum class WheelPosition {
    TOP_RIGHT,
    BOTTOM_RIGHT
}

/**
 * High-performance, animated ship helm / steering wheel menu.
 * Supports:
 * - Interactive finger drag rotation around the hub.
 * - One-time 360° spin animation on item selection before triggering the action.
 * - Expand / collapse transitions from corner anchors.
 * - Dynamic Dark and Light theme styling with brushed metallic rims & neon aura glows.
 */
@Composable
fun HelmWheelMenu(
    isExpanded: Boolean,
    onDismissRequest: () -> Unit,
    position: WheelPosition,
    hubIcon: ImageVector,
    hubLabel: String,
    onHubClick: () -> Unit,
    items: List<WheelItem>,
    modifier: Modifier = Modifier
) {
    if (!isExpanded) return

    val coroutineScope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val density = LocalDensity.current

    // Rotation state in degrees
    val rotationAngle = remember { Animatable(if (position == WheelPosition.TOP_RIGHT) 45f else -45f) }
    var spinningItemId by remember { mutableStateOf<String?>(null) }
    var isSpinning by remember { mutableStateOf(false) }

    // Dimensions
    val wheelRadius = 145.dp
    val badgeRadius = 110.dp
    val hubRadius = 38.dp
    val rimOuterRadius = 130.dp
    val rimInnerRadius = 85.dp
    val handleLength = 25.dp

    val wheelRadiusPx = with(density) { wheelRadius.toPx() }
    val badgeRadiusPx = with(density) { badgeRadius.toPx() }
    val hubRadiusPx = with(density) { hubRadius.toPx() }
    val rimOuterPx = with(density) { rimOuterRadius.toPx() }
    val rimInnerPx = with(density) { rimInnerRadius.toPx() }
    val handleLenPx = with(density) { handleLength.toPx() }

    // Theme color palette
    val rimBrush = if (isDark) {
        Brush.sweepGradient(
            listOf(
                Color(0xFF38BDF8),
                Color(0xFF818CF8),
                Color(0xFFC084FC),
                Color(0xFF34D399),
                Color(0xFF38BDF8)
            )
        )
    } else {
        Brush.sweepGradient(
            listOf(
                Color(0xFF4F46E5),
                Color(0xFF0284C7),
                Color(0xFF7C3AED),
                Color(0xFF0D9488),
                Color(0xFF4F46E5)
            )
        )
    }

    val spokeColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
    val handleColor = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)
    val hubBg = if (isDark) Color(0xFF131726) else Color(0xFFFFFFFF)
    val hubBorder = if (isDark) Color(0xFF38BDF8) else Color(0xFF4F46E5)

    // Pulse animation for selection / idle halo
    val pulseTransition = rememberInfiniteTransition(label = "helm_pulse")
    val haloAlpha by pulseTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "halo"
    )

    // Wheel Container Size (Diameter = 2 * wheelRadius + 2 * handleLength + 40dp for badges)
    val containerSize = wheelRadius * 2 + handleLength * 2 + 50.dp
    val centerOffsetDp = containerSize / 2

    // Dismiss scrim backdrop
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (isDark) 0.55f else 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!isSpinning) onDismissRequest()
            }
    ) {
        // Alignment anchor
        val boxAlignment = when (position) {
            WheelPosition.TOP_RIGHT -> Alignment.TopEnd
            WheelPosition.BOTTOM_RIGHT -> Alignment.BottomEnd
        }

        // Corner offset so the center of the wheel is near the screen edge/corner
        val cornerOffset = when (position) {
            WheelPosition.TOP_RIGHT -> Modifier.offset(x = 60.dp, y = (-20).dp)
            WheelPosition.BOTTOM_RIGHT -> Modifier.offset(x = 60.dp, y = 20.dp)
        }

        Box(
            modifier = Modifier
                .align(boxAlignment)
                .then(cornerOffset)
                .size(containerSize)
                .pointerInput(isSpinning) {
                    if (isSpinning) return@pointerInput
                    var lastAngle = 0f
                    detectDragGestures(
                        onDragStart = { offset ->
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            lastAngle = Math.toDegrees(atan2((offset.y - cy).toDouble(), (offset.x - cx).toDouble())).toFloat()
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val currentAngle = Math.toDegrees(atan2((change.position.y - cy).toDouble(), (change.position.x - cx).toDouble())).toFloat()
                            var delta = currentAngle - lastAngle
                            if (delta > 180f) delta -= 360f
                            if (delta < -180f) delta += 360f
                            lastAngle = currentAngle
                            coroutineScope.launch {
                                rotationAngle.snapTo(rotationAngle.value + delta)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val currentRot = rotationAngle.value

            // 1. Canvas: Realistic Ship's Helm drawing (Hub, Spokes, Rim, Handles)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .rotate(currentRot)
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f

                // Outer ambient aura glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            if (isDark) Color(0xFF6366F1).copy(alpha = haloAlpha) else Color(0xFF38BDF8).copy(alpha = haloAlpha * 0.7f),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = rimOuterPx + handleLenPx + 20f
                    ),
                    center = Offset(cx, cy),
                    radius = rimOuterPx + handleLenPx + 20f
                )

                // Outer Rim
                drawCircle(
                    brush = rimBrush,
                    center = Offset(cx, cy),
                    radius = rimOuterPx,
                    style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                )

                // Inner Rim
                drawCircle(
                    color = spokeColor.copy(alpha = 0.5f),
                    center = Offset(cx, cy),
                    radius = rimInnerPx,
                    style = Stroke(width = 4.dp.toPx())
                )

                // Draw Spokes and Outer Handles
                val totalItems = items.size.coerceAtLeast(6)
                val stepAngle = (2 * PI / totalItems).toFloat()

                for (i in 0 until totalItems) {
                    val angle = i * stepAngle
                    val cosA = cos(angle)
                    val sinA = sin(angle)

                    val startX = cx + hubRadiusPx * cosA
                    val startY = cy + hubRadiusPx * sinA

                    val rimX = cx + rimOuterPx * cosA
                    val rimY = cy + rimOuterPx * sinA

                    val handleTipX = cx + (rimOuterPx + handleLenPx) * cosA
                    val handleTipY = cy + (rimOuterPx + handleLenPx) * sinA

                    // Spoke line
                    drawLine(
                        color = spokeColor,
                        start = Offset(startX, startY),
                        end = Offset(rimX, rimY),
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // Helm Handle / Peg extending past outer rim
                    drawLine(
                        color = handleColor,
                        start = Offset(rimX, rimY),
                        end = Offset(handleTipX, handleTipY),
                        strokeWidth = 6.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // Knob at the tip of the handle
                    drawCircle(
                        color = handleColor,
                        center = Offset(handleTipX, handleTipY),
                        radius = 4.5.dp.toPx()
                    )
                }
            }

            // 2. Action Node Badges placed on the spokes
            val totalItems = items.size
            val stepDeg = 360f / totalItems.coerceAtLeast(1)

            items.forEachIndexed { i, item ->
                // Item angle with rotation applied
                val itemAngleDeg = i * stepDeg + currentRot
                val itemAngleRad = Math.toRadians(itemAngleDeg.toDouble())

                val offsetX = (badgeRadius.value * cos(itemAngleRad).toFloat()).dp
                val offsetY = (badgeRadius.value * sin(itemAngleRad).toFloat()).dp

                val isThisSpinning = spinningItemId == item.id

                // Interactive badge surface
                Surface(
                    modifier = Modifier
                        .offset(x = offsetX, y = offsetY)
                        .size(if (isThisSpinning) 54.dp else 48.dp)
                        .scale(if (isThisSpinning) 1.15f else 1f)
                        .clip(CircleShape)
                        .clickable(enabled = !isSpinning) {
                            // "oru itha select panna antha wheel one time spin agitu open aganum"
                            isSpinning = true
                            spinningItemId = item.id
                            coroutineScope.launch {
                                // Full 360 degree spin rotation
                                rotationAngle.animateTo(
                                    targetValue = rotationAngle.value + 360f,
                                    animationSpec = tween(
                                        durationMillis = 520,
                                        easing = CubicBezierEasing(0.25f, 0.9f, 0.35f, 1.0f)
                                    )
                                )
                                isSpinning = false
                                spinningItemId = null
                                onDismissRequest()
                                item.onClick()
                            }
                        },
                    shape = CircleShape,
                    color = if (isDark) Color(0xFF1E2235) else Color.White,
                    border = BorderStroke(
                        width = if (isThisSpinning) 2.5.dp else 1.5.dp,
                        color = if (isThisSpinning) item.accentColor else item.accentColor.copy(alpha = 0.65f)
                    ),
                    shadowElevation = if (isThisSpinning) 12.dp else 5.dp
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        // Badge background subtle tint
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(item.accentColor.copy(alpha = if (isThisSpinning) 0.28f else 0.12f))
                        )

                        // Icon stays upright regardless of wheel rotation for maximum readability
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = item.accentColor,
                            modifier = Modifier.size(23.dp)
                        )
                    }
                }
            }

            // 3. Center Hub (Settings / Menu Center Button)
            Surface(
                modifier = Modifier
                    .size(hubRadius * 2)
                    .clip(CircleShape)
                    .clickable(enabled = !isSpinning) {
                        isSpinning = true
                        coroutineScope.launch {
                            rotationAngle.animateTo(
                                targetValue = rotationAngle.value + 360f,
                                animationSpec = tween(500, easing = FastOutSlowInEasing)
                            )
                            isSpinning = false
                            onDismissRequest()
                            onHubClick()
                        }
                    },
                shape = CircleShape,
                color = hubBg,
                border = BorderStroke(2.5.dp, hubBorder),
                shadowElevation = 10.dp
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Central Hub Icon (Gear for Top Wheel, Menu for Bottom Wheel)
                    Icon(
                        imageVector = hubIcon,
                        contentDescription = hubLabel,
                        tint = hubBorder,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        // Close Hint button in opposite corner or header
        Surface(
            modifier = Modifier
                .align(if (position == WheelPosition.TOP_RIGHT) Alignment.TopStart else Alignment.BottomStart)
                .padding(24.dp)
                .clip(CircleShape)
                .clickable { onDismissRequest() },
            shape = CircleShape,
            color = if (isDark) Color(0xFF1E2235) else Color.White,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close Wheel",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Close Wheel",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * Compact circular Ship's Helm button used in the top-right header and bottom-right bar.
 * Features an animated 8-spoke rotating nautical helm graphic with metallic finish and glowing hub.
 */
@Composable
fun CompactHelmButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Close,
    accentColor: Color = Indigo,
    size: Dp = 46.dp
) {
    val isDark = isSystemInDarkTheme()
    val infiniteTransition = rememberInfiniteTransition(label = "idle_rotation")
    val idleRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Restart),
        label = "rot"
    )

    Surface(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = if (isDark) Color(0xFF161A29) else Color.White,
        border = BorderStroke(
            1.5.dp,
            Brush.sweepGradient(
                listOf(
                    accentColor,
                    Cyan,
                    Violet,
                    accentColor
                )
            )
        ),
        shadowElevation = 5.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Helm Wheel Canvas
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp)
                    .rotate(idleRotation)
            ) {
                val cx = this.size.width / 2f
                val cy = this.size.height / 2f
                val rOuter = cx * 0.82f
                val rInner = cx * 0.50f
                val handleLen = cx * 0.18f

                // Outer and inner rim
                drawCircle(
                    color = if (isDark) Color(0xFF38BDF8) else accentColor,
                    radius = rOuter,
                    style = Stroke(width = 1.8.dp.toPx())
                )
                drawCircle(
                    color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8),
                    radius = rInner,
                    style = Stroke(width = 1.2.dp.toPx())
                )

                // 8 spokes and handles
                for (i in 0 until 8) {
                    val angle = (i * PI / 4).toFloat()
                    val cosA = cos(angle)
                    val sinA = sin(angle)

                    // Spoke
                    drawLine(
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        start = Offset(cx + rInner * cosA, cy + rInner * sinA),
                        end = Offset(cx + rOuter * cosA, cy + rOuter * sinA),
                        strokeWidth = 1.5.dp.toPx()
                    )

                    // Outer Handle Peg
                    drawLine(
                        color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569),
                        start = Offset(cx + rOuter * cosA, cy + rOuter * sinA),
                        end = Offset(cx + (rOuter + handleLen) * cosA, cy + (rOuter + handleLen) * sinA),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            // Center icon in the hub
            Icon(
                imageVector = icon,
                contentDescription = "Wheel Menu",
                tint = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B),
                modifier = Modifier.size(size * 0.44f)
            )
        }
    }
}

