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
    BTN_CLOSE,
    HEADER_DRAG,
    STATUS_ACCESSIBILITY
}

// =========================================================================
// 1. CUSTOM VIEW: FLOATING BALL
// =========================================================================
class FloatingBallView(context: Context) : View(context) {
    var onBallTapped: (() -> Unit)? = null
    var onBallDragged: ((dx: Int, dy: Int) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var isDragging = false
    private var downTime = 0L

    private var isControllerActive = false
    private var logoBitmap: Bitmap? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.parseColor("#EE0B0F19")
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.8f * resources.displayMetrics.density
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * resources.displayMetrics.density
        maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
    }

    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        loadLogo()
    }

    fun setControllerActive(active: Boolean) {
        isControllerActive = active
        invalidate()
    }

    private fun loadLogo() {
        try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            logoBitmap = BitmapFactory.decodeResource(resources, R.drawable.app_logo, opts)
                ?: BitmapFactory.decodeResource(resources, R.drawable.ic_launcher, opts)
        } catch (ignored: Exception) {}
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = (Math.min(w, h) / 2f) - (8f * resources.displayMetrics.density)

        // Deep obsidian sphere
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // App logo
        logoBitmap?.let { bmp ->
            val saveCount = canvas.save()
            val clipPath = android.graphics.Path().apply {
                addCircle(cx, cy, radius - 4f, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            val pad = radius * 0.16f
            val dstRect = RectF(cx - radius + pad, cy - radius + pad, cx + radius - pad, cy + radius - pad)
            canvas.drawBitmap(bmp, null, dstRect, logoPaint)
            canvas.restoreToCount(saveCount)
        }

        // Glowing Outer Cyber Ring
        val accentColor = if (isControllerActive) {
            android.graphics.Color.parseColor("#00E5FF") // Active Cyan
        } else {
            android.graphics.Color.parseColor("#6366F1") // Standby Indigo
        }

        glowPaint.color = accentColor
        glowPaint.alpha = 110
        canvas.drawCircle(cx, cy, radius, glowPaint)

        ringPaint.color = accentColor
        ringPaint.alpha = 245
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // Active Green Beacon
        if (isControllerActive) {
            val beaconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#10B981")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx + radius * 0.68f, cy - radius * 0.68f, 4f * resources.displayMetrics.density, beaconPaint)
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
// 2. CUSTOM VIEW: AUTHENTIC NATIVE MOUSE POINTER
// =========================================================================
class MousePointerView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val cursorPath = android.graphics.Path()

    private var pointerScale = 1.0f
    private var isDragActive = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.WHITE
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = android.graphics.Color.parseColor("#111622")
        strokeJoin = Paint.Join.ROUND
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.argb(80, 0, 0, 0)
        maskFilter = BlurMaskFilter(2f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        color = android.graphics.Color.parseColor("#00E5FF")
    }

    private var rippleRadius = 0f
    private var rippleAlpha = 0
    private var cursorBounceScale = 1.0f

    init {
        buildCursorPath()
    }

    private fun buildCursorPath() {
        val s = density
        cursorPath.reset()
        cursorPath.moveTo(0f, 0f)
        cursorPath.lineTo(0f, 18.5f * s)
        cursorPath.lineTo(4.6f * s, 14.8f * s)
        cursorPath.lineTo(7.6f * s, 21.8f * s)
        cursorPath.lineTo(10.2f * s, 20.6f * s)
        cursorPath.lineTo(7.2f * s, 13.8f * s)
        cursorPath.lineTo(12.8f * s, 13.8f * s)
        cursorPath.close()
    }

    fun setPointerScale(scale: Float) {
        pointerScale = scale.coerceIn(0.5f, 2.5f)
        invalidate()
    }

    fun setPointerTheme(color: String) {
        when (color.lowercase()) {
            "cyan" -> {
                fillPaint.color = android.graphics.Color.parseColor("#00E5FF")
                outlinePaint.color = android.graphics.Color.parseColor("#002830")
                ripplePaint.color = android.graphics.Color.parseColor("#00E5FF")
            }
            "green" -> {
                fillPaint.color = android.graphics.Color.parseColor("#10B981")
                outlinePaint.color = android.graphics.Color.parseColor("#043B2C")
                ripplePaint.color = android.graphics.Color.parseColor("#10B981")
            }
            "black" -> {
                fillPaint.color = android.graphics.Color.parseColor("#0F172A")
                outlinePaint.color = android.graphics.Color.parseColor("#F8FAFC")
                ripplePaint.color = android.graphics.Color.parseColor("#94A3B8")
            }
            else -> { // "white"
                fillPaint.color = android.graphics.Color.WHITE
                outlinePaint.color = android.graphics.Color.parseColor("#111622")
                ripplePaint.color = android.graphics.Color.parseColor("#00E5FF")
            }
        }
        invalidate()
    }

    fun setDragActive(active: Boolean) {
        isDragActive = active
        invalidate()
    }

    fun triggerClickAnimation() {
        ValueAnimator.ofFloat(0.90f, 1.0f).apply {
            duration = 160
            interpolator = OvershootInterpolator(1.8f)
            addUpdateListener {
                cursorBounceScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        ValueAnimator.ofFloat(0f, 16f * density).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                rippleRadius = anim.animatedValue as Float
                rippleAlpha = ((1f - anim.animatedFraction) * 200).toInt()
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Click ripple ring at hotspot tip (0, 0)
        if (rippleRadius > 0f && rippleAlpha > 0) {
            ripplePaint.alpha = rippleAlpha
            canvas.drawCircle(0f, 0f, rippleRadius, ripplePaint)
        }

        canvas.save()
        val finalScale = cursorBounceScale * pointerScale
        canvas.scale(finalScale, finalScale, 0f, 0f)

        // Drop shadow
        canvas.save()
        canvas.translate(1.2f * density, 1.8f * density)
        canvas.drawPath(cursorPath, shadowPaint)
        canvas.restore()

        // Pure Arrow Body
        canvas.drawPath(cursorPath, fillPaint)

        // Sharp Precision Outline
        canvas.drawPath(cursorPath, outlinePaint)

        // Active Drag Lock Halo Ring
        if (isDragActive) {
            val dragHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * density
                color = android.graphics.Color.parseColor("#F59E0B")
            }
            canvas.drawCircle(0f, 0f, 6f * density, dragHalo)
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
    var onDismiss: (() -> Unit)? = null
    var onControllerDrag: ((dx: Int, dy: Int) -> Unit)? = null

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

    // Two-finger multi-touch tracking
    private var isMultiTouch = false
    private var lastMultiY = 0f
    private var lastMultiX = 0f
    private var multiTouchAccumDy = 0f
    private var multiTouchAccumDx = 0f

    // Visual touch indicator on trackpad
    private var isTouchingPad = false
    private var padTouchX = 0f
    private var padTouchY = 0f
    private var padTouch2X = 0f
    private var padTouch2Y = 0f

    // Scroll strip thumb tracking
    private var scrollThumbY = -1f

    // Coordinates & Accessibility status
    private var coordText = "X: 540  Y: 1100"
    private var isAccessibilityReady = AgentAccessibilityService.instance != null

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
                repeatHandler.postDelayed(this, 160)
            }
        }
    }

    // Paints
    private val chassisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.parseColor("#F5080C16")
    }

    private val chassisStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        color = android.graphics.Color.parseColor("#00E5FF")
    }

    private val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.parseColor("#2600E5FF")
    }

    private val padBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.parseColor("#E6050812")
    }

    private val padBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = android.graphics.Color.parseColor("#243047")
    }

    private val scrollStripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.parseColor("#1A00E5FF")
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val touchAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.parseColor("#3300E5FF")
        maskFilter = BlurMaskFilter(6f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val touchRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        color = android.graphics.Color.parseColor("#00E5FF")
    }

    fun refreshAccessibilityStatus() {
        isAccessibilityReady = AgentAccessibilityService.instance != null
        invalidate()
    }

    fun updateCursorCoords(x: Int, y: Int) {
        coordText = "X: $x  Y: $y"
        invalidate()
    }

    fun setDragState(active: Boolean) {
        isDragActive = active
        invalidate()
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

    private fun getPadRect(): RectF {
        val w = width.toFloat()
        val top = getHeaderHeight() + getStatusBarHeight() + (4f * density)
        val bot = height.toFloat() - getDockHeight() - (6f * density)
        return RectF(8f * density, top, w - (8f * density), bot)
    }

    private fun getScrollStripRect(): RectF {
        val pad = getPadRect()
        val stripWidth = 24f * density
        return RectF(pad.right - stripWidth, pad.top, pad.right, pad.bottom)
    }

    private data class DockLayout(
        val rClick: RectF,
        val rDrag: RectF,
        val rLeft: RectF,
        val rUp: RectF,
        val rDown: RectF,
        val rRight: RectF,
        val rBack: RectF
    )

    private fun getDockLayout(): DockLayout {
        val w = width.toFloat()
        val h = height.toFloat()
        val dockTop = h - getDockHeight()
        val dockBot = h - (6f * density)
        val startX = 8f * density
        val padW = w - (16f * density)
        val gap = 3f * density
        val totalGaps = 6f * gap
        val availW = (padW - totalGaps).coerceAtLeast(10f)

        // Proportional Weights: CLICK (2.2), DRAG (1.8), LEFT (1.0), UP (1.0), DOWN (1.0), RIGHT (1.0), BACK (1.6)
        val sumWeights = 9.6f
        fun bw(weight: Float) = (availW * (weight / sumWeights))

        val wClick = bw(2.2f)
        val wDrag = bw(1.8f)
        val wLeft = bw(1.0f)
        val wUp = bw(1.0f)
        val wDown = bw(1.0f)
        val wRight = bw(1.0f)
        val wBack = bw(1.6f)

        var curX = startX
        val rClick = RectF(curX, dockTop, curX + wClick, dockBot)
        curX += wClick + gap
        val rDrag = RectF(curX, dockTop, curX + wDrag, dockBot)
        curX += wDrag + gap
        val rLeft = RectF(curX, dockTop, curX + wLeft, dockBot)
        curX += wLeft + gap
        val rUp = RectF(curX, dockTop, curX + wUp, dockBot)
        curX += wUp + gap
        val rDown = RectF(curX, dockTop, curX + wDown, dockBot)
        curX += wDown + gap
        val rRight = RectF(curX, dockTop, curX + wRight, dockBot)
        curX += wRight + gap
        val rBack = RectF(curX, dockTop, w - (8f * density), dockBot)

        return DockLayout(rClick, rDrag, rLeft, rUp, rDown, rRight, rBack)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = 18f * density

        // 1. Chassis Background & Cyber Border
        val chassisRect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(chassisRect, corner, corner, chassisPaint)
        canvas.drawRoundRect(chassisRect, corner, corner, chassisStrokePaint)

        // 2. Top Header Bar
        val headerRect = RectF(0f, 0f, w, getHeaderHeight())
        canvas.drawRoundRect(headerRect, corner, corner, headerBgPaint)

        // Trackpad Pro Badge
        val modeRect = RectF(6f * density, 5f * density, 120f * density, getHeaderHeight() - (5f * density))
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.parseColor("#2600E5FF")
        }
        val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = android.graphics.Color.parseColor("#00E5FF")
        }
        canvas.drawRoundRect(modeRect, 10f * density, 10f * density, pillPaint)
        canvas.drawRoundRect(modeRect, 10f * density, 10f * density, pillStroke)

        textPaint.textSize = 10f * density
        textPaint.color = android.graphics.Color.parseColor("#00E5FF")
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("⚡ TRACKPAD", modeRect.centerX(), modeRect.centerY() + (3.5f * density), textPaint)

        // Center grip dots
        val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#66FFFFFF")
            style = Paint.Style.FILL
        }
        val gripY = getHeaderHeight() / 2f
        for (i in -2..2) {
            canvas.drawCircle(w / 2f + (i * 6f * density), gripY, 1.6f * density, gripPaint)
        }

        // Quick Resize Button [ ⤢ ]
        val rClose = RectF(w - (34f * density), 4f * density, w - (6f * density), getHeaderHeight() - (4f * density))
        val rResize = RectF(rClose.left - (32f * density), 4f * density, rClose.left - (4f * density), getHeaderHeight() - (4f * density))

        val resizeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (pressedTarget == ControllerTouchTarget.BTN_RESIZE) android.graphics.Color.parseColor("#3300E5FF") else android.graphics.Color.parseColor("#152033")
        }
        val resizeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = if (pressedTarget == ControllerTouchTarget.BTN_RESIZE) android.graphics.Color.parseColor("#00E5FF") else android.graphics.Color.parseColor("#334155")
        }
        val resizeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#00E5FF")
            textSize = 12f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawRoundRect(rResize, 6f * density, 6f * density, resizeBg)
        canvas.drawRoundRect(rResize, 6f * density, 6f * density, resizeBorder)
        canvas.drawText("⤢", rResize.centerX(), rResize.centerY() + (4f * density), resizeText)

        // Close button (✕)
        val closeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (pressedTarget == ControllerTouchTarget.BTN_CLOSE) android.graphics.Color.parseColor("#44EF4444") else android.graphics.Color.parseColor("#1F1522")
        }
        val closeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = if (pressedTarget == ControllerTouchTarget.BTN_CLOSE) android.graphics.Color.parseColor("#EF4444") else android.graphics.Color.parseColor("#472535")
        }
        val closeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#EF4444")
            textSize = 13f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawRoundRect(rClose, 6f * density, 6f * density, closeBg)
        canvas.drawRoundRect(rClose, 6f * density, 6f * density, closeBorder)
        canvas.drawText("✕", rClose.centerX(), rClose.centerY() + (4.5f * density), closeText)

        // 3. Status Bar: Drag State, Accessibility, & Coordinates
        val statusY = getHeaderHeight() + (13f * density)
        isAccessibilityReady = AgentAccessibilityService.instance != null
        if (!isAccessibilityReady) {
            val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#F59E0B")
                textSize = 9.5f * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("⚠️ Accessibility OFF - Tap to Enable", w / 2f, statusY, warnPaint)
        } else if (isDragActive) {
            val dragBannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#F59E0B")
                textSize = 9.5f * density
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("✋ DRAG HELD - TAP TO DROP", w / 2f, statusY, dragBannerPaint)
        } else {
            val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#00E5FF")
                textSize = 9.5f * density
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("⚡ READY  •  $coordText", w / 2f, statusY, statusPaint)
        }

        // 4. Center Trackpad Surface Area
        val padRect = getPadRect()
        canvas.drawRoundRect(padRect, 10f * density, 10f * density, padBgPaint)
        canvas.drawRoundRect(padRect, 10f * density, 10f * density, padBorderPaint)

        // Precision Grid Matrix Dots
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#1F00E5FF")
            style = Paint.Style.FILL
        }
        val stepX = (padRect.width() - 32f * density) / 4f
        val stepY = padRect.height() / 4f
        for (gx in 1..3) {
            for (gy in 1..3) {
                canvas.drawCircle(padRect.left + (gx * stepX), padRect.top + (gy * stepY), 1.2f * density, dotPaint)
            }
        }

        // Instructions watermark
        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#4D94A3B8")
            textSize = 9.5f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
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

                // Connecting stroke between fingers
                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#8000E5FF")
                    strokeWidth = 1.5f * density
                    style = Paint.Style.STROKE
                }
                canvas.drawLine(padTouchX, padTouchY, padTouch2X, padTouch2Y, linePaint)
            }
        }

        // Right-edge Dedicated Scroll Strip
        val strip = getScrollStripRect()
        canvas.drawRoundRect(strip, 6f * density, 6f * density, scrollStripPaint)

        val stripBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = android.graphics.Color.parseColor("#3300E5FF")
        }
        canvas.drawRoundRect(strip, 6f * density, 6f * density, stripBorder)

        // Scroll thumb pill indicator
        val thumbY = if (scrollThumbY > 0f) scrollThumbY else strip.centerY()
        val thumbRect = RectF(strip.left + (2f * density), thumbY - (10f * density), strip.right - (2f * density), thumbY + (10f * density))
        val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.parseColor("#00E5FF")
        }
        canvas.drawRoundRect(thumbRect, 4f * density, 4f * density, thumbPaint)

        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#00E5FF")
            textSize = 9.5f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("▲", strip.centerX(), strip.top + (12f * density), arrowPaint)
        canvas.drawText("▼", strip.centerX(), strip.bottom - (5f * density), arrowPaint)

        // 5. Bottom Hardware-Style Cyber Dock with 7 Navigation & Action Buttons
        val dock = getDockLayout()

        // 1: CLICK
        drawDockButton(
            canvas, dock.rClick, "🖱️ CLICK",
            bgColorHex = "#00E5FF", textColorHex = "#080D1A", borderColorHex = "#00E5FF",
            isPressed = (pressedTarget == ControllerTouchTarget.BTN_L_CLICK)
        )

        // 2: DRAG
        val dragBg = if (isDragActive) "#F59E0B" else "#141D2B"
        val dragTextCol = if (isDragActive) "#080D1A" else "#F59E0B"
        val dragBorder = if (isDragActive) "#F59E0B" else "#F59E0B"
        drawDockButton(
            canvas, dock.rDrag, if (isDragActive) "✋ DROP" else "✋ DRAG",
            bgColorHex = dragBg, textColorHex = dragTextCol, borderColorHex = dragBorder,
            isPressed = (pressedTarget == ControllerTouchTarget.BTN_DRAG_TOGGLE)
        )

        // 3: ◀ LEFT
        drawDockButton(
            canvas, dock.rLeft, "◀",
            bgColorHex = "#111827", textColorHex = "#38BDF8", borderColorHex = "#334155",
            isPressed = (pressedTarget == ControllerTouchTarget.BTN_SCROLL_LEFT)
        )

        // 4: ▲ UP
        drawDockButton(
            canvas, dock.rUp, "▲",
            bgColorHex = "#111827", textColorHex = "#38BDF8", borderColorHex = "#334155",
            isPressed = (pressedTarget == ControllerTouchTarget.BTN_SCROLL_UP)
        )

        // 5: ▼ DOWN
        drawDockButton(
            canvas, dock.rDown, "▼",
            bgColorHex = "#111827", textColorHex = "#38BDF8", borderColorHex = "#334155",
            isPressed = (pressedTarget == ControllerTouchTarget.BTN_SCROLL_DOWN)
        )

        // 6: ▶ RIGHT
        drawDockButton(
            canvas, dock.rRight, "▶",
            bgColorHex = "#111827", textColorHex = "#38BDF8", borderColorHex = "#334155",
            isPressed = (pressedTarget == ControllerTouchTarget.BTN_SCROLL_RIGHT)
        )

        // 7: 🔙 BACK
        drawDockButton(
            canvas, dock.rBack, "🔙 BACK",
            bgColorHex = "#1E293B", textColorHex = "#F8FAFC", borderColorHex = "#475569",
            isPressed = (pressedTarget == ControllerTouchTarget.BTN_R_CLICK)
        )
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
        val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (isPressed) android.graphics.Color.parseColor("#38BDF8") else android.graphics.Color.parseColor(bgColorHex)
            if (isPressed && bgColorHex != "#00E5FF") alpha = 140
        }
        val borderP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (if (isPressed) 1.8f else 1f) * density
            color = if (isPressed) android.graphics.Color.parseColor("#00E5FF") else android.graphics.Color.parseColor(borderColorHex)
        }
        val tP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isPressed) android.graphics.Color.WHITE else android.graphics.Color.parseColor(textColorHex)
            textSize = (if (text.length > 2) 9.5f else 13f) * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawRoundRect(rect, corner, corner, bgP)
        canvas.drawRoundRect(rect, corner, corner, borderP)
        canvas.drawText(text, rect.centerX(), rect.centerY() + (tP.textSize * 0.35f), tP)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val w = width.toFloat()
        val h = height.toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                downTime = System.currentTimeMillis()

                val pad = getPadRect()
                val strip = getScrollStripRect()
                val dockTop = h - getDockHeight()
                val rClose = RectF(w - (34f * density), 0f, w, getHeaderHeight())
                val rResize = RectF(rClose.left - (34f * density), 0f, rClose.left, getHeaderHeight())

                // 1. Header controls
                if (y <= getHeaderHeight()) {
                    if (rClose.contains(x, y)) {
                        activeTarget = ControllerTouchTarget.BTN_CLOSE
                        pressedTarget = activeTarget
                    } else if (rResize.contains(x, y)) {
                        activeTarget = ControllerTouchTarget.BTN_RESIZE
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
                else if (pad.contains(x, y)) {
                    if (strip.contains(x, y)) {
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
                else if (y >= dockTop) {
                    val dock = getDockLayout()
                    when {
                        dock.rClick.contains(x, y) -> activeTarget = ControllerTouchTarget.BTN_L_CLICK
                        dock.rDrag.contains(x, y) -> activeTarget = ControllerTouchTarget.BTN_DRAG_TOGGLE
                        dock.rLeft.contains(x, y) -> {
                            activeTarget = ControllerTouchTarget.BTN_SCROLL_LEFT
                            onScroll?.invoke(ScrollDirection.LEFT)
                            repeatTarget = activeTarget
                            repeatHandler.postDelayed(repeatRunnable, 340)
                        }
                        dock.rUp.contains(x, y) -> {
                            activeTarget = ControllerTouchTarget.BTN_SCROLL_UP
                            onScroll?.invoke(ScrollDirection.UP)
                            repeatTarget = activeTarget
                            repeatHandler.postDelayed(repeatRunnable, 340)
                        }
                        dock.rDown.contains(x, y) -> {
                            activeTarget = ControllerTouchTarget.BTN_SCROLL_DOWN
                            onScroll?.invoke(ScrollDirection.DOWN)
                            repeatTarget = activeTarget
                            repeatHandler.postDelayed(repeatRunnable, 340)
                        }
                        dock.rRight.contains(x, y) -> {
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
                        val pad = getPadRect()
                        padTouchX = x.coerceIn(pad.left + 4f, pad.right - 4f)
                        padTouchY = y.coerceIn(pad.top + 4f, pad.bottom - 4f)
                        invalidate()
                    }
                    ControllerTouchTarget.TRACKPAD_SCROLL_STRIP -> {
                        val stripDy = event.rawY - lastRawY
                        val strip = getScrollStripRect()
                        scrollThumbY = y.coerceIn(strip.top + 10f, strip.bottom - 10f)
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
