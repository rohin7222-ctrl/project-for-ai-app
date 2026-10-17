package com.nextgen.agent

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import kotlin.math.hypot

/**
 * MouseButtonManager handles:
 * 1. Floating Ball Overlay (App logo, draggable anywhere on screen, customizable size)
 * 2. Virtual Mouse Pointer Overlay (Precise screen cursor with click animations)
 * 3. Analog Joystick Overlay (Virtual thumbstick to navigate cursor + tap on analog for OK / click)
 * 4. Dismiss / show toggle when floating ball is tapped
 */
class MouseButtonManager private constructor(private val appContext: Context) {

    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    // Config states
    var isBallEnabled: Boolean = false
        private set
    var ballSizeDp: Int = 52
        private set
    var pointerSpeed: Float = 1.0f
        private set

    // Active overlay states
    var isBallShowing: Boolean = false
        private set
    var isPointerAndAnalogShowing: Boolean = false
        private set

    // Overlays Views
    private var floatingBallView: FloatingBallView? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private var mousePointerView: MousePointerView? = null
    private var pointerParams: WindowManager.LayoutParams? = null

    private var analogJoystickView: AnalogJoystickView? = null
    private var analogParams: WindowManager.LayoutParams? = null

    // Cursor position in screen pixels
    var cursorX: Float = 500f
    var cursorY: Float = 800f

    // Screen dimensions
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400

    init {
        updateScreenDimensions()
    }

