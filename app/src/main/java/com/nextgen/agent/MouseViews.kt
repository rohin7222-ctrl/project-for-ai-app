package com.nextgen.agent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.hypot

/**
 * Controller operating modes:
 * - TRACKPAD: 1:1 Laptop touchpad experience (swipe to move, tap to click, right-edge scroll strip, drag lock).
 * - JOYSTICK: 3D Thumbstick with dynamic velocity acceleration and cardinal LED indicators.
 */
enum class MousePadMode {
    TRACKPAD, JOYSTICK
}

enum class ControllerTouchTarget {
    NONE,
    TRACKPAD_SURFACE,
    TRACKPAD_SCROLL_STRIP,
    JOYSTICK_STICK,
    BTN_L_CLICK,
    BTN_DRAG_TOGGLE,
    BTN_R_CLICK,
    BTN_SCROLL_UP,
    BTN_SCROLL_DOWN,
    BTN_MODE_TOGGLE,
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
        pointerScale = scale.coerceIn(0.6f, 2.0f)
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
// 3. CUSTOM VIEW: DUAL-MODE CONTROLLER HUD (TRACKPAD & JOYSTICK)
// =========================================================================
class CyberMouseControllerView(context: Context) : View(context) {
    var onTrackpadMove: ((dx: Float, dy: Float) -> Unit)? = null
    var onStickMove: ((normX: Float, normY: Float) -> Unit)? = null
    var onLeftClick: (() -> Unit)? = null
    var onRightClick: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onLongClick: (() -> Unit)? = null
    var onDragToggle: (() -> Unit)? = null
    var onScroll: ((scrollUp: Boolean) -> Unit)? = null
    var onModeToggled: ((mode: MousePadMode) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var onControllerDrag: ((dx: Int, dy: Int) -> Unit)? = null

    var currentPadMode: MousePadMode = MousePadMode.TRACKPAD
    var isDragActive: Boolean = false
    var padAlpha: Float = 0.92f

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Stick position in Joystick mode
    private var stickX = 0f
    private var stickY = 0f

    // Touch tracking
    private var activeTarget = ControllerTouchTarget.NONE
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downTime = 0L
    private var lastTapTime = 0L

    // Coordinates & Accessibility status
    private var coordText = "X: 540  Y: 1100"
    private var isAccessibilityReady = AgentAccessibilityService.instance != null

    // Continuous joystick frame callback
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (activeTarget == ControllerTouchTarget.JOYSTICK_STICK) {
                val maxR = getMaxStickRadius()
                if (maxR > 0f) {
                    onStickMove?.invoke(stickX / maxR, stickY / maxR)
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    // Paints
    private val chassisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.parseColor("#F2090D18")
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
        color = android.graphics.Color.parseColor("#E6060912")
    }

    private val padBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = android.graphics.Color.parseColor("#2E384D")
    }

    private val scrollStripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.parseColor("#1A00E5FF")
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
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
        chassisPaint.alpha = (padAlpha * 242).toInt().coerceIn(80, 255)
        padBgPaint.alpha = (padAlpha * 230).toInt().coerceIn(70, 255)
        invalidate()
    }

    private fun getHeaderHeight() = 34f * density
    private fun getStatusBarHeight() = 18f * density
    private fun getDockHeight() = 44f * density

    private fun getPadRect(): RectF {
        val w = width.toFloat()
        val top = getHeaderHeight() + getStatusBarHeight() + (4f * density)
        val bot = height.toFloat() - getDockHeight() - (6f * density)
        return RectF(8f * density, top, w - (8f * density), bot)
    }

    private fun getScrollStripRect(): RectF {
        val pad = getPadRect()
        val stripWidth = 26f * density
        return RectF(pad.right - stripWidth, pad.top, pad.right, pad.bottom)
    }

    private fun getStickCenterX() = width / 2f
    private fun getStickCenterY(): Float {
        val pad = getPadRect()
        return (pad.top + pad.bottom) / 2f
    }

    private fun getWellRadius(): Float {
        val pad = getPadRect()
        return (Math.min(pad.width(), pad.height()) / 2f) - (8f * density)
    }

