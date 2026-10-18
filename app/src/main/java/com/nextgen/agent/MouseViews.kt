package com.nextgen.agent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.hypot

/**
 * Controller operating mode: Dedicated 1:1 Laptop Precision Trackpad
 */
enum class MousePadMode {
    TRACKPAD
}

enum class ScrollDirection {
    UP, DOWN, LEFT, RIGHT
}

enum class ControllerTouchTarget {
    NONE,
    TRACKPAD_SURFACE,
    TRACKPAD_SCROLL_STRIP,
    BTN_L_CLICK,
    BTN_DRAG_TOGGLE,
    BTN_SCROLL_LEFT,
    BTN_SCROLL_UP,
    BTN_SCROLL_DOWN,
    BTN_SCROLL_RIGHT,
    BTN_R_CLICK,
    BTN_RESIZE,
    BTN_SPEED_GEAR,
    BTN_AI_SNAP,
    BTN_CLOSE,
    HEADER_DRAG,
    STATUS_ACCESSIBILITY
}

// =========================================================================
// 1. CUSTOM VIEW: FLOATING CYBER BALL
// =========================================================================
class FloatingBallView(context: Context) : View(context) {
    var onBallTapped: (() -> Unit)? = null
    var onBallDragged: ((dx: Int, dy: Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var isDragging = false
    private var downTime = 0L

    private var isControllerActive = false
    private var logoBitmap: Bitmap? = null

    // Pre-allocated Drawing Objects (Zero GC allocations in onDraw)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F5060A14")
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5.5f * density
        maskFilter = BlurMaskFilter(8f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(45, 255, 255, 255)
    }

    private val beaconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.FILL
    }

    private val beaconGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.FILL
        alpha = 110
        maskFilter = BlurMaskFilter(4f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()
    private val logoDstRect = RectF()
    private val sheenRect = RectF()

    init {
        loadLogo()
    }

    fun setControllerActive(active: Boolean) {
        if (isControllerActive != active) {
            isControllerActive = active
            invalidate()
        }
    }

    private fun loadLogo() {
        try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            logoBitmap = BitmapFactory.decodeResource(resources, R.drawable.app_logo, opts)
                ?: BitmapFactory.decodeResource(resources, R.drawable.ic_launcher, opts)
        } catch (ignored: Exception) {}
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val radius = (Math.min(w, h) / 2f) - (8f * density)

        clipPath.reset()
        clipPath.addCircle(cx, cy, (radius - 3.5f).coerceAtLeast(1f), Path.Direction.CW)

        val pad = radius * 0.16f
        logoDstRect.set(cx - radius + pad, cy - radius + pad, cx + radius - pad, cy + radius - pad)
        sheenRect.set(cx - radius * 0.7f, cy - radius * 0.9f, cx + radius * 0.7f, cy - radius * 0.1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = (Math.min(w, h) / 2f) - (8f * density)

        // 1. Deep Obsidian glass sphere
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // 2. App logo
        logoBitmap?.let { bmp ->
            val saveCount = canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(bmp, null, logoDstRect, logoPaint)
            // Subtle glass specular reflection
            canvas.drawOval(sheenRect, sheenPaint)
            canvas.restoreToCount(saveCount)
        }

        // 3. Glowing Outer Cyber Ring
        val accentColor = if (isControllerActive) {
            Color.parseColor("#00E5FF") // Active Cyan
        } else {
            Color.parseColor("#6366F1") // Standby Indigo
        }

        glowPaint.color = accentColor
        glowPaint.alpha = 110
        canvas.drawCircle(cx, cy, radius, glowPaint)

        ringPaint.color = accentColor
        ringPaint.alpha = 240
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // 4. Active Green Beacon
        if (isControllerActive) {
            val bx = cx + radius * 0.68f
            val by = cy - radius * 0.68f
            canvas.drawCircle(bx, by, 6f * density, beaconGlowPaint)
            canvas.drawCircle(bx, by, 3.8f * density, beaconPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                isDragging = false
                downTime = System.currentTimeMillis()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastRawX).toInt()
                val dy = (event.rawY - lastRawY).toInt()
                val totalDist = hypot((event.rawX - downRawX).toDouble(), (event.rawY - downRawY).toDouble())
                if (totalDist > touchSlop) {
                    isDragging = true
                }
                if (isDragging) {
                    onBallDragged?.invoke(dx, dy)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - downTime
                val totalDist = hypot((event.rawX - downRawX).toDouble(), (event.rawY - downRawY).toDouble())
                if (!isDragging && totalDist < touchSlop && duration < 350) {
                    onBallTapped?.invoke()
                }
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

// =========================================================================
// 2. CUSTOM VIEW: ULTRA-MODERN AERO CYBER MOUSE POINTER
// =========================================================================
class MousePointerView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val cursorPath = Path()

    private var pointerScale = 1.0f
    private var isDragActive = false
    private var isMagneticLocked = false

    // Pre-allocated Drawing Paints (Zero GC allocations in onDraw)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#090D18")
        strokeJoin = Paint.Join.ROUND
    }

    private val innerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
        color = Color.parseColor("#8000E5FF")
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(100, 0, 0, 0)
        maskFilter = BlurMaskFilter(2.4f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        color = Color.parseColor("#00E5FF")
    }

    private val rippleAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3300E5FF")
        maskFilter = BlurMaskFilter(6f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val laserHotspotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF")
    }

    private val laserCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val dragBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E6F59E0B")
    }

    private val dragBadgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#FFFFFF")
    }

    private val dragBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#080D1A")
        textSize = 8.5f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val lockReticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = Color.parseColor("#00E5FF")
    }

    private val dragBadgeRect = RectF()

    private var rippleRadius = 0f
    private var rippleAlpha = 0
    private var cursorBounceScale = 1.0f

    init {
        buildCursorPath()
    }

    private fun buildCursorPath() {
        val s = density
        cursorPath.reset()
        // Aerodynamic Precision Arrow Tip at (0, 0)
        cursorPath.moveTo(0f, 0f)
        cursorPath.lineTo(0f, 19.5f * s)
        cursorPath.lineTo(5.0f * s, 15.5f * s)
        cursorPath.lineTo(8.4f * s, 22.8f * s)
        cursorPath.lineTo(11.4f * s, 21.4f * s)
        cursorPath.lineTo(8.0f * s, 14.5f * s)
        cursorPath.lineTo(13.6f * s, 14.5f * s)
        cursorPath.close()

        dragBadgeRect.set(15f * s, 6f * s, 54f * s, 19f * s)
    }

    fun setPointerScale(scale: Float) {
        pointerScale = scale.coerceIn(0.5f, 2.5f)
        invalidate()
    }

    fun setPointerTheme(color: String) {
        when (color.lowercase()) {
            "cyan" -> {
                fillPaint.color = Color.parseColor("#00E5FF")
                outlinePaint.color = Color.parseColor("#002830")
                innerGlowPaint.color = Color.parseColor("#FFFFFF")
                ripplePaint.color = Color.parseColor("#00E5FF")
                laserHotspotPaint.color = Color.parseColor("#00E5FF")
            }
            "green" -> {
                fillPaint.color = Color.parseColor("#10B981")
                outlinePaint.color = Color.parseColor("#043B2C")
                innerGlowPaint.color = Color.parseColor("#6EE7B7")
                ripplePaint.color = Color.parseColor("#10B981")
                laserHotspotPaint.color = Color.parseColor("#10B981")
            }
            "black" -> {
                fillPaint.color = Color.parseColor("#0F172A")
                outlinePaint.color = Color.parseColor("#F8FAFC")
                innerGlowPaint.color = Color.parseColor("#64748B")
                ripplePaint.color = Color.parseColor("#94A3B8")
                laserHotspotPaint.color = Color.parseColor("#00E5FF")
            }
            else -> { // "white"
                fillPaint.color = Color.WHITE
                outlinePaint.color = Color.parseColor("#090D18")
                innerGlowPaint.color = Color.parseColor("#8000E5FF")
                ripplePaint.color = Color.parseColor("#00E5FF")
                laserHotspotPaint.color = Color.parseColor("#00E5FF")
            }
        }
        invalidate()
    }

    fun setMagneticLocked(locked: Boolean) {
        if (isMagneticLocked != locked) {
            isMagneticLocked = locked
            invalidate()
        }
    }

    fun setDragActive(active: Boolean) {
        if (isDragActive != active) {
            isDragActive = active
            invalidate()
        }
    }

    fun triggerClickAnimation() {
        ValueAnimator.ofFloat(0.88f, 1.0f).apply {
            duration = 160
            interpolator = OvershootInterpolator(1.8f)
            addUpdateListener {
                cursorBounceScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        ValueAnimator.ofFloat(0f, 18f * density).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                rippleRadius = anim.animatedValue as Float
                rippleAlpha = ((1f - anim.animatedFraction) * 220).toInt()
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Click Sonar Ripple Animation at Hotspot Tip (0, 0)
        if (rippleRadius > 0f && rippleAlpha > 0) {
            rippleAuraPaint.alpha = rippleAlpha / 2
            canvas.drawCircle(0f, 0f, rippleRadius * 1.25f, rippleAuraPaint)
            ripplePaint.alpha = rippleAlpha
            canvas.drawCircle(0f, 0f, rippleRadius, ripplePaint)
        }

        canvas.save()
        val finalScale = cursorBounceScale * pointerScale
        canvas.scale(finalScale, finalScale, 0f, 0f)

        // 2. Drop Shadow
        canvas.save()
        canvas.translate(1.4f * density, 2.0f * density)
        canvas.drawPath(cursorPath, shadowPaint)
        canvas.restore()

        // 3. Arrow Body & Inner Glow
        canvas.drawPath(cursorPath, fillPaint)
        canvas.drawPath(cursorPath, innerGlowPaint)

        // 4. Sharp Precision Outline
        canvas.drawPath(cursorPath, outlinePaint)

        // 5. Pinpoint Laser Reticle at Tip (0, 0)
        canvas.drawCircle(0f, 0f, 2.2f * density, laserHotspotPaint)
        canvas.drawCircle(0f, 0f, 1.0f * density, laserCorePaint)

        // 6. Active Drag Floating Badge
        if (isDragActive) {
            canvas.drawRoundRect(dragBadgeRect, 6f * density, 6f * density, dragBadgeBgPaint)
            canvas.drawRoundRect(dragBadgeRect, 6f * density, 6f * density, dragBadgeStrokePaint)
            canvas.drawText("✋ DRAG", dragBadgeRect.centerX(), dragBadgeRect.centerY() + (3.0f * density), dragBadgeTextPaint)
        }

        // 7. AI Magnetic Lock Reticle
        if (isMagneticLocked) {
            canvas.drawCircle(0f, 0f, 8.5f * density, lockReticlePaint)
        }

        canvas.restore()
    }
}

// =========================================================================
// 3. CUSTOM VIEW: HIGH-TECH CYBER TRACKPAD CONTROLLER HUD
// =========================================================================
class CyberMouseControllerView(context: Context) : View(context) {
    var onTrackpadMove: ((dx: Float, dy: Float) -> Unit)? = null
    var onLeftClick: (() -> Unit)? = null
    var onRightClick: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onLongClick: (() -> Unit)? = null
    var onDragToggle: (() -> Unit)? = null
    var onScroll: ((direction: ScrollDirection) -> Unit)? = null
    var onResizeRequested: (() -> Unit)? = null
    var onSpeedGearToggled: (() -> Unit)? = null
    var onAiSnapRequested: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var onControllerDrag: ((dx: Int, dy: Int) -> Unit)? = null

    var isHyperSpeed: Boolean = false
    var currentPadMode: MousePadMode = MousePadMode.TRACKPAD
    var isDragActive: Boolean = false
    var padAlpha: Float = 0.94f

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Touch tracking
    private var activeTarget = ControllerTouchTarget.NONE
    private var pressedTarget = ControllerTouchTarget.NONE
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downTime = 0L
    private var lastTapTime = 0L

    // Multi-touch tracking
    private var isMultiTouch = false
    private var lastMultiY = 0f
    private var lastMultiX = 0f
    private var multiTouchAccumDy = 0f
    private var multiTouchAccumDx = 0f

    // Visual touch indicator
    private var isTouchingPad = false
    private var padTouchX = 0f
    private var padTouchY = 0f
    private var padTouch2X = 0f
    private var padTouch2Y = 0f

    // Scroll strip thumb tracking
    private var scrollThumbY = -1f

    // Telemetry text
    private var coordText = "X: 540  Y: 1100"
    private var isAccessibilityReady = false

    // Auto-repeat on held directional buttons
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatTarget: ControllerTouchTarget? = null
    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeatTarget?.let { target ->
                when (target) {
                    ControllerTouchTarget.BTN_SCROLL_UP -> onScroll?.invoke(ScrollDirection.UP)
                    ControllerTouchTarget.BTN_SCROLL_DOWN -> onScroll?.invoke(ScrollDirection.DOWN)
                    ControllerTouchTarget.BTN_SCROLL_LEFT -> onScroll?.invoke(ScrollDirection.LEFT)
                    ControllerTouchTarget.BTN_SCROLL_RIGHT -> onScroll?.invoke(ScrollDirection.RIGHT)
                    else -> {}
                }
                repeatHandler.postDelayed(this, 150)
            }
        }
    }

    // =========================================================================
    // PRE-ALLOCATED DRAWING OBJECTS (ZERO ALLOCATIONS IN onDraw)
    // =========================================================================
    private val chassisRect = RectF()
    private val headerRect = RectF()
    private val modePillRect = RectF()
    private val padRect = RectF()
    private val scrollStripRect = RectF()
    private val thumbRect = RectF()

    // Header buttons
    private val rClose = RectF()
    private val rResize = RectF()
    private val rGear = RectF()
    private val rAiSnap = RectF()

    // Dock Buttons
    private val rClick = RectF()
    private val rDrag = RectF()
    private val rLeft = RectF()
    private val rUp = RectF()
    private val rDown = RectF()
    private val rRight = RectF()
    private val rBack = RectF()

    // Paints
    private val chassisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F5070B16")
    }

