package com.nextgen.agent

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.filled.Bolt
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
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
 * Premium Corner-Anchored Half-Wheel / Ship's Helm Menu.
 * Matching the exact user wireframe artwork:
 * - Corner-anchored half-wheel arc (lower-left quadrant for Top-Right, upper-left quadrant for Bottom-Right).
 * - Authentic nautical helm graphics: dual concentric rims, 8 radial spokes, turned handles with 3D spherical knobs.
 * - Spoke-mounted circular action badges.
 * - Drag-to-spin physics with fling momentum/decay and tactile haptic feedback per spoke.
 * - Floating active tooltip chip displaying the focused spoke item label.
 * - Snappy 1-time 360° selection spin animation with shockwave glow before executing the action.
 * - Operates directly in-place on the screen with no dark scrim covering the logo or background.
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
    val coroutineScope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val density = LocalDensity.current
    val view = LocalView.current

    // Rotation angle state:
    // Initial angle offsets so the primary items appear inside the visible quadrant:
    val initialAngle = if (position == WheelPosition.TOP_RIGHT) 100f else 190f
    val rotationAngle = remember { Animatable(initialAngle) }
    var spinningItemId by remember { mutableStateOf<String?>(null) }
    var isSpinning by remember { mutableStateOf(false) }

    // Initial entrance flourish spin when expanded
    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            rotationAngle.snapTo(initialAngle - 25f)
            rotationAngle.animateTo(
                targetValue = initialAngle,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = 340f)
            )
        }
    }

    // Haptic feedback tick on spoke crossing
    var lastHapticSector by remember { mutableStateOf((initialAngle / 45f).toInt()) }
    LaunchedEffect(rotationAngle.value) {
        val currentSector = (rotationAngle.value / 45f).toInt()
        if (currentSector != lastHapticSector) {
            lastHapticSector = currentSector
            try {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            } catch (ignored: Exception) {}
        }
    }

    // Geometry Dimensions
    val hubRadius = 28.dp
    val innerRimRadius = 68.dp
    val outerRimRadius = 108.dp
    val badgeRadius = 108.dp
    val handleLength = 36.dp
    val totalRadius = outerRimRadius + handleLength // ~144.dp

    val hubRadiusPx = with(density) { hubRadius.toPx() }
    val innerRimPx = with(density) { innerRimRadius.toPx() }
    val outerRimPx = with(density) { outerRimRadius.toPx() }
    val handleLenPx = with(density) { handleLength.toPx() }

    // Theme Palettes
    val strokeColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF1E293B)
    val rimBandFill = if (isDark) {
        Brush.sweepGradient(
            listOf(
                Color(0xFF1E2538),
                Color(0xFF2A334E),
                Color(0xFF1E2538),
                Color(0xFF2A334E),
                Color(0xFF1E2538)
            )
        )
    } else {
        Brush.sweepGradient(
            listOf(
                Color(0xFFE2E8F0),
                Color(0xFFF1F5F9),
                Color(0xFFE2E8F0),
                Color(0xFFF8FAFC),
                Color(0xFFE2E8F0)
            )
        )
    }

    val handleColor = if (isDark) Color(0xFFCBD5E1) else Color(0xFF334155)
    val hubBg = if (isDark) Color(0xFF131722) else Color(0xFFFFFFFF)
    val hubBorder = if (isDark) Color(0xFF38BDF8) else Color(0xFF0F172A)
    val knobColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF0F172A)

    // Pulse transition for idle helm sheen
    val pulseTransition = rememberInfiniteTransition(label = "helm_halo")
    val haloPulse by pulseTransition.animateFloat(
        initialValue = 0.15f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "hp"
    )

    // Selection shockwave ring animation
    val shockwaveAnim = remember { Animatable(0f) }

    // Find currently focused spoke item pointing toward user's view:
    // Top-Right wheel sweet-spot angle is 135° (pointing down-left).
    // Bottom-Right wheel sweet-spot angle is 225° (pointing up-left).
    val sweetSpotAngle = if (position == WheelPosition.TOP_RIGHT) 135.0 else 225.0
    val totalItems = items.size
    val stepDeg = 360f / totalItems.coerceAtLeast(1)

    val currentRotNorm = ((rotationAngle.value % 360f) + 360f) % 360f
    var focusedItemIndex = 0
    var minDiff = 360.0
    for (i in 0 until totalItems) {
        val spokeAngle = ((i * stepDeg + currentRotNorm) % 360f).toDouble()
        var diff = abs(spokeAngle - sweetSpotAngle)
        if (diff > 180.0) diff = 360.0 - diff
        if (diff < minDiff) {
            minDiff = diff
            focusedItemIndex = i
        }
    }
    val activeItem = items.getOrNull(focusedItemIndex) ?: items.firstOrNull()

    // Container size covers full diameter:
    val containerSize = totalRadius * 2 + 60.dp
    val halfContainer = containerSize / 2

    // Corner offset to align center with top-right or bottom-right corner:
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

    val transformOrigin = when (position) {
        WheelPosition.TOP_RIGHT -> TransformOrigin(1f, 0f)
        WheelPosition.BOTTOM_RIGHT -> TransformOrigin(1f, 1f)
    }

    fun snapAngle(angle: Float, sweetSpot: Float, step: Float): Float {
        var diff = (sweetSpot - (angle % 360f)) % step
        if (diff < 0f) diff += step
        if (diff > step / 2f) diff -= step
        return angle + diff
    }

    AnimatedVisibility(
        visible = isExpanded,
        enter = fadeIn(tween(200)) + scaleIn(
            initialScale = 0.25f,
            transformOrigin = transformOrigin,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f)
        ),
        exit = fadeOut(tween(180)) + scaleOut(
            targetScale = 0.25f,
            transformOrigin = transformOrigin,
            animationSpec = tween(180)
        )
    ) {
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
            // Active Floating Tooltip Label Chip (High Contrast in both Light & Dark modes)
            activeItem?.let { act ->
                val chipAlignment = when (position) {
                    WheelPosition.TOP_RIGHT -> Alignment.TopStart
                    WheelPosition.BOTTOM_RIGHT -> Alignment.BottomStart
                }
                val chipPadding = when (position) {
                    WheelPosition.TOP_RIGHT -> Modifier.padding(start = 20.dp, top = 80.dp)
                    WheelPosition.BOTTOM_RIGHT -> Modifier.padding(start = 20.dp, bottom = 125.dp)
                }

                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = if (isDark) Color(0xFF0F172A).copy(alpha = 0.96f) else Color.White,
                    border = BorderStroke(2.dp, Brush.horizontalGradient(listOf(act.accentColor, act.accentColor.copy(alpha = 0.65f)))),
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .align(chipAlignment)
                        .then(chipPadding)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable(enabled = !isSpinning) {
                            isSpinning = true
                            spinningItemId = act.id
                            try {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            } catch (ignored: Exception) {}
                            coroutineScope.launch {
                                shockwaveAnim.snapTo(0f)
                                launch { shockwaveAnim.animateTo(1f, tween(480, easing = FastOutSlowInEasing)) }
                                rotationAngle.animateTo(
                                    targetValue = rotationAngle.value + 360f,
                                    animationSpec = tween(520, easing = CubicBezierEasing(0.22f, 0.95f, 0.3f, 1.0f))
                                )
                                isSpinning = false
                                spinningItemId = null
                                act.onClick()
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(act.accentColor.copy(alpha = 0.35f), act.accentColor.copy(alpha = 0.10f))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = act.icon,
                                contentDescription = act.label,
                                modifier = Modifier.size(19.dp),
                                tint = act.accentColor
                            )
                        }
                        Column {
                            Text(
                                text = act.label,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Black,
                                color = if (isDark) Color.White else Color(0xFF0F172A)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "தட்டவும் • Tap to activate",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = act.accentColor
                                )
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    modifier = Modifier.size(11.dp),
                                    tint = act.accentColor
                                )
                            }
                        }
                    }
                }
            }

        // Corner Helm Wheel Container
        Box(
            modifier = Modifier
                .align(boxAlignment)
                .then(cornerOffset)
                .size(containerSize)
                .pointerInput(isSpinning) {
                    if (isSpinning) return@pointerInput
                    var lastAngle = 0f
                    var lastDelta = 0f
                    detectDragGestures(
                        onDragStart = { offset ->
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            lastAngle = Math.toDegrees(atan2((offset.y - cy).toDouble(), (offset.x - cx).toDouble())).toFloat()
                            lastDelta = 0f
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
                            lastDelta = delta
                            coroutineScope.launch {
                                rotationAngle.snapTo(rotationAngle.value + delta)
                            }
                        },
                        onDragEnd = {
                            // Magnetic spoke snapping with inertia momentum & friction decay
                            val momentum = if (abs(lastDelta) > 1.2f) lastDelta * 28f else 0f
                            val estAngle = rotationAngle.value + momentum
                            val targetSnapped = snapAngle(estAngle, sweetSpotAngle.toFloat(), stepDeg)

                            coroutineScope.launch {
                                if (momentum != 0f) {
                                    rotationAngle.animateTo(
                                        targetValue = estAngle,
                                        animationSpec = tween(
                                            durationMillis = 380,
                                            easing = CubicBezierEasing(0.12f, 0.85f, 0.25f, 1.0f)
                                        )
                                    )
                                }
                                rotationAngle.animateTo(
                                    targetValue = targetSnapped,
                                    animationSpec = spring(
                                        dampingRatio = 0.68f,
                                        stiffness = 320f
                                    )
                                )
                                try {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                } catch (ignored: Exception) {}
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val currentRot = rotationAngle.value

            // 1. Canvas: High-Fidelity Ship's Helm Canvas Drawing
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .rotate(currentRot)
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f

                // Outer ambient aura glow
                drawCircle(
                    color = (activeItem?.accentColor ?: if (isDark) Color(0xFF38BDF8) else Color.Black).copy(alpha = haloPulse * 0.7f),
                    center = Offset(cx, cy),
                    radius = outerRimPx + handleLenPx + 16f
                )

                // Selection shockwave ring pulse
                if (shockwaveAnim.value > 0f && shockwaveAnim.value < 1f) {
                    drawCircle(
                        color = (activeItem?.accentColor ?: if (isDark) Color(0xFF38BDF8) else Color(0xFF4F46E5)).copy(alpha = (1f - shockwaveAnim.value) * 0.85f),
                        center = Offset(cx, cy),
                        radius = badgeRadius.toPx() + shockwaveAnim.value * 45.dp.toPx(),
                        style = Stroke(width = (4.5f * (1f - shockwaveAnim.value)).dp.toPx())
                    )
                }

                // Outer Rim Band Fill
                drawCircle(
                    brush = rimBandFill,
                    center = Offset(cx, cy),
                    radius = outerRimPx,
                    style = Stroke(width = 13.dp.toPx())
                )

                // Outer Rim Outer Boundary Line
                drawCircle(
                    color = strokeColor,
                    center = Offset(cx, cy),
                    radius = outerRimPx + 6.5.dp.toPx(),
                    style = Stroke(width = 2.2.dp.toPx())
                )

                // Outer Rim Inner Boundary Line
                drawCircle(
                    color = strokeColor,
                    center = Offset(cx, cy),
                    radius = outerRimPx - 6.5.dp.toPx(),
                    style = Stroke(width = 1.6.dp.toPx())
                )

                // Astrolabe / Compass 24 Navigation Tick Marks along outer rim
                val totalTicks = 24
                val tickStep = (2 * PI / totalTicks).toFloat()
                for (t in 0 until totalTicks) {
                    val a = t * tickStep
                    val isMajor = (t % 3 == 0)
                    val tLen = if (isMajor) 6.dp.toPx() else 3.5.dp.toPx()
                    val r1 = outerRimPx - 6.5.dp.toPx()
                    val r2 = r1 + tLen
                    drawLine(
                        color = strokeColor.copy(alpha = if (isMajor) 0.85f else 0.45f),
                        start = Offset(cx + r1 * cos(a), cy + r1 * sin(a)),
                        end = Offset(cx + r2 * cos(a), cy + r2 * sin(a)),
                        strokeWidth = if (isMajor) 1.8.dp.toPx() else 1.1.dp.toPx()
                    )
                }

                // Inner Rim Band Fill
                drawCircle(
                    brush = rimBandFill,
                    center = Offset(cx, cy),
                    radius = innerRimPx,
                    style = Stroke(width = 8.5.dp.toPx())
                )

                // Inner Rim Outer Boundary Line
                drawCircle(
                    color = strokeColor,
                    center = Offset(cx, cy),
                    radius = innerRimPx + 4.2.dp.toPx(),
                    style = Stroke(width = 1.6.dp.toPx())
                )

                // Inner Rim Inner Boundary Line
                drawCircle(
                    color = strokeColor,
                    center = Offset(cx, cy),
                    radius = innerRimPx - 4.2.dp.toPx(),
                    style = Stroke(width = 1.6.dp.toPx())
                )

                // Draw Dynamic Spokes & Handles with Turned Peg Knobs & 3D Specular Highlights
                val totalSpokes = totalItems.coerceAtLeast(8)
                val stepAngle = (2 * PI / totalSpokes).toFloat()

                for (i in 0 until totalSpokes) {
                    val angle = i * stepAngle
                    val cosA = cos(angle)
                    val sinA = sin(angle)

                    val startX = cx + hubRadiusPx * cosA
                    val startY = cy + hubRadiusPx * sinA

                    val outerRimX = cx + outerRimPx * cosA
                    val outerRimY = cy + outerRimPx * sinA

                    val handleNeckX = cx + (outerRimPx + 6.5.dp.toPx()) * cosA
                    val handleNeckY = cy + (outerRimPx + 6.5.dp.toPx()) * sinA

                    val knobX = cx + (outerRimPx + handleLenPx) * cosA
                    val knobY = cy + (outerRimPx + handleLenPx) * sinA

                    // Spoke shaft (hub to outer rim)
                    drawLine(
                        color = strokeColor,
                        start = Offset(startX, startY),
                        end = Offset(outerRimX, outerRimY),
                        strokeWidth = 3.6.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // Helm Handle extending outward past outer rim
                    drawLine(
                        color = handleColor,
                        start = Offset(handleNeckX, handleNeckY),
                        end = Offset(knobX, knobY),
                        strokeWidth = 5.2.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // Handle collar ring near rim
                    drawCircle(
                        color = strokeColor,
                        center = Offset(handleNeckX, handleNeckY),
                        radius = 4.2.dp.toPx()
                    )

                    // Round Knob / Ball at the tip of the handle (Base sphere)
                    drawCircle(
                        color = knobColor,
                        center = Offset(knobX, knobY),
                        radius = 6.5.dp.toPx()
                    )
                    drawCircle(
                        color = if (isDark) Color(0xFF38BDF8) else Color(0xFF0F172A),
                        center = Offset(knobX, knobY),
                        radius = 6.5.dp.toPx(),
                        style = Stroke(width = 1.3.dp.toPx())
                    )

                    // 3D Specular shine highlight on the knob
                    drawCircle(
                        color = Color.White.copy(alpha = if (isDark) 0.85f else 0.65f),
                        center = Offset(knobX - 2.dp.toPx(), knobY - 2.dp.toPx()),
                        radius = 2.dp.toPx()
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
                    style = Stroke(width = 3.2.dp.toPx())
                )
                drawCircle(
                    color = strokeColor.copy(alpha = 0.3f),
                    center = Offset(cx, cy),
                    radius = hubRadiusPx * 0.75f,
                    style = Stroke(width = 1.2.dp.toPx())
                )

                // Astrolabe 8-pointed Nautical Compass Star in Center Hub
                val starPoints = 8
                val starStep = (2 * PI / starPoints).toFloat()
                val rOuterStar = hubRadiusPx * 0.65f
                val rInnerStar = hubRadiusPx * 0.30f
                val starPath = Path()
                for (s in 0 until (starPoints * 2)) {
                    val a = s * (starStep / 2f) - (PI / 2).toFloat()
                    val r = if (s % 2 == 0) rOuterStar else rInnerStar
                    val px = cx + r * cos(a)
                    val py = cy + r * sin(a)
                    if (s == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
                }
                starPath.close()
                drawPath(
                    path = starPath,
                    color = (activeItem?.accentColor ?: hubBorder).copy(alpha = if (isDark) 0.50f else 0.35f)
                )

                // Outer Orbiting Satellite Particle
                val satAngle = Math.toRadians((currentRot * 1.5).toDouble()).toFloat()
                val satR = outerRimPx
                val satX = cx + satR * cos(satAngle)
                val satY = cy + satR * sin(satAngle)
                drawCircle(
                    color = (activeItem?.accentColor ?: Cyan).copy(alpha = 0.30f),
                    center = Offset(satX, satY),
                    radius = 8.dp.toPx()
                )
                drawCircle(
                    color = Color.White,
                    center = Offset(satX, satY),
                    radius = 3.2.dp.toPx()
                )
            }

            // 2. Action Node Badges mounted along the spokes
            items.forEachIndexed { i, item ->
                val itemAngleDeg = i * stepDeg + currentRot
                val itemAngleRad = Math.toRadians(itemAngleDeg.toDouble())

                // Badge offset relative to center
                val offsetX = (badgeRadius.value * cos(itemAngleRad).toFloat()).dp
                val offsetY = (badgeRadius.value * sin(itemAngleRad).toFloat()).dp

                val isThisSpinning = spinningItemId == item.id
                val isFocused = activeItem?.id == item.id
                val itemColor = item.accentColor

                val badgeBg = if (isDark) {
                    if (isThisSpinning) itemColor.copy(alpha = 0.38f)
                    else if (isFocused) itemColor.copy(alpha = 0.22f)
                    else Color(0xFF131722)
                } else {
                    if (isThisSpinning) itemColor.copy(alpha = 0.28f)
                    else if (isFocused) itemColor.copy(alpha = 0.16f)
                    else Color(0xFFFFFFFF)
                }
                val badgeBorder = if (isThisSpinning || isFocused) {
                    itemColor
                } else {
                    itemColor.copy(alpha = if (isDark) 0.65f else 0.50f)
                }
                val iconTint = if (isThisSpinning || isFocused) {
                    itemColor
                } else {
                    itemColor.copy(alpha = if (isDark) 0.95f else 0.90f)
                }

                // Interactive circular badge with vibrant individual accent color
                Surface(
                    modifier = Modifier
                        .offset(x = offsetX, y = offsetY)
                        .size(if (isThisSpinning) 52.dp else if (isFocused) 48.dp else 42.dp)
                        .scale(if (isThisSpinning) 1.25f else if (isFocused) 1.10f else 1f)
                        .clip(CircleShape)
                        .clickable(enabled = !isSpinning) {
                            // "oru itha select panna antha wheel one time spin agitu open aganum"
                            isSpinning = true
                            spinningItemId = item.id
                            try {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            } catch (ignored: Exception) {}

                            coroutineScope.launch {
                                // Trigger shockwave pulse
                                shockwaveAnim.snapTo(0f)
                                launch {
                                    shockwaveAnim.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
                                }

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
                    color = badgeBg,
                    border = BorderStroke(
                        width = if (isThisSpinning) 2.8.dp else if (isFocused) 2.4.dp else 1.8.dp,
                        color = badgeBorder
                    ),
                    shadowElevation = if (isThisSpinning) 14.dp else if (isFocused) 8.dp else 4.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    listOf(itemColor.copy(alpha = if (isFocused) 0.28f else 0.08f), Color.Transparent)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // Badge icon stays upright regardless of wheel rotation
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = iconTint,
                            modifier = Modifier.size(if (isThisSpinning) 26.dp else if (isFocused) 24.dp else 21.dp)
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
                        try {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        } catch (ignored: Exception) {}
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
                border = BorderStroke(2.8.dp, hubBorder),
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
                        modifier = Modifier.size(25.dp)
                    )
                }
            }
        }
    }
}
}

/**
 * Compact circular Ship's Helm icon button used in Frame 2 & Frame 5:
 * An 8-spoke miniature steering wheel outline with subtle interactive sheen.
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
    val effectiveAccent = if (accentColor != Color.Black) accentColor else (if (isDark) Color(0xFF38BDF8) else Color(0xFF4F46E5))
    val hubColor = effectiveAccent

    val inf = rememberInfiniteTransition(label = "compact_helm")
    val pulseAlpha by inf.animateFloat(
        initialValue = 0.10f, targetValue = 0.28f,
        animationSpec = infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "cpa"
    )

    Surface(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = effectiveAccent.copy(alpha = if (isDark) 0.14f else 0.08f),
        border = BorderStroke(1.2.dp, effectiveAccent.copy(alpha = 0.45f)),
        shadowElevation = 3.dp
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

                // Outer ambient aura glow
                drawCircle(
                    color = effectiveAccent.copy(alpha = pulseAlpha),
                    radius = rOuter + handleLen + 2.dp.toPx()
                )

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
                modifier = Modifier.size(size * 0.34f)
            )
        }
    }
}