    private fun getMaxStickRadius() = getWellRadius() * 0.70f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val corner = 16f * density

        // 1. Chassis Background & Cyber Border
        val chassisRect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(chassisRect, corner, corner, chassisPaint)
        canvas.drawRoundRect(chassisRect, corner, corner, chassisStrokePaint)

        // 2. Top Header Bar
        val headerRect = RectF(0f, 0f, w, getHeaderHeight())
        canvas.drawRoundRect(headerRect, corner, corner, headerBgPaint)

        // Mode Switch Pill [ 🖱️ TRACKPAD ] / [ 🕹️ ANALOG ]
        val isTrackpad = (currentPadMode == MousePadMode.TRACKPAD)
        val modeRect = RectF(6f * density, 5f * density, 114f * density, getHeaderHeight() - (5f * density))
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (isTrackpad) android.graphics.Color.parseColor("#00E5FF") else android.graphics.Color.parseColor("#818CF8")
        }
        canvas.drawRoundRect(modeRect, 10f * density, 10f * density, pillPaint)

        textPaint.textSize = 10f * density
        textPaint.color = android.graphics.Color.parseColor("#080D1A")
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val modeLabel = if (isTrackpad) "🖱️ TRACKPAD" else "🕹️ ANALOG"
        canvas.drawText(modeLabel, modeRect.centerX(), modeRect.centerY() + (3.5f * density), textPaint)