    private val chassisStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = Color.parseColor("#00E5FF")
    }

    private val cornerAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        color = Color.parseColor("#00E5FF")
        strokeCap = Paint.Cap.ROUND
    }

    private val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2200E5FF")
    }

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2600E5FF")
    }

    private val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#00E5FF")
    }

    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        style = Paint.Style.FILL
    }

    private val padBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E6040710")
    }

    private val padBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.parseColor("#243047")
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1800E5FF")
        style = Paint.Style.FILL
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D94A3B8")
        textSize = 9.5f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val touchAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2E00E5FF")
        maskFilter = BlurMaskFilter(6f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val touchRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        color = Color.parseColor("#00E5FF")
    }

    private val multiLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8000E5FF")
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }

    private val scrollStripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A00E5FF")
    }

    private val stripBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#3300E5FF")
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF")
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 9.5f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val telemetryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 9.5f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        textSize = 9.5f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val dragBannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        textSize = 9.5f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    // Reusable Dock Button Drawing Paints
    private val btnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val btnBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val btnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    init {
        refreshAccessibilityStatus()
    }

    fun refreshAccessibilityStatus() {
        val ready = AgentAccessibilityService.instance != null
        if (isAccessibilityReady != ready) {
            isAccessibilityReady = ready
            invalidate()
        }
    }

    fun updateCursorCoords(x: Int, y: Int) {
        val newText = "X: $x  Y: $y"
        if (coordText != newText) {
            coordText = newText
            invalidate()
        }
    }

    fun setDragState(active: Boolean) {
        if (isDragActive != active) {
            isDragActive = active
            invalidate()
        }
    }

    fun applyCustomization(alpha: Float) {
        padAlpha = alpha.coerceIn(0.4f, 1.0f)
        chassisPaint.alpha = (padAlpha * 245).toInt().coerceIn(80, 255)
        padBgPaint.alpha = (padAlpha * 235).toInt().coerceIn(70, 255)
        invalidate()
    }

    private fun getHeaderHeight() = 36f * density
    private fun getStatusBarHeight() = 18f * density
    private fun getDockHeight() = 46f * density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wf = w.toFloat()
        val hf = h.toFloat()

        // 1. Chassis & Header
        chassisRect.set(0f, 0f, wf, hf)
        headerRect.set(0f, 0f, wf, getHeaderHeight())
        modePillRect.set(6f * density, 5f * density, 115f * density, getHeaderHeight() - (5f * density))

        // 2. Header Buttons
        rClose.set(wf - (34f * density), 4f * density, wf - (6f * density), getHeaderHeight() - (4f * density))
        rResize.set(rClose.left - (30f * density), 4f * density, rClose.left - (4f * density), getHeaderHeight() - (4f * density))
        rGear.set(rResize.left - (38f * density), 4f * density, rResize.left - (4f * density), getHeaderHeight() - (4f * density))
        rAiSnap.set(rGear.left - (30f * density), 4f * density, rGear.left - (4f * density), getHeaderHeight() - (4f * density))

        // 3. Trackpad Surface & Scroll Strip
        val padTop = getHeaderHeight() + getStatusBarHeight() + (4f * density)
        val padBot = hf - getDockHeight() - (6f * density)
        padRect.set(8f * density, padTop, wf - (8f * density), padBot)

        val stripWidth = 24f * density
        scrollStripRect.set(padRect.right - stripWidth, padRect.top, padRect.right, padRect.bottom)

        // 4. Dock Buttons Layout
        val dockTop = hf - getDockHeight()
        val dockBot = hf - (6f * density)
        val startX = 8f * density
        val padW = wf - (16f * density)
        val gap = 3f * density
        val totalGaps = 6f * gap
        val availW = (padW - totalGaps).coerceAtLeast(10f)

        val sumWeights = 9.6f
        fun bw(weight: Float) = (availW * (weight / sumWeights))

        val wClick = bw(2.2f)
        val wDrag = bw(1.8f)
        val wLeft = bw(1.0f)
        val wUp = bw(1.0f)
        val wDown = bw(1.0f)
        val wRight = bw(1.0f)

        var curX = startX
        rClick.set(curX, dockTop, curX + wClick, dockBot); curX += wClick + gap
        rDrag.set(curX, dockTop, curX + wDrag, dockBot); curX += wDrag + gap
        rLeft.set(curX, dockTop, curX + wLeft, dockBot); curX += wLeft + gap
        rUp.set(curX, dockTop, curX + wUp, dockBot); curX += wUp + gap
        rDown.set(curX, dockTop, curX + wDown, dockBot); curX += wDown + gap
        rRight.set(curX, dockTop, curX + wRight, dockBot); curX += wRight + gap
        rBack.set(curX, dockTop, wf - (8f * density), dockBot)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = 18f * density

        // 1. Futuristic Glass Chassis & Cyber Accent
        canvas.drawRoundRect(chassisRect, corner, corner, chassisPaint)
        canvas.drawRoundRect(chassisRect, corner, corner, chassisStrokePaint)

        // High-Tech Corner Laser Brackets
        val brkLen = 14f * density
        canvas.drawLine(chassisRect.left + 4f, chassisRect.top + brkLen, chassisRect.left + 4f, chassisRect.top + 4f, cornerAccentPaint)
        canvas.drawLine(chassisRect.left + 4f, chassisRect.top + 4f, chassisRect.left + brkLen, chassisRect.top + 4f, cornerAccentPaint)
        canvas.drawLine(chassisRect.right - brkLen, chassisRect.top + 4f, chassisRect.right - 4f, chassisRect.top + 4f, cornerAccentPaint)
        canvas.drawLine(chassisRect.right - 4f, chassisRect.top + 4f, chassisRect.right - 4f, chassisRect.top + brkLen, cornerAccentPaint)

        // 2. Top Header Bar
        canvas.drawRoundRect(headerRect, corner, corner, headerBgPaint)

        // Trackpad Pro Badge
        canvas.drawRoundRect(modePillRect, 9f * density, 9f * density, pillPaint)
        canvas.drawRoundRect(modePillRect, 9f * density, 9f * density, pillStroke)

        textPaint.textSize = 10f * density
        textPaint.color = Color.parseColor("#00E5FF")
        canvas.drawText("⚡ TRACKPAD", modePillRect.centerX(), modePillRect.centerY() + (3.5f * density), textPaint)

        // Center grip dots
        val gripY = getHeaderHeight() / 2f
        for (i in -2..2) {
            canvas.drawCircle(w / 2f + (i * 6f * density), gripY, 1.6f * density, gripPaint)
        }

        // Header Action Buttons: [ 🎯 AI SNAP ], [ ⚡ 1X / 🚀 2X ], [ ⤢ RESIZE ], [ ✕ CLOSE ]
        drawHeaderBtn(canvas, rAiSnap, "🎯", isPressed = (pressedTarget == ControllerTouchTarget.BTN_AI_SNAP), accentHex = "#A855F7")
        drawHeaderBtn(canvas, rGear, if (isHyperSpeed) "🚀2X" else "⚡1X", isPressed = (pressedTarget == ControllerTouchTarget.BTN_SPEED_GEAR), accentHex = if (isHyperSpeed) "#F59E0B" else "#00E5FF")
        drawHeaderBtn(canvas, rResize, "⤢", isPressed = (pressedTarget == ControllerTouchTarget.BTN_RESIZE), accentHex = "#00E5FF")
        drawHeaderBtn(canvas, rClose, "✕", isPressed = (pressedTarget == ControllerTouchTarget.BTN_CLOSE), accentHex = "#EF4444")

        // 3. Status Bar: Drag State, Accessibility, & Coordinates
        val statusY = getHeaderHeight() + (13f * density)
        if (!isAccessibilityReady) {
            canvas.drawText("⚠️ Accessibility OFF - Tap to Enable", w / 2f, statusY, warnPaint)
        } else if (isDragActive) {
            canvas.drawText("✋ DRAG HELD - TAP TO DROP", w / 2f, statusY, dragBannerPaint)
        } else {
            canvas.drawText("⚡ READY  •  $coordText", w / 2f, statusY, telemetryPaint)
        }

        // 4. Center Trackpad Surface Area
        canvas.drawRoundRect(padRect, 10f * density, 10f * density, padBgPaint)
        canvas.drawRoundRect(padRect, 10f * density, 10f * density, padBorderPaint)

        // Precision Grid Matrix Dots (zero heap allocation)
        val stepX = (padRect.width() - 32f * density) / 4f
        val stepY = padRect.height() / 4f
        for (gx in 1..3) {
            for (gy in 1..3) {
                canvas.drawCircle(padRect.left + (gx * stepX), padRect.top + (gy * stepY), 1.2f * density, dotPaint)
            }
        }

        // Instructions watermark
        canvas.drawText("Glide: Move  •  Tap: Click  •  2-Finger: Scroll", padRect.centerX() - (10f * density), padRect.centerY() + (3.5f * density), guidePaint)

        // Dynamic Touch Feedback Indicator
        if (isTouchingPad) {
            canvas.drawCircle(padTouchX, padTouchY, 18f * density, touchAuraPaint)
            canvas.drawCircle(padTouchX, padTouchY, 12f * density, touchRingPaint)
            canvas.drawCircle(padTouchX, padTouchY, 3.5f * density, touchRingPaint)

            if (isMultiTouch) {
                canvas.drawCircle(padTouch2X, padTouch2Y, 18f * density, touchAuraPaint)
                canvas.drawCircle(padTouch2X, padTouch2Y, 12f * density, touchRingPaint)
                canvas.drawCircle(padTouch2X, padTouch2Y, 3.5f * density, touchRingPaint)
                canvas.drawLine(padTouchX, padTouchY, padTouch2X, padTouch2Y, multiLinePaint)
            }
        }

        // Right-edge Dedicated Scroll Strip
        canvas.drawRoundRect(scrollStripRect, 6f * density, 6f * density, scrollStripPaint)
        canvas.drawRoundRect(scrollStripRect, 6f * density, 6f * density, stripBorderPaint)

        // Scroll thumb pill indicator
        val thumbY = if (scrollThumbY > 0f) scrollThumbY else scrollStripRect.centerY()
        thumbRect.set(scrollStripRect.left + (2f * density), thumbY - (10f * density), scrollStripRect.right - (2f * density), thumbY + (10f * density))
        canvas.drawRoundRect(thumbRect, 4f * density, 4f * density, thumbPaint)

        canvas.drawText("▲", scrollStripRect.centerX(), scrollStripRect.top + (12f * density), arrowPaint)
        canvas.drawText("▼", scrollStripRect.centerX(), scrollStripRect.bottom - (5f * density), arrowPaint)

        // 5. Bottom Hardware-Style Cyber Dock with 7 Navigation & Action Buttons
        drawDockButton(canvas, rClick, "🖱️ CLICK", "#00E5FF", "#080D1A", "#00E5FF", (pressedTarget == ControllerTouchTarget.BTN_L_CLICK))
        drawDockButton(
            canvas, rDrag, if (isDragActive) "✋ DROP" else "✋ DRAG",
            if (isDragActive) "#F59E0B" else "#141D2B",
            if (isDragActive) "#080D1A" else "#F59E0B",
            "#F59E0B",
            (pressedTarget == ControllerTouchTarget.BTN_DRAG_TOGGLE)
        )
        drawDockButton(canvas, rLeft, "◀", "#111827", "#38BDF8", "#334155", (pressedTarget == ControllerTouchTarget.BTN_SCROLL_LEFT))
        drawDockButton(canvas, rUp, "▲", "#111827", "#38BDF8", "#334155", (pressedTarget == ControllerTouchTarget.BTN_SCROLL_UP))
        drawDockButton(canvas, rDown, "▼", "#111827", "#38BDF8", "#334155", (pressedTarget == ControllerTouchTarget.BTN_SCROLL_DOWN))
        drawDockButton(canvas, rRight, "▶", "#111827", "#38BDF8", "#334155", (pressedTarget == ControllerTouchTarget.BTN_SCROLL_RIGHT))
        drawDockButton(canvas, rBack, "🔙 BACK", "#1E293B", "#F8FAFC", "#475569", (pressedTarget == ControllerTouchTarget.BTN_R_CLICK))
    }

    private fun drawHeaderBtn(canvas: Canvas, rect: RectF, text: String, isPressed: Boolean, accentHex: String) {
        val corner = 6f * density
        val col = Color.parseColor(accentHex)
        btnBgPaint.color = if (isPressed) Color.argb(60, Color.red(col), Color.green(col), Color.blue(col)) else Color.parseColor("#152033")
        btnBorderPaint.color = if (isPressed) col else Color.parseColor("#334155")
        btnBorderPaint.strokeWidth = 1f * density
        btnTextPaint.color = col
        btnTextPaint.textSize = if (text.length > 2) 8.5f * density else 12f * density

        canvas.drawRoundRect(rect, corner, corner, btnBgPaint)
        canvas.drawRoundRect(rect, corner, corner, btnBorderPaint)
        canvas.drawText(text, rect.centerX(), rect.centerY() + (btnTextPaint.textSize * 0.35f), btnTextPaint)
    }

    private fun drawDockButton(
        canvas: Canvas,
        rect: RectF,
        text: String,
        bgColorHex: String,
        textColorHex: String,
        borderColorHex: String,
        isPressed: Boolean = false
    ) {
        val corner = 8f * density
        btnBgPaint.color = if (isPressed) Color.parseColor("#38BDF8") else Color.parseColor(bgColorHex)
        if (isPressed && bgColorHex != "#00E5FF") btnBgPaint.alpha = 140 else btnBgPaint.alpha = 255

        btnBorderPaint.strokeWidth = (if (isPressed) 1.8f else 1f) * density
        btnBorderPaint.color = if (isPressed) Color.parseColor("#00E5FF") else Color.parseColor(borderColorHex)

        btnTextPaint.color = if (isPressed) Color.WHITE else Color.parseColor(textColorHex)
        btnTextPaint.textSize = (if (text.length > 2) 9.5f else 13f) * density

        canvas.drawRoundRect(rect, corner, corner, btnBgPaint)
        canvas.drawRoundRect(rect, corner, corner, btnBorderPaint)
        canvas.drawText(text, rect.centerX(), rect.centerY() + (btnTextPaint.textSize * 0.35f), btnTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                downTime = System.currentTimeMillis()

                // 1. Header controls
                if (y <= getHeaderHeight()) {
                    if (rClose.contains(x, y)) {
                        activeTarget = ControllerTouchTarget.BTN_CLOSE
                        pressedTarget = activeTarget
                    } else if (rResize.contains(x, y)) {
                        activeTarget = ControllerTouchTarget.BTN_RESIZE
                        pressedTarget = activeTarget
                    } else if (rGear.contains(x, y)) {
                        activeTarget = ControllerTouchTarget.BTN_SPEED_GEAR
                        pressedTarget = activeTarget
                    } else if (rAiSnap.contains(x, y)) {
                        activeTarget = ControllerTouchTarget.BTN_AI_SNAP
                        pressedTarget = activeTarget
                    } else {
                        activeTarget = ControllerTouchTarget.HEADER_DRAG
                    }
                }
                // 2. Status bar tap
                else if (y in getHeaderHeight()..(getHeaderHeight() + getStatusBarHeight())) {
                    if (!isAccessibilityReady) {
                        activeTarget = ControllerTouchTarget.STATUS_ACCESSIBILITY
                    } else {
                        activeTarget = ControllerTouchTarget.HEADER_DRAG
                    }
                }
                // 3. Center Pad Area
                else if (padRect.contains(x, y)) {
                    if (scrollStripRect.contains(x, y)) {
                        activeTarget = ControllerTouchTarget.TRACKPAD_SCROLL_STRIP
                        scrollThumbY = y
                    } else {
                        activeTarget = ControllerTouchTarget.TRACKPAD_SURFACE
                        isTouchingPad = true
                        padTouchX = x
                        padTouchY = y
                    }
                }
                // 4. Bottom Dock 7 Buttons
                else if (y >= (height - getDockHeight())) {
                    when {
                        rClick.contains(x, y) -> activeTarget = ControllerTouchTarget.BTN_L_CLICK
                        rDrag.contains(x, y) -> activeTarget = ControllerTouchTarget.BTN_DRAG_TOGGLE
                        rLeft.contains(x, y) -> {
                            activeTarget = ControllerTouchTarget.BTN_SCROLL_LEFT
                            onScroll?.invoke(ScrollDirection.LEFT)
                            repeatTarget = activeTarget
                            repeatHandler.postDelayed(repeatRunnable, 340)
                        }
                        rUp.contains(x, y) -> {
                            activeTarget = ControllerTouchTarget.BTN_SCROLL_UP
                            onScroll?.invoke(ScrollDirection.UP)
                            repeatTarget = activeTarget
                            repeatHandler.postDelayed(repeatRunnable, 340)
                        }
                        rDown.contains(x, y) -> {
                            activeTarget = ControllerTouchTarget.BTN_SCROLL_DOWN
                            onScroll?.invoke(ScrollDirection.DOWN)
                            repeatTarget = activeTarget
                            repeatHandler.postDelayed(repeatRunnable, 340)
                        }
                        rRight.contains(x, y) -> {
                            activeTarget = ControllerTouchTarget.BTN_SCROLL_RIGHT
                            onScroll?.invoke(ScrollDirection.RIGHT)
                            repeatTarget = activeTarget
                            repeatHandler.postDelayed(repeatRunnable, 340)
                        }
                        else -> activeTarget = ControllerTouchTarget.BTN_R_CLICK
                    }
                    pressedTarget = activeTarget
                } else {
                    activeTarget = ControllerTouchTarget.HEADER_DRAG
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (activeTarget == ControllerTouchTarget.TRACKPAD_SURFACE && event.pointerCount >= 2) {
                    isMultiTouch = true
                    lastMultiY = (event.getY(0) + event.getY(1)) / 2f
                    lastMultiX = (event.getX(0) + event.getX(1)) / 2f
                    multiTouchAccumDy = 0f
                    multiTouchAccumDx = 0f
                    padTouchX = event.getX(0)
                    padTouchY = event.getY(0)
                    padTouch2X = event.getX(1)
                    padTouch2Y = event.getY(1)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val rawDx = event.rawX - lastRawX
                val rawDy = event.rawY - lastRawY

                if (isMultiTouch && event.pointerCount >= 2) {
                    val curMultiY = (event.getY(0) + event.getY(1)) / 2f
                    val curMultiX = (event.getX(0) + event.getX(1)) / 2f
                    val mdy = curMultiY - lastMultiY
                    val mdx = curMultiX - lastMultiX
                    multiTouchAccumDy += mdy
                    multiTouchAccumDx += mdx
                    lastMultiY = curMultiY
                    lastMultiX = curMultiX

                    padTouchX = event.getX(0)
                    padTouchY = event.getY(0)
                    padTouch2X = event.getX(1)
                    padTouch2Y = event.getY(1)

                    if (Math.abs(multiTouchAccumDy) >= (18f * density)) {
                        onScroll?.invoke(if (multiTouchAccumDy > 0) ScrollDirection.UP else ScrollDirection.DOWN)
                        multiTouchAccumDy = 0f
                    } else if (Math.abs(multiTouchAccumDx) >= (22f * density)) {
                        onScroll?.invoke(if (multiTouchAccumDx > 0) ScrollDirection.LEFT else ScrollDirection.RIGHT)
                        multiTouchAccumDx = 0f
                    }
                    invalidate()
                    return true
                }

                when (activeTarget) {
                    ControllerTouchTarget.TRACKPAD_SURFACE -> {
                        onTrackpadMove?.invoke(rawDx, rawDy)
                        padTouchX = x.coerceIn(padRect.left + 4f, padRect.right - 4f)
                        padTouchY = y.coerceIn(padRect.top + 4f, padRect.bottom - 4f)
                        invalidate()
                    }
                    ControllerTouchTarget.TRACKPAD_SCROLL_STRIP -> {
                        val stripDy = event.rawY - lastRawY
                        scrollThumbY = y.coerceIn(scrollStripRect.top + 10f, scrollStripRect.bottom - 10f)
                        if (Math.abs(stripDy) >= (12f * density)) {
                            onScroll?.invoke(if (stripDy > 0) ScrollDirection.UP else ScrollDirection.DOWN)
                            lastRawY = event.rawY
                        }
                        invalidate()
                    }
                    ControllerTouchTarget.HEADER_DRAG -> {
                        val dx = (event.rawX - lastRawX).toInt()
                        val dy = (event.rawY - lastRawY).toInt()
                        onControllerDrag?.invoke(dx, dy)
                    }
                    else -> {}
                }

                lastRawX = event.rawX
                lastRawY = event.rawY
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    isMultiTouch = false
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                repeatHandler.removeCallbacks(repeatRunnable)
                repeatTarget = null

                val duration = System.currentTimeMillis() - downTime
                val moved = hypot((event.rawX - downRawX).toDouble(), (event.rawY - downRawY).toDouble())

                when (activeTarget) {
                    ControllerTouchTarget.TRACKPAD_SURFACE -> {
                        if (!isMultiTouch && moved < touchSlop && duration < 240) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 280) {
                                onDoubleTap?.invoke()
                                lastTapTime = 0L
                            } else {
                                onLeftClick?.invoke()
                                lastTapTime = now
                            }
                        } else if (!isMultiTouch && moved < touchSlop && duration >= 500) {
                            onLongClick?.invoke()
                        }
                    }
                    ControllerTouchTarget.BTN_L_CLICK -> {
                        if (duration > 500) {
                            onLongClick?.invoke()
                        } else {
                            onLeftClick?.invoke()
                        }
                    }
                    ControllerTouchTarget.BTN_DRAG_TOGGLE -> {
                        onDragToggle?.invoke()
                    }
                    ControllerTouchTarget.BTN_R_CLICK -> {
                        onRightClick?.invoke()
                    }
                    ControllerTouchTarget.BTN_RESIZE -> {
                        onResizeRequested?.invoke()
                    }
                    ControllerTouchTarget.BTN_SPEED_GEAR -> {
                        onSpeedGearToggled?.invoke()
                    }
                    ControllerTouchTarget.BTN_AI_SNAP -> {
                        onAiSnapRequested?.invoke()
                    }
                    ControllerTouchTarget.BTN_CLOSE -> {
                        onDismiss?.invoke()
                    }
                    ControllerTouchTarget.STATUS_ACCESSIBILITY -> {
                        AgentAccessibilityService.openAccessibilitySettings(context)
                    }
                    else -> {}
                }

                activeTarget = ControllerTouchTarget.NONE
                pressedTarget = ControllerTouchTarget.NONE
                isTouchingPad = false
                isMultiTouch = false
                scrollThumbY = -1f
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