    private fun updateScreenDimensions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
        }
        if (cursorX == 500f && cursorY == 800f) {
            cursorX = (screenWidth / 2).toFloat()
            cursorY = (screenHeight / 2).toFloat()
        }
    }

    fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(appContext)
        } else {
            true
        }
    }

    private fun getOverlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    /** Sync with persisted app config */
    fun syncWithConfig(cfg: Config) {
        mainHandler.post {
            ballSizeDp = cfg.mouseBallSize
            pointerSpeed = cfg.mouseSpeed
            isBallEnabled = cfg.mouseBallEnabled

            if (isBallEnabled) {
                if (canDrawOverlays()) {
                    showFloatingBall()
                }
            } else {
                hideFloatingBall()
                hidePointerAndAnalog()
            }
        }
    }

    fun setBallEnabled(enabled: Boolean, context: Context? = null) {
        isBallEnabled = enabled
        if (enabled) {
            if (!canDrawOverlays()) {
                context?.let {
                    Toast.makeText(it, "Overlay permission required for Floating Ball", Toast.LENGTH_SHORT).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + it.packageName)
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    it.startActivity(intent)
                }
                return
            }
            showFloatingBall()
        } else {
            hideFloatingBall()
            hidePointerAndAnalog()
        }
    }

    fun setBallSize(sizeDp: Int) {
        ballSizeDp = sizeDp.coerceIn(36, 88)
        if (isBallShowing && floatingBallView != null && ballParams != null) {
            mainHandler.post {
                val px = (ballSizeDp * appContext.resources.displayMetrics.density).toInt()
                ballParams?.width = px
                ballParams?.height = px
                try {
                    windowManager.updateViewLayout(floatingBallView, ballParams)
                    floatingBallView?.invalidate()
                } catch (ignored: Exception) {}
            }
        }
    }

    fun setPointerSpeed(speed: Float) {
        pointerSpeed = speed.coerceIn(0.4f, 3.0f)
    }

    // ==========================================
    // FLOATING BALL OVERLAY
    // ==========================================
    fun showFloatingBall() {
        if (!canDrawOverlays()) return
        mainHandler.post {
            try {
                if (floatingBallView == null) {
                    val px = (ballSizeDp * appContext.resources.displayMetrics.density).toInt()
                    ballParams = WindowManager.LayoutParams(
                        px,
                        px,
                        getOverlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = 30
                        y = screenHeight / 3
                    }

                    floatingBallView = FloatingBallView(appContext).apply {
                        onBallTapped = {
                            togglePointerAndAnalog()
                        }
                        onBallDragged = { dx, dy ->
                            ballParams?.let { p ->
                                p.x += dx
                                p.y += dy
                                p.x = p.x.coerceIn(0, screenWidth - p.width)
                                p.y = p.y.coerceIn(0, screenHeight - p.height)
                                try {
                                    windowManager.updateViewLayout(this, p)
                                } catch (ignored: Exception) {}
                            }
                        }
                    }
                    windowManager.addView(floatingBallView, ballParams)
                    isBallShowing = true
                } else if (!isBallShowing) {
                    floatingBallView?.visibility = View.VISIBLE
                    isBallShowing = true
                }
            } catch (e: Exception) {
                isBallShowing = false
            }
        }
    }

    fun hideFloatingBall() {
        mainHandler.post {
            try {
                if (floatingBallView != null && isBallShowing) {
                    windowManager.removeView(floatingBallView)
                    floatingBallView = null
                    isBallShowing = false
                }
            } catch (ignored: Exception) {
                floatingBallView = null
                isBallShowing = false
            }
        }
    }

    // ==========================================
    // POINTER & ANALOG JOYSTICK TOGGLE
    // ==========================================
    fun togglePointerAndAnalog() {
        vibrate(30)
        if (isPointerAndAnalogShowing) {
            hidePointerAndAnalog()
        } else {
            showPointerAndAnalog()
        }
    }

    fun showPointerAndAnalog() {
        if (!canDrawOverlays()) return
        mainHandler.post {
            try {
                updateScreenDimensions()

                // 1. Show Mouse Pointer Overlay
                if (mousePointerView == null) {
                    val pointerSizePx = (40 * appContext.resources.displayMetrics.density).toInt()
                    pointerParams = WindowManager.LayoutParams(
                        pointerSizePx,
                        pointerSizePx,
                        getOverlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = cursorX.toInt()
                        y = cursorY.toInt()
                    }
                    mousePointerView = MousePointerView(appContext)
                    windowManager.addView(mousePointerView, pointerParams)
                } else {
                    mousePointerView?.visibility = View.VISIBLE
                }

                // 2. Show Analog Controller Overlay
                if (analogJoystickView == null) {
                    val padSizePx = (160 * appContext.resources.displayMetrics.density).toInt()
                    analogParams = WindowManager.LayoutParams(
                        padSizePx,
                        padSizePx,
                        getOverlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = screenWidth - padSizePx - 40
                        y = screenHeight - padSizePx - 160
                    }
                    analogJoystickView = AnalogJoystickView(appContext).apply {
                        onStickMove = { normX, normY, deltaX, deltaY ->
                            moveCursor(normX, normY)
                        }
                        onStickTap = {
                            performMouseClick()
                        }
                        onPadDrag = { dx, dy ->
                            analogParams?.let { p ->
                                p.x += dx
                                p.y += dy
                                p.x = p.x.coerceIn(0, screenWidth - p.width)
                                p.y = p.y.coerceIn(0, screenHeight - p.height)
                                try {
                                    windowManager.updateViewLayout(this, p)
                                } catch (ignored: Exception) {}
                            }
                        }
                    }
                    windowManager.addView(analogJoystickView, analogParams)
                } else {
                    analogJoystickView?.visibility = View.VISIBLE
                }

                isPointerAndAnalogShowing = true
                floatingBallView?.setControllerActive(true)

            } catch (e: Exception) {
                isPointerAndAnalogShowing = false
            }
        }
    }

    fun hidePointerAndAnalog() {
        mainHandler.post {
            try {
                if (mousePointerView != null) {
                    windowManager.removeView(mousePointerView)
                    mousePointerView = null
                }
                if (analogJoystickView != null) {
                    windowManager.removeView(analogJoystickView)
                    analogJoystickView = null
                }
                isPointerAndAnalogShowing = false
                floatingBallView?.setControllerActive(false)
            } catch (ignored: Exception) {
                mousePointerView = null
                analogJoystickView = null
                isPointerAndAnalogShowing = false
            }
        }
    }

    private fun moveCursor(normX: Float, normY: Float) {
        val mag = hypot(normX.toDouble(), normY.toDouble()).toFloat()
        if (mag > 0.05f) {
            val stepSpeed = (20f * pointerSpeed) * (mag * mag.coerceAtLeast(0.35f))
            cursorX += (normX / mag) * stepSpeed
            cursorY += (normY / mag) * stepSpeed

            cursorX = cursorX.coerceIn(0f, (screenWidth - 10).toFloat())
            cursorY = cursorY.coerceIn(0f, (screenHeight - 10).toFloat())

            pointerParams?.let { p ->
                p.x = cursorX.toInt()
                p.y = cursorY.toInt()
                try {
                    windowManager.updateViewLayout(mousePointerView, p)
                } catch (ignored: Exception) {}
            }
        }
    }

    /**
     * Tapping the analog triggers OK / Click at the pointer position!
     */
    fun performMouseClick() {
        vibrate(45)
        mousePointerView?.triggerClickRipple()

        val ok = AgentAccessibilityService.performClickAt(cursorX, cursorY)
        if (!ok && AgentAccessibilityService.instance == null) {
            mainHandler.post {
                Toast.makeText(
                    appContext,
                    "Enable 'Aero Fenix' in Accessibility Settings for screen clicks",
                    Toast.LENGTH_LONG
                ).show()
                AgentAccessibilityService.autoEnable(appContext)
            }
        }
    }

    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        } catch (ignored: Exception) {}
    }

    companion object {
        @Volatile
        private var instance: MouseButtonManager? = null

        fun getInstance(context: Context): MouseButtonManager {
            return instance ?: synchronized(this) {
                instance ?: MouseButtonManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // =========================================================================
    // CUSTOM VIEW: FLOATING BALL
    // =========================================================================
    inner class FloatingBallView(context: Context) : View(context) {
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

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * resources.displayMetrics.density
        }

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f * resources.displayMetrics.density
            maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
        }

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.parseColor("#E60B0F19")
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

            // Dark frosted glass background
            canvas.drawCircle(cx, cy, radius, bgPaint)

            // Logo drawing clipped to inner circle
            logoBitmap?.let { bmp ->
                val saveCount = canvas.save()
                val clipPath = android.graphics.Path().apply {
                    addCircle(cx, cy, radius - 4f, android.graphics.Path.Direction.CW)
                }
                canvas.clipPath(clipPath)
                val pad = radius * 0.18f
                val dstRect = RectF(cx - radius + pad, cy - radius + pad, cx + radius - pad, cy + radius - pad)
                canvas.drawBitmap(bmp, null, dstRect, logoPaint)
                canvas.restoreToCount(saveCount)
            }

            // Outer Neon Glowing Cyber Ring
            val ringColor = if (isControllerActive) {
                android.graphics.Color.parseColor("#00E5FF") // Active Cyan
            } else {
                android.graphics.Color.parseColor("#6366F1") // Standby Indigo
            }

            glowPaint.color = ringColor
            glowPaint.alpha = 100
            canvas.drawCircle(cx, cy, radius, glowPaint)

            ringPaint.color = ringColor
            ringPaint.alpha = 240
            canvas.drawCircle(cx, cy, radius, ringPaint)

            // Small active status pulse pip
            if (isControllerActive) {
                val pipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#10B981") // Green
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(cx + radius * 0.7f, cy - radius * 0.7f, 4f * resources.displayMetrics.density, pipPaint)
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
    // CUSTOM VIEW: VIRTUAL MOUSE POINTER (CURSOR)
    // =========================================================================
    inner class MousePointerView(context: Context) : View(context) {

        private val density = resources.displayMetrics.density
        private val cursorPath = android.graphics.Path()

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.WHITE
        }

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = android.graphics.Color.parseColor("#0F172A")
            strokeJoin = Paint.Join.ROUND
        }

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            color = android.graphics.Color.parseColor("#00E5FF")
            alpha = 180
        }

        private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            color = android.graphics.Color.parseColor("#00E5FF")
        }

        private var rippleRadius = 0f
        private var rippleAlpha = 0
        private var rippleAnimator: ValueAnimator? = null

        init {
            val s = density
            cursorPath.reset()
            cursorPath.moveTo(0f, 0f)
            cursorPath.lineTo(16f * s, 16f * s)
            cursorPath.lineTo(10f * s, 16f * s)
            cursorPath.lineTo(14f * s, 26f * s)
            cursorPath.lineTo(10f * s, 27.5f * s)
            cursorPath.lineTo(6.5f * s, 17.5f * s)
            cursorPath.lineTo(0f, 22f * s)
            cursorPath.close()
        }

        fun triggerClickRipple() {
            rippleAnimator?.cancel()
            rippleAnimator = ValueAnimator.ofFloat(0f, 26f * density).apply {
                duration = 240
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    rippleRadius = anim.animatedValue as Float
                    rippleAlpha = ((1f - anim.animatedFraction) * 255).toInt()
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            if (rippleRadius > 0f && rippleAlpha > 0) {
                ripplePaint.alpha = rippleAlpha
                canvas.drawCircle(0f, 0f, rippleRadius, ripplePaint)
            }

            canvas.drawPath(cursorPath, glowPaint)
            canvas.drawPath(cursorPath, fillPaint)
            canvas.drawPath(cursorPath, strokePaint)

            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#00E5FF")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(0f, 0f, 2.5f * density, dotPaint)
        }
    }

    // =========================================================================
    // CUSTOM VIEW: VIRTUAL ANALOG JOYSTICK
    // =========================================================================
    inner class AnalogJoystickView(context: Context) : View(context) {
        var onStickMove: ((normX: Float, normY: Float, deltaX: Float, deltaY: Float) -> Unit)? = null
        var onStickTap: (() -> Unit)? = null
        var onPadDrag: ((dx: Int, dy: Int) -> Unit)? = null

        private val density = resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        private var stickX = 0f
        private var stickY = 0f

        private var isOperatingStick = false
        private var isDraggingPad = false
        private var downTime = 0L
        private var downTouchX = 0f
        private var downTouchY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f

        private val baseBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.parseColor("#E6070B14")
        }

        private val baseRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            color = android.graphics.Color.parseColor("#00E5FF")
        }

        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = android.graphics.Color.parseColor("#4400E5FF")
        }

        private val stickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val stickRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = android.graphics.Color.WHITE
        }

        private val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (isOperatingStick && (stickX != 0f || stickY != 0f)) {
                    val maxRadius = getMaxStickRadius()
                    val normX = stickX / maxRadius
                    val normY = stickY / maxRadius
                    onStickMove?.invoke(normX, normY, stickX, stickY)
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }

        private fun getMaxStickRadius(): Float {
            val baseRadius = Math.min(width, height) / 2f - (16f * density)
            val thumbRadius = 22f * density
            return (baseRadius - thumbRadius).coerceAtLeast(10f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val baseRadius = Math.min(width, height) / 2f - (14f * density)
            val thumbRadius = 22f * density

            canvas.drawCircle(cx, cy, baseRadius, baseBgPaint)

            canvas.drawLine(cx - baseRadius * 0.7f, cy, cx + baseRadius * 0.7f, cy, crosshairPaint)
            canvas.drawLine(cx, cy - baseRadius * 0.7f, cx, cy + baseRadius * 0.7f, crosshairPaint)

            canvas.drawCircle(cx, cy, baseRadius, baseRingPaint)

            val thumbCenterX = cx + stickX
            val thumbCenterY = cy + stickY

            stickPaint.shader = RadialGradient(
                thumbCenterX,
                thumbCenterY,
                thumbRadius,
                intArrayOf(
                    android.graphics.Color.parseColor("#00F5FF"),
                    android.graphics.Color.parseColor("#3B82F6"),
                    android.graphics.Color.parseColor("#1E1B4B")
                ),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )

            canvas.drawCircle(thumbCenterX, thumbCenterY, thumbRadius, stickPaint)
            canvas.drawCircle(thumbCenterX, thumbCenterY, thumbRadius, stickRingPaint)

            val pipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.drawCircle(thumbCenterX, thumbCenterY, 4f * density, pipPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val cx = width / 2f
            val cy = height / 2f
            val maxRadius = getMaxStickRadius()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    downTouchX = event.x
                    downTouchY = event.y
                    lastRawX = event.rawX
                    lastRawY = event.rawY

                    val distFromCenter = hypot((event.x - cx).toDouble(), (event.y - cy).toDouble()).toFloat()
                    val outerRimThreshold = maxRadius + (12f * density)

                    if (distFromCenter <= outerRimThreshold) {
                        isOperatingStick = true
                        isDraggingPad = false
                        handleStickMove(event.x - cx, event.y - cy, maxRadius)
                        Choreographer.getInstance().postFrameCallback(frameCallback)
                    } else {
                        isDraggingPad = true
                        isOperatingStick = false
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isOperatingStick) {
                        handleStickMove(event.x - cx, event.y - cy, maxRadius)
                    } else if (isDraggingPad) {
                        val dx = (event.rawX - lastRawX).toInt()
                        val dy = (event.rawY - lastRawY).toInt()
                        onPadDrag?.invoke(dx, dy)
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - downTime
                    val totalMoved = hypot((event.x - downTouchX).toDouble(), (event.y - downTouchY).toDouble())

                    animateStickRelease()

                    if (isOperatingStick && totalMoved < touchSlop && duration < 350) {
                        onStickTap?.invoke()
                    }

                    isOperatingStick = false
                    isDraggingPad = false
                    Choreographer.getInstance().removeFrameCallback(frameCallback)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun handleStickMove(dx: Float, dy: Float, maxR: Float) {
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
            val animator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val fraction = anim.animatedValue as Float
                    stickX = startX * fraction
                    stickY = startY * fraction
                    invalidate()
                }
            }
            animator.start()
        }
    }
}