        // Drag handle grip dots in center
        val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#80FFFFFF")
            style = Paint.Style.FILL
        }
        val gripY = getHeaderHeight() / 2f
        for (i in -2..2) {
            canvas.drawCircle(w / 2f + (i * 7f * density), gripY, 1.8f * density, gripPaint)
        }

        // Close button (✕)
        val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#FF5252")
            textSize = 14f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("✕", w - (18f * density), getHeaderHeight() / 2f + (5f * density), closePaint)

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
            canvas.drawText("⚡ READY  $coordText", w / 2f, statusY, statusPaint)
        }

        // 4. Center Surface Area
        val padRect = getPadRect()
        canvas.drawRoundRect(padRect, 10f * density, 10f * density, padBgPaint)
        canvas.drawRoundRect(padRect, 10f * density, 10f * density, padBorderPaint)

        if (isTrackpad) {
            // Precision Grid Dots
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#26FFFFFF")
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
            canvas.drawText("Swipe: Move  •  Tap: Click", padRect.centerX() - (12f * density), padRect.centerY() + (3.5f * density), guidePaint)

            // Right-edge Dedicated Scroll Strip
            val strip = getScrollStripRect()
            canvas.drawRoundRect(strip, 6f * density, 6f * density, scrollStripPaint)

            val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#00E5FF")
                textSize = 10f * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("▲", strip.centerX(), strip.top + (14f * density), arrowPaint)
            canvas.drawText("📜", strip.centerX(), strip.centerY() + (3.5f * density), arrowPaint)
            canvas.drawText("▼", strip.centerX(), strip.bottom - (6f * density), arrowPaint)

        } else {
            // JOYSTICK MODE
            val stickCx = getStickCenterX()
            val stickCy = getStickCenterY()
            val wellR = getWellRadius()

            val wellRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * density
                color = android.graphics.Color.parseColor("#3300E5FF")
            }
            canvas.drawCircle(stickCx, stickCy, wellR, wellRingPaint)

            // Cardinal Direction LEDs (▲, ►, ▼, ◄)
            val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 12f * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val activeLed = android.graphics.Color.parseColor("#00E5FF")
            val dimLed = android.graphics.Color.parseColor("#334155")

            ledPaint.color = if (stickY < -10f * density) activeLed else dimLed
            canvas.drawText("▲", stickCx, stickCy - wellR + (14f * density), ledPaint)

            ledPaint.color = if (stickX > 10f * density) activeLed else dimLed
            canvas.drawText("►", stickCx + wellR - (10f * density), stickCy + (4f * density), ledPaint)

            ledPaint.color = if (stickY > 10f * density) activeLed else dimLed
            canvas.drawText("▼", stickCx, stickCy + wellR - (4f * density), ledPaint)

            ledPaint.color = if (stickX < -10f * density) activeLed else dimLed
            canvas.drawText("◄", stickCx - wellR + (10f * density), stickCy + (4f * density), ledPaint)

            // Center thumbstick knob
            val knobCx = stickCx + stickX
            val knobCy = stickCy + stickY
            val knobR = wellR * 0.45f

            val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = android.graphics.Color.parseColor("#00E5FF")
            }
            canvas.drawCircle(knobCx, knobCy, knobR, knobPaint)

            val knobCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = android.graphics.Color.parseColor("#090D1A")
            }
            canvas.drawCircle(knobCx, knobCy, knobR * 0.45f, knobCorePaint)
        }

        // 5. Bottom Physical Mouse Dock (44dp)
        val dockTop = h - getDockHeight()
        val dockBot = h - (8f * density)
        val padW = w - (16f * density)
        val startX = 8f * density

        // Button 1: [ 🖱️ CLICK ] (Primary Click) ~30%
        val b1Right = startX + (padW * 0.30f)
        drawDockButton(canvas, RectF(startX, dockTop, b1Right, dockBot), "🖱️ CLICK", "#00E5FF", "#080D1A")

        // Button 2: [ ✋ DRAG ] (Drag Lock Toggle) ~24%
        val b2Left = b1Right + (4f * density)
        val b2Right = b2Left + (padW * 0.23f)
        val dragBg = if (isDragActive) "#F59E0B" else "#1E293B"
        val dragTextCol = if (isDragActive) "#080D1A" else "#F59E0B"
        drawDockButton(canvas, RectF(b2Left, dockTop, b2Right, dockBot), if (isDragActive) "✋ DROP" else "✋ DRAG", dragBg, dragTextCol)

        // Button 3: [ ▲ ] (Scroll Up) ~11%
        val b3Left = b2Right + (4f * density)
        val b3Right = b3Left + (padW * 0.11f)
        drawDockButton(canvas, RectF(b3Left, dockTop, b3Right, dockBot), "▲", "#1E293B", "#00E5FF")

        // Button 4: [ ▼ ] (Scroll Down) ~11%
        val b4Left = b3Right + (4f * density)
        val b4Right = b4Left + (padW * 0.11f)
        drawDockButton(canvas, RectF(b4Left, dockTop, b4Right, dockBot), "▼", "#1E293B", "#00E5FF")

        // Button 5: [ 🔙 BACK ] (Right-Click Back) ~19%
        val b5Left = b4Right + (4f * density)
        val b5Right = w - (8f * density)
        drawDockButton(canvas, RectF(b5Left, dockTop, b5Right, dockBot), "🔙 BACK", "#334155", "#FFFFFF")
    }

    private fun drawDockButton(canvas: Canvas, rect: RectF, text: String, bgColorHex: String, textColorHex: String) {
        val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.parseColor(bgColorHex)
        }
        val borderP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = android.graphics.Color.parseColor("#475569")
        }
        val tP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(textColorHex)
            textSize = 10f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawRoundRect(rect, 8f * density, 8f * density, bgP)
        canvas.drawRoundRect(rect, 8f * density, 8f * density, borderP)
        canvas.drawText(text, rect.centerX(), rect.centerY() + (3.5f * density), tP)
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

                val isTrackpad = (currentPadMode == MousePadMode.TRACKPAD)
                val pad = getPadRect()
                val strip = getScrollStripRect()
                val dockTop = h - getDockHeight()

                // 1. Header controls
                if (y <= getHeaderHeight()) {
                    if (x >= w - (36f * density)) {
                        activeTarget = ControllerTouchTarget.BTN_CLOSE
                    } else if (x <= 120f * density) {
                        activeTarget = ControllerTouchTarget.BTN_MODE_TOGGLE
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
                    if (isTrackpad) {
                        if (strip.contains(x, y)) {
                            activeTarget = ControllerTouchTarget.TRACKPAD_SCROLL_STRIP
                        } else {
                            activeTarget = ControllerTouchTarget.TRACKPAD_SURFACE
                        }
                    } else {
                        activeTarget = ControllerTouchTarget.JOYSTICK_STICK
                        handleStickMove(x - getStickCenterX(), y - getStickCenterY())
                        Choreographer.getInstance().postFrameCallback(frameCallback)
                    }
                }
                // 4. Bottom Dock Buttons
                else if (y >= dockTop) {
                    val padW = w - (16f * density)
                    val startX = 8f * density
                    val b1Right = startX + (padW * 0.30f)
                    val b2Right = b1Right + (4f * density) + (padW * 0.23f)
                    val b3Right = b2Right + (4f * density) + (padW * 0.11f)
                    val b4Right = b3Right + (4f * density) + (padW * 0.11f)

                    if (x <= b1Right) {
                        activeTarget = ControllerTouchTarget.BTN_L_CLICK
                    } else if (x <= b2Right) {
                        activeTarget = ControllerTouchTarget.BTN_DRAG_TOGGLE
                    } else if (x <= b3Right) {
                        activeTarget = ControllerTouchTarget.BTN_SCROLL_UP
                    } else if (x <= b4Right) {
                        activeTarget = ControllerTouchTarget.BTN_SCROLL_DOWN
                    } else {
                        activeTarget = ControllerTouchTarget.BTN_R_CLICK
                    }
                } else {
                    activeTarget = ControllerTouchTarget.HEADER_DRAG
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val rawDx = event.rawX - lastRawX
                val rawDy = event.rawY - lastRawY

                when (activeTarget) {
                    ControllerTouchTarget.TRACKPAD_SURFACE -> {
                        onTrackpadMove?.invoke(rawDx, rawDy)
                    }
                    ControllerTouchTarget.TRACKPAD_SCROLL_STRIP -> {
                        val stripDy = event.rawY - lastRawY
                        if (Math.abs(stripDy) >= (14f * density)) {
                            onScroll?.invoke(stripDy > 0)
                            lastRawY = event.rawY
                        }
                    }
                    ControllerTouchTarget.JOYSTICK_STICK -> {
                        handleStickMove(x - getStickCenterX(), y - getStickCenterY())
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

            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - downTime
                val moved = hypot((event.rawX - downRawX).toDouble(), (event.rawY - downRawY).toDouble())

                when (activeTarget) {
                    ControllerTouchTarget.TRACKPAD_SURFACE -> {
                        // Tap on trackpad = Instant Left Click
                        if (moved < touchSlop && duration < 240) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 280) {
                                onDoubleTap?.invoke()
                                lastTapTime = 0L
                            } else {
                                onLeftClick?.invoke()
                                lastTapTime = now
                            }
                        } else if (moved < touchSlop && duration >= 500) {
                            // Long-press surface = Long click
                            onLongClick?.invoke()
                        }
                    }
                    ControllerTouchTarget.JOYSTICK_STICK -> {
                        animateStickRelease()
                        Choreographer.getInstance().removeFrameCallback(frameCallback)
                        if (moved < touchSlop && duration < 240) {
                            onLeftClick?.invoke()
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
                    ControllerTouchTarget.BTN_SCROLL_UP -> {
                        onScroll?.invoke(true)
                    }
                    ControllerTouchTarget.BTN_SCROLL_DOWN -> {
                        onScroll?.invoke(false)
                    }
                    ControllerTouchTarget.BTN_MODE_TOGGLE -> {
                        currentPadMode = if (currentPadMode == MousePadMode.TRACKPAD) MousePadMode.JOYSTICK else MousePadMode.TRACKPAD
                        onModeToggled?.invoke(currentPadMode)
                        invalidate()
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
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleStickMove(dx: Float, dy: Float) {
        val maxR = getMaxStickRadius()
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist > maxR) {
            val ratio = maxR / dist
            stickX = dx * ratio
            stickY = dy * ratio
        } else {
            stickX = dx
            stickY = dy
        }
        invalidate()
    }

    private fun animateStickRelease() {
        val startX = stickX
        val startY = stickY
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 160
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                stickX = startX * fraction
                stickY = startY * fraction
                invalidate()
            }
            start()
        }
    }
}
