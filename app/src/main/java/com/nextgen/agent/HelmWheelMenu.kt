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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Action item for a wheel spoke.
 */
data class WheelItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val accentColor: Color = Color.Black,
    val onClick: () -> Unit
)

enum class WheelPosition {
    TOP_RIGHT,
    BOTTOM_RIGHT
}

/**
 * Corner-Anchored Half-Wheel / Ship's Helm Menu.
 * Matches user's wireframe design:
 * - Anchored right at the top-right or bottom-right corner of the screen.
 * - Shows a half-wheel (arc) extending into the screen with dual concentric rims, 8 spokes,
 *   helm handles with round knobs, and spoke-mounted circular icon badges.
 * - Supports continuous finger drag rotation around the corner hub.
 * - Performs a full 1-time 360° spin on item tap before executing the action.
 * - Operates directly on the screen without a dark obscuring background so logo and screen are visible.
 */
@Composable
fun CornerHelmWheel(
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

    // Rotation angle in degrees:
    // Initial angle offsets so the primary items appear in the visible quadrant:
    // For TOP_RIGHT: visible quadrant is angles 90° to 180° (down and left).
    // For BOTTOM_RIGHT: visible quadrant is angles 180° to 270° (left and up).
    val initialAngle = if (position == WheelPosition.TOP_RIGHT) 100f else 190f
    val rotationAngle = remember { Animatable(initialAngle) }
    var spinningItemId by remember { mutableStateOf<String?>(null) }
    var isSpinning by remember { mutableStateOf(false) }

    // Wheel Geometry Dimensions
    val hubRadius = 26.dp
    val innerRimRadius = 66.dp
    val outerRimRadius = 106.dp
    val badgeRadius = 106.dp
    val handleLength = 34.dp
    val totalRadius = outerRimRadius + handleLength // ~140.dp

    val hubRadiusPx = with(density) { hubRadius.toPx() }
    val innerRimPx = with(density) { innerRimRadius.toPx() }
    val outerRimPx = with(density) { outerRimRadius.toPx() }
    val handleLenPx = with(density) { handleLength.toPx() }

    // Wireframe Design Colors (Light & Dark Theme):
    // In light theme: classic crisp sketch/line-art with deep charcoal lines and white fills.
    // In dark theme: cyber obsidian with metallic silver/cyan highlights.
    val strokeColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF1E293B)
    val rimBandFill = if (isDark) Color(0xFF1A1F2E) else Color(0xFFF1F5F9)
    val handleColor = if (isDark) Color(0xFFCBD5E1) else Color(0xFF334155)
    val hubBg = if (isDark) Color(0xFF131722) else Color(0xFFFFFFFF)
    val hubBorder = if (isDark) Color(0xFF38BDF8) else Color(0xFF0F172A)
    val knobColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF0F172A)

    // The container size covers the full diameter of the wheel:
    val containerSize = totalRadius * 2 + 60.dp
    val halfContainer = containerSize / 2

    // Hub center offset relative to container center is (0, 0).
    // We position the container so its center aligns with the phone corner:
    // For TOP_RIGHT: center is at (screenWidth - 34.dp, 34.dp)
    // For BOTTOM_RIGHT: center is at (screenWidth - 34.dp, screenHeight - 110.dp)
    val cornerOffset = when (position) {
        WheelPosition.TOP_RIGHT -> Modifier
            .offset(x = halfContainer - 34.dp, y = -(halfContainer - 34.dp))
        WheelPosition.BOTTOM_RIGHT -> Modifier
            .offset(x = halfContainer - 34.dp, y = halfContainer - 115.dp)
    }

    val boxAlignment = when (position) {
        WheelPosition.TOP_RIGHT -> Alignment.TopEnd
        WheelPosition.BOTTOM_RIGHT -> Alignment.BottomEnd
    }

    // Transparent tap-outside listener (no dark scrim covering the screen!)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!isSpinning) onDismissRequest()
            }
    ) {
        // Corner Helm Wheel Container
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

            // 1. Canvas: Authentic Ship's Helm drawing matching wireframe line art!
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .rotate(currentRot)
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f

                // Outer ambient aura glow (subtle)
                drawCircle(
                    color = if (isDark) Color(0xFF38BDF8).copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.04f),
                    center = Offset(cx, cy),
                    radius = outerRimPx + handleLenPx + 15f
                )

                // Outer Rim Band Fill
                drawCircle(
                    color = rimBandFill,
                    center = Offset(cx, cy),
                    radius = outerRimPx,
                    style = Stroke(width = 12.dp.toPx())
                )

                // Outer Rim Outer Boundary Line
                drawCircle(
                    color = strokeColor,
                    center = Offset(cx, cy),
                    radius = outerRimPx + 6.dp.toPx(),
                    style = Stroke(width = 2.dp.toPx())
                )

                // Outer Rim Inner Boundary Line
                drawCircle(
                    color = strokeColor,
                    center = Offset(cx, cy),
                    radius = outerRimPx - 6.dp.toPx(),
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Inner Rim Band Fill
                drawCircle(
                    color = rimBandFill,
                    center = Offset(cx, cy),
                    radius = innerRimPx,
                    style = Stroke(width = 8.dp.toPx())
                )

                // Inner Rim Outer Boundary Line
                drawCircle(
                    color = strokeColor,
                    center = Offset(cx, cy),
                    radius = innerRimPx + 4.dp.toPx(),
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Inner Rim Inner Boundary Line
                drawCircle(
                    color = strokeColor,
                    center = Offset(cx, cy),
                    radius = innerRimPx - 4.dp.toPx(),
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Draw 8 Spokes & Handles with Turned Peg Knobs
                val totalSpokes = 8
                val stepAngle = (2 * PI / totalSpokes).toFloat()

                for (i in 0 until totalSpokes) {
                    val angle = i * stepAngle
                    val cosA = cos(angle)
                    val sinA = sin(angle)

                    val startX = cx + hubRadiusPx * cosA
                    val startY = cy + hubRadiusPx * sinA

                    val outerRimX = cx + outerRimPx * cosA
                    val outerRimY = cy + outerRimPx * sinA

                    val handleNeckX = cx + (outerRimPx + 6.dp.toPx()) * cosA
                    val handleNeckY = cy + (outerRimPx + 6.dp.toPx()) * sinA

                    val knobX = cx + (outerRimPx + handleLenPx) * cosA
                    val knobY = cy + (outerRimPx + handleLenPx) * sinA

                    // Spoke shaft (hub to outer rim)
                    drawLine(
                        color = strokeColor,
                        start = Offset(startX, startY),
                        end = Offset(outerRimX, outerRimY),
                        strokeWidth = 3.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // Helm Handle extending outward past outer rim
                    drawLine(
                        color = handleColor,
                        start = Offset(handleNeckX, handleNeckY),
                        end = Offset(knobX, knobY),
                        strokeWidth = 5.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // Handle collar ring near rim
                    drawCircle(
                        color = strokeColor,
                        center = Offset(handleNeckX, handleNeckY),
                        radius = 4.dp.toPx()
                    )

                    // Round Knob / Ball at the tip of the handle
                    drawCircle(
                        color = knobColor,
                        center = Offset(knobX, knobY),
                        radius = 6.dp.toPx()
                    )
                    drawCircle(
                        color = if (isDark) Color(0xFF38BDF8) else Color(0xFF0F172A),
                        center = Offset(knobX, knobY),
                        radius = 6.dp.toPx(),
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                }

                // Center Hub Ring on Canvas
                drawCircle(
                    color = hubBg,
                    center = Offset(cx, cy),
                    radius = hubRadiusPx
                )
                drawCircle(
                    color = hubBorder,
                    center = Offset(cx, cy),
                    radius = hubRadiusPx,
                    style = Stroke(width = 3.dp.toPx())
                )
            }

            // 2. Action Node Badges mounted along the spokes
            val totalItems = items.size
            val stepDeg = 360f / totalItems.coerceAtLeast(1)

            items.forEachIndexed { i, item ->
                val itemAngleDeg = i * stepDeg + currentRot
                val itemAngleRad = Math.toRadians(itemAngleDeg.toDouble())

                // Badge offset relative to center
                val offsetX = (badgeRadius.value * cos(itemAngleRad).toFloat()).dp
                val offsetY = (badgeRadius.value * sin(itemAngleRad).toFloat()).dp

                val isThisSpinning = spinningItemId == item.id

                // Interactive circular badge
                Surface(
                    modifier = Modifier
                        .offset(x = offsetX, y = offsetY)
                        .size(if (isThisSpinning) 48.dp else 42.dp)
                        .scale(if (isThisSpinning) 1.18f else 1f)
                        .clip(CircleShape)
                        .clickable(enabled = !isSpinning) {
                            // "oru itha select panna antha wheel one time spin agitu open aganum"
                            isSpinning = true
                            spinningItemId = item.id
                            coroutineScope.launch {
                                // Smooth 1-time 360° spin animation
                                rotationAngle.animateTo(
                                    targetValue = rotationAngle.value + 360f,
                                    animationSpec = tween(
                                        durationMillis = 520,
                                        easing = CubicBezierEasing(0.22f, 0.95f, 0.3f, 1.0f)
                                    )
                                )
                                isSpinning = false
                                spinningItemId = null
                                item.onClick()
                            }
                        },
                    shape = CircleShape,
                    color = if (isDark) Color(0xFF1E2235) else Color.White,
                    border = BorderStroke(
                        width = if (isThisSpinning) 2.5.dp else 1.8.dp,
                        color = if (isThisSpinning) (if (isDark) Color(0xFF38BDF8) else Color(0xFF4F46E5))
                                else (if (isDark) Color(0xFF64748B) else Color(0xFF1E293B))
                    ),
                    shadowElevation = if (isThisSpinning) 10.dp else 4.dp
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        // Badge icon stays upright regardless of wheel rotation
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (isThisSpinning) (if (isDark) Color(0xFF38BDF8) else Color(0xFF4F46E5))
                                   else (if (isDark) Color(0xFFE2E8F0) else Color(0xFF0F172A)),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // 3. Center Hub Button
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
                            onHubClick()
                        }
                    },
                shape = CircleShape,
                color = hubBg,
                border = BorderStroke(2.5.dp, hubBorder),
                shadowElevation = 8.dp
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = hubIcon,
                        contentDescription = hubLabel,
                        tint = hubBorder,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

/**
 * Compact circular Ship's Helm icon button used in Frame 2 & Frame 5:
 * An 8-spoke miniature steering wheel outline.
 * Tapping it smoothly expands the corner helm wheel!
 */
@Composable
fun CompactHelmButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Settings,
    accentColor: Color = Color.Black,
    size: Dp = 44.dp
) {
    val isDark = isSystemInDarkTheme()
    val strokeColor = if (isDark) Color(0xFFCBD5E1) else Color(0xFF1E293B)
    val hubColor = if (isDark) Color(0xFF38BDF8) else Color(0xFF1E293B)

    Surface(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Transparent
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            ) {
                val cx = this.size.width / 2f
                val cy = this.size.height / 2f
                val rOuter = cx * 0.76f
                val rInner = cx * 0.48f
                val handleLen = cx * 0.22f

                // Outer & inner rim rings
                drawCircle(
                    color = strokeColor,
                    radius = rOuter,
                    style = Stroke(width = 1.8.dp.toPx())
                )
                drawCircle(
                    color = strokeColor,
                    radius = rInner,
                    style = Stroke(width = 1.2.dp.toPx())
                )

                // 8 Spokes with handle pegs
                for (i in 0 until 8) {
                    val angle = (i * PI / 4).toFloat()
                    val cosA = cos(angle)
                    val sinA = sin(angle)

                    // Spoke
                    drawLine(
                        color = strokeColor,
                        start = Offset(cx + rInner * cosA, cy + rInner * sinA),
                        end = Offset(cx + rOuter * cosA, cy + rOuter * sinA),
                        strokeWidth = 1.4.dp.toPx()
                    )

                    // Handle peg
                    drawLine(
                        color = strokeColor,
                        start = Offset(cx + rOuter * cosA, cy + rOuter * sinA),
                        end = Offset(cx + (rOuter + handleLen) * cosA, cy + (rOuter + handleLen) * sinA),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // Center hub circle
                drawCircle(
                    color = strokeColor,
                    center = Offset(cx, cy),
                    radius = cx * 0.22f,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // Hub center icon
            Icon(
                imageVector = icon,
                contentDescription = "Menu",
                tint = hubColor,
                modifier = Modifier.size(size * 0.32f)
            )
        }
    }
}
