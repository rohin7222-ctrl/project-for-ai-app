package com.nextgen.agent

import android.accessibilityservice.AccessibilityService
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
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Premium Cyberpunk Virtual Mouse & Cockpit Controller Manager.
 * Features:
 * 1. Floating Ball Overlay (App logo, draggable, dynamic cyber aura)
 * 2. Precision Sci-Fi Mouse Cursor (Pure white/cyan aerodynamic arrow, targeting tip, click shockwave)
 * 3. Cyber Cockpit Controller HUD:
 *    - 3D Analog Joystick with cardinal LED highlights & precision response curve
 *    - Guaranteed "CLICK (OK)" trigger button
 *    - "BACK" navigation button for third-party apps
 *    - "SCROLL UP / DOWN" swipe buttons for reels/feeds/webpages
 *    - Live X/Y coordinate readout
 *    - Draggable chassis with quick-dismiss handle
 * 4. Strict screen boundary clamping (Pointer NEVER escapes the visible display)
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

    private var analogControllerView: CyberAnalogControllerView? = null
    private var controllerParams: WindowManager.LayoutParams? = null

    // Cursor position in screen pixels (Hotspot at arrow tip)
    var cursorX: Float = 540f
    var cursorY: Float = 1100f

    // Screen dimensions & bounds
    var screenWidth: Int = 1080
        private set
    var screenHeight: Int = 2400
        private set

    var minPointerX: Float = 2f
        private set
    var minPointerY: Float = 2f
        private set
    var maxPointerX: Float = 1040f
        private set
    var maxPointerY: Float = 2340f
        private set

    init {
        updateScreenDimensions()
    }

    fun updateScreenDimensions() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels

        val pointerSize = (38f * dm.density)
        minPointerX = 2f
        minPointerY = 2f
        maxPointerX = (screenWidth - pointerSize).coerceAtLeast(minPointerX)
        maxPointerY = (screenHeight - pointerSize).coerceAtLeast(minPointerY)

        cursorX = cursorX.coerceIn(minPointerX, maxPointerX)
        cursorY = cursorY.coerceIn(minPointerY, maxPointerY)
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
                updateScreenDimensions()
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
                                p.x = p.x.coerceIn(0, (screenWidth - p.width))
                                p.y = p.y.coerceIn(40, (screenHeight - p.height - 40))
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
    // POINTER & ANALOG CONTROLLER TOGGLE
    // ==========================================
    fun togglePointerAndAnalog() {
        vibrate(35)
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
                val pointerSizePx = (38 * appContext.resources.displayMetrics.density).toInt()
                if (mousePointerView == null) {
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

                // 2. Show Cyber Cockpit Controller Overlay
                val density = appContext.resources.displayMetrics.density
                val padWidthPx = (220 * density).toInt()
                val padHeightPx = (200 * density).toInt()

                if (analogControllerView == null) {
                    controllerParams = WindowManager.LayoutParams(
                        padWidthPx,
                        padHeightPx,
                        getOverlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = (screenWidth - padWidthPx - (20 * density).toInt()).coerceAtLeast(10)
                        y = (screenHeight - padHeightPx - (120 * density).toInt()).coerceAtLeast(50)
                    }

                    analogControllerView = CyberAnalogControllerView(appContext).apply {
                        onStickMove = { normX, normY ->
                            moveCursor(normX, normY)
                        }
                        onClickRequested = {
                            performMouseClick()
                        }
                        onBackRequested = {
                            performBackAction()
                        }
                        onScrollRequested = { scrollUp ->
                            performScroll(scrollUp)
                        }
                        onControllerDismiss = {
                            hidePointerAndAnalog()
                        }
                        onPadDrag = { dx, dy ->
                            controllerParams?.let { p ->
                                p.x += dx
                                p.y += dy
                                p.x = p.x.coerceIn(0, (screenWidth - p.width))
                                p.y = p.y.coerceIn(30, (screenHeight - p.height - 30))
                                try {
                                    windowManager.updateViewLayout(this, p)
                                } catch (ignored: Exception) {}
                            }
                        }
                    }
                    windowManager.addView(analogControllerView, controllerParams)
                } else {
                    analogControllerView?.visibility = View.VISIBLE
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
                if (analogControllerView != null) {
                    windowManager.removeView(analogControllerView)
                    analogControllerView = null
                }
                isPointerAndAnalogShowing = false
                floatingBallView?.setControllerActive(false)
            } catch (ignored: Exception) {
                mousePointerView = null
                analogControllerView = null
                isPointerAndAnalogShowing = false
            }
        }
    }

    /**
     * Precision cursor movement with quadratic response curve.
     * Guaranteed strictly within screen boundaries.
     */
    private fun moveCursor(normX: Float, normY: Float) {
        val mag = hypot(normX.toDouble(), normY.toDouble()).toFloat()
        if (mag > 0.04f) {
            // Quadratic curve: slow thumb movement gives sub-pixel precision, fast gives rapid screen sweep
            val speedFactor = (22f * pointerSpeed) * (mag * mag.coerceAtLeast(0.35f))
            val moveX = (normX / mag) * speedFactor
            val moveY = (normY / mag) * speedFactor

            // STRICT SCREEN CLAMPING - Cursor tip NEVER goes off-screen
            cursorX = (cursorX + moveX).coerceIn(minPointerX, maxPointerX)
            cursorY = (cursorY + moveY).coerceIn(minPointerY, maxPointerY)

            pointerParams?.let { p ->
                p.x = cursorX.toInt()
                p.y = cursorY.toInt()
                try {
                    windowManager.updateViewLayout(mousePointerView, p)
                } catch (ignored: Exception) {}
            }

            analogControllerView?.updateCursorCoords(cursorX.toInt(), cursorY.toInt())
        }
    }

    /**
     * Executes reliable click at (cursorX, cursorY) inside ANY active Android app.
     */
    fun performMouseClick() {
        vibrate(45)
        mousePointerView?.triggerClickShockwave()

        val ok = AgentAccessibilityService.performClickAt(cursorX, cursorY)
        if (!ok) {
            if (AgentAccessibilityService.instance == null) {
                mainHandler.post {
                    Toast.makeText(
                        appContext,
                        "Enable 'Aero Fenix' in Accessibility Settings to click inside other apps",
                        Toast.LENGTH_LONG
                    ).show()
                    AgentAccessibilityService.autoEnable(appContext)
                }
            } else {
                mainHandler.post {
                    // Try with slight offset retry if needed
                    AgentAccessibilityService.performClickAt(cursorX + 0.5f, cursorY + 0.5f)
                }
            }
        }
    }

    /**
     * Performs system back action inside any third-party app
     */
    fun performBackAction() {
        vibrate(30)
        val ok = AgentAccessibilityService.perform(AccessibilityService.GLOBAL_ACTION_BACK)
        if (!ok && AgentAccessibilityService.instance == null) {
            AgentAccessibilityService.autoEnable(appContext)
        }
    }

    /**
     * Performs smooth scroll gesture (swipe up / swipe down) across any app
     */
    fun performScroll(scrollUp: Boolean) {
        vibrate(35)
        val midX = screenWidth / 2f
        val startY: Float
        val endY: Float

        if (scrollUp) {
            // Scroll content up (swipe down)
            startY = screenHeight * 0.35f
            endY = screenHeight * 0.70f
        } else {
            // Scroll content down (swipe up)
            startY = screenHeight * 0.70f
            endY = screenHeight * 0.35f
        }
        AgentAccessibilityService.performSwipe(midX, startY, midX, endY, 260)
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
    // 1. CUSTOM VIEW: FLOATING BALL
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

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.parseColor("#EE060A14")
        }

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * resources.displayMetrics.density
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

            // Obsidian glass sphere backdrop
            canvas.drawCircle(cx, cy, radius, bgPaint)

            // Draw app logo inside clipped circle
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

            // Glowing Cyber Ring
            val accentColor = if (isControllerActive) {
                android.graphics.Color.parseColor("#00E5FF") // Active Cyber Cyan
            } else {
                android.graphics.Color.parseColor("#818CF8") // Standby Violet
            }

            glowPaint.color = accentColor
            glowPaint.alpha = 110
            canvas.drawCircle(cx, cy, radius, glowPaint)

            ringPaint.color = accentColor
            ringPaint.alpha = 245
            canvas.drawCircle(cx, cy, radius, ringPaint)

            // Active Green Status Beacon
            if (isControllerActive) {
                val beaconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#10B981")
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(cx + radius * 0.68f, cy - radius * 0.68f, 4.5f * resources.displayMetrics.density, beaconPaint)
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
    // 2. CUSTOM VIEW: SCI-FI PRECISION MOUSE CURSOR
    // =========================================================================
    inner class MousePointerView(context: Context) : View(context) {

        private val density = resources.displayMetrics.density
        private val cursorPath = android.graphics.Path()

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.WHITE
        }

        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
            color = android.graphics.Color.parseColor("#090D1A")
            strokeJoin = Paint.Join.ROUND
        }

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * density
            color = android.graphics.Color.parseColor("#00E5FF")
            alpha = 200
        }

        private val shockwavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
        }

        private var shockwaveRadius = 0f
        private var shockwaveAlpha = 0
        private var cursorScale = 1.0f

        init {
            // High-precision angled Sci-Fi arrow cursor with tip strictly at (0, 0)
            val s = density
            cursorPath.reset()
            cursorPath.moveTo(0f, 0f)
            cursorPath.lineTo(17f * s, 17f * s)
            cursorPath.lineTo(10.5f * s, 17f * s)
            cursorPath.lineTo(14.5f * s, 27f * s)
            cursorPath.lineTo(10.5f * s, 28.5f * s)
            cursorPath.lineTo(6.8f * s, 18.5f * s)
            cursorPath.lineTo(0f, 23.5f * s)
            cursorPath.close()
        }

        fun triggerClickShockwave() {
            // Micro-bounce
            ValueAnimator.ofFloat(0.82f, 1.0f).apply {
                duration = 180
                interpolator = OvershootInterpolator()
                addUpdateListener {
                    cursorScale = it.animatedValue as Float
                    invalidate()
                }
                start()
            }

            // Expanding shockwave ripple
            ValueAnimator.ofFloat(0f, 32f * density).apply {
                duration = 260
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    shockwaveRadius = anim.animatedValue as Float
                    shockwaveAlpha = ((1f - anim.animatedFraction) * 255).toInt()
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Click shockwave ripple at hotspot tip (0, 0)
            if (shockwaveRadius > 0f && shockwaveAlpha > 0) {
                shockwavePaint.color = android.graphics.Color.parseColor("#00E5FF")
                shockwavePaint.alpha = shockwaveAlpha
                canvas.drawCircle(0f, 0f, shockwaveRadius, shockwavePaint)

                // Secondary inner amber ring
                shockwavePaint.color = android.graphics.Color.parseColor("#F59E0B")
                shockwavePaint.alpha = (shockwaveAlpha * 0.6f).toInt()
                canvas.drawCircle(0f, 0f, shockwaveRadius * 0.6f, shockwavePaint)
            }

            canvas.save()
            canvas.scale(cursorScale, cursorScale, 0f, 0f)

            // Neon cyan backlight glow
            canvas.drawPath(cursorPath, glowPaint)

            // High-contrast pure white aerodynamic body
            canvas.drawPath(cursorPath, fillPaint)

            // Deep obsidian border
            canvas.drawPath(cursorPath, outlinePaint)

            // Precision targeting dot at tip (0, 0)
            val pipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#FF2D55") // High-vis Crimson Hotspot
                style = Paint.Style.FILL
            }
            canvas.drawCircle(0f, 0f, 2.5f * density, pipPaint)

            canvas.restore()
        }
    }

    // =========================================================================
    // 3. CUSTOM VIEW: CYBER COCKPIT CONTROLLER HUD
    // =========================================================================
    enum class TouchMode {
        NONE, STICK, DRAG_HEADER, BTN_CLICK, BTN_BACK, BTN_SCROLL_UP, BTN_SCROLL_DOWN, BTN_CLOSE
    }

    inner class CyberAnalogControllerView(context: Context) : View(context) {
        var onStickMove: ((normX: Float, normY: Float) -> Unit)? = null
        var onClickRequested: (() -> Unit)? = null
        var onBackRequested: (() -> Unit)? = null
        var onScrollRequested: ((scrollUp: Boolean) -> Unit)? = null
        var onControllerDismiss: (() -> Unit)? = null
        var onPadDrag: ((dx: Int, dy: Int) -> Unit)? = null

        private val density = resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        // Stick position relative to its well center
        private var stickX = 0f
        private var stickY = 0f

        // Interaction zones
        private var activeTouchMode: TouchMode = TouchMode.NONE
        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var downTime = 0L

        // Coordinates display
        private var coordText = "X: 540  Y: 1100"

        // Paints
        private val chassisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.parseColor("#F0080C16")
        }

        private val chassisStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = android.graphics.Color.parseColor("#00E5FF")
        }

        private val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.parseColor("#3300E5FF")
        }

        private val wellBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = android.graphics.Color.parseColor("#E605070E")
        }

        private val wellRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = android.graphics.Color.parseColor("#4400E5FF")
        }

        private val stickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val stickRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
            color = android.graphics.Color.WHITE
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 10f * density
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        private val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (activeTouchMode == TouchMode.STICK && (stickX != 0f || stickY != 0f)) {
                    val maxR = getMaxStickRadius()
                    val normX = stickX / maxR
                    val normY = stickY / maxR
                    onStickMove?.invoke(normX, normY)
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }

        fun updateCursorCoords(x: Int, y: Int) {
            coordText = "X: $x  Y: $y"
            invalidate()
        }

        private fun getStickCenterX(): Float = 64f * density
        private fun getStickCenterY(): Float = 112f * density
        private fun getWellRadius(): Float = 46f * density
        private fun getThumbRadius(): Float = 20f * density
        private fun getMaxStickRadius(): Float = getWellRadius() - getThumbRadius() + (4f * density)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cornerR = 20f * density

            // 1. Chassis Rounded Cockpit Backdrop
            val chassisRect = RectF(2f, 2f, w - 2f, h - 2f)
            canvas.drawRoundRect(chassisRect, cornerR, cornerR, chassisPaint)
            canvas.drawRoundRect(chassisRect, cornerR, cornerR, chassisStrokePaint)

            // 2. Header Bar (Drag zone + Title + Close button)
            val headerH = 32f * density
            val headerRect = RectF(2f, 2f, w - 2f, headerH)
            canvas.drawRoundRect(headerRect, cornerR, cornerR, headerBgPaint)

            // Title
            textPaint.color = android.graphics.Color.parseColor("#00E5FF")
            textPaint.textSize = 10.5f * density
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("⚡ AERO MOUSE HUD", 14f * density, 21f * density, textPaint)

            // Close button [X]
            textPaint.color = android.graphics.Color.parseColor("#FF5252")
            textPaint.textSize = 14f * density
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("✕", w - 14f * density, 22f * density, textPaint)

            // 3. Analog Joystick Well (Left side)
            val stickCx = getStickCenterX()
            val stickCy = getStickCenterY()
            val wellR = getWellRadius()
            val thumbR = getThumbRadius()

            canvas.drawCircle(stickCx, stickCy, wellR, wellBgPaint)
            canvas.drawCircle(stickCx, stickCy, wellR, wellRingPaint)

            // Directional indicators (▲ ► ▼ ◄) that highlight when tilted
            val maxR = getMaxStickRadius()
            val normX = stickX / maxR
            val normY = stickY / maxR

            drawCardinalIndicator(canvas, stickCx, stickCy - wellR + (7f * density), "▲", normY < -0.3f)
            drawCardinalIndicator(canvas, stickCx + wellR - (7f * density), stickCy + (4f * density), "►", normX > 0.3f)
            drawCardinalIndicator(canvas, stickCx, stickCy + wellR - (2f * density), "▼", normY > 0.3f)
            drawCardinalIndicator(canvas, stickCx - wellR + (7f * density), stickCy + (4f * density), "◄", normX < -0.3f)

            // 4. 3D Metallic Stick Knob
            val thumbCx = stickCx + stickX
            val thumbCy = stickCy + stickY

            stickPaint.shader = RadialGradient(
                thumbCx - thumbR * 0.3f,
                thumbCy - thumbR * 0.3f,
                thumbR * 1.2f,
                intArrayOf(
                    android.graphics.Color.parseColor("#00F5FF"),
                    android.graphics.Color.parseColor("#2563EB"),
                    android.graphics.Color.parseColor("#0F172A")
                ),
                floatArrayOf(0f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(thumbCx, thumbCy, thumbR, stickPaint)
            canvas.drawCircle(thumbCx, thumbCy, thumbR, stickRingPaint)

            // Center glowing core pip
            val pipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.drawCircle(thumbCx, thumbCy, 3.5f * density, pipPaint)

            // 5. Action Dock (Right side)
            val dockLeft = 126f * density

            // Big CLICK (OK) Button
            val clickBtnRect = RectF(dockLeft, 42f * density, w - 16f * density, 92f * density)
            val clickBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = android.graphics.Color.parseColor("#FF00E5FF")
            }
            canvas.drawRoundRect(clickBtnRect, 14f * density, 14f * density, clickBgPaint)

            val clickLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#0B0F19")
                textSize = 13f * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("🎯 CLICK", clickBtnRect.centerX(), clickBtnRect.centerY() + (4.5f * density), clickLabelPaint)

            // BACK Button
            val backBtnRect = RectF(dockLeft, 102f * density, dockLeft + 38f * density, 142f * density)
            val secBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = android.graphics.Color.parseColor("#262E40")
            }
            canvas.drawRoundRect(backBtnRect, 10f * density, 10f * density, secBtnPaint)

            val secTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 10f * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("🔙", backBtnRect.centerX(), backBtnRect.centerY() + (4f * density), secTextPaint)

            // SCROLL UP Button (▲)
            val scrollUpRect = RectF(dockLeft + 44f * density, 102f * density, dockLeft + 68f * density, 142f * density)
            canvas.drawRoundRect(scrollUpRect, 8f * density, 8f * density, secBtnPaint)
            canvas.drawText("▲", scrollUpRect.centerX(), scrollUpRect.centerY() + (4f * density), secTextPaint)

            // SCROLL DOWN Button (▼)
            val scrollDownRect = RectF(dockLeft + 72f * density, 102f * density, w - 16f * density, 142f * density)
            canvas.drawRoundRect(scrollDownRect, 8f * density, 8f * density, secBtnPaint)
            canvas.drawText("▼", scrollDownRect.centerX(), scrollDownRect.centerY() + (4f * density), secTextPaint)

            // 6. Bottom Coordinate Status Bar
            val statusY = h - (12f * density)
            textPaint.color = android.graphics.Color.parseColor("#94A3B8")
            textPaint.textSize = 9.5f * density
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(coordText, w / 2f, statusY, textPaint)
        }

        private fun drawCardinalIndicator(canvas: Canvas, x: Float, y: Float, symbol: String, isLit: Boolean) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isLit) android.graphics.Color.parseColor("#00E5FF") else android.graphics.Color.parseColor("#3300E5FF")
                textSize = if (isLit) 11f * density else 8.5f * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(symbol, x, y, p)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            val w = width.toFloat()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY

                    val stickCx = getStickCenterX()
                    val stickCy = getStickCenterY()
                    val distStick = hypot((x - stickCx).toDouble(), (y - stickCy).toDouble()).toFloat()

                    // Check touch targets
                    if (y <= 34f * density && x >= w - 40f * density) {
                        activeTouchMode = TouchMode.BTN_CLOSE
                    } else if (y <= 34f * density) {
                        activeTouchMode = TouchMode.DRAG_HEADER
                    } else if (distStick <= getWellRadius() + (14f * density)) {
                        activeTouchMode = TouchMode.STICK
                        handleStickMove(x - stickCx, y - stickCy)
                        Choreographer.getInstance().postFrameCallback(frameCallback)
                    } else if (x >= 126f * density && y in (42f * density)..(92f * density)) {
                        activeTouchMode = TouchMode.BTN_CLICK
                    } else if (x in (126f * density)..(164f * density) && y in (102f * density)..(142f * density)) {
                        activeTouchMode = TouchMode.BTN_BACK
                    } else if (x in (170f * density)..(194f * density) && y in (102f * density)..(142f * density)) {
                        activeTouchMode = TouchMode.BTN_SCROLL_UP
                    } else if (x in (198f * density)..(w - 10f * density) && y in (102f * density)..(142f * density)) {
                        activeTouchMode = TouchMode.BTN_SCROLL_DOWN
                    } else {
                        // Drag pad anywhere else on chassis
                        activeTouchMode = TouchMode.DRAG_HEADER
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeTouchMode == TouchMode.STICK) {
                        handleStickMove(x - getStickCenterX(), y - getStickCenterY())
                    } else if (activeTouchMode == TouchMode.DRAG_HEADER) {
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
                    val moved = hypot((event.rawX - downRawX).toDouble(), (event.rawY - downRawY).toDouble())

                    when (activeTouchMode) {
                        TouchMode.STICK -> {
                            animateStickRelease()
                            Choreographer.getInstance().removeFrameCallback(frameCallback)
                            // Tap on stick center = Click!
                            if (moved < touchSlop && duration < 320) {
                                onClickRequested?.invoke()
                            }
                        }
                        TouchMode.BTN_CLICK -> {
                            onClickRequested?.invoke()
                        }
                        TouchMode.BTN_BACK -> {
                            onBackRequested?.invoke()
                        }
                        TouchMode.BTN_SCROLL_UP -> {
                            onScrollRequested?.invoke(true)
                        }
                        TouchMode.BTN_SCROLL_DOWN -> {
                            onScrollRequested?.invoke(false)
                        }
                        TouchMode.BTN_CLOSE -> {
                            onControllerDismiss?.invoke()
                        }
                        TouchMode.DRAG_HEADER -> {
                            // Drag ended
                        }
                        TouchMode.NONE -> {}
                    }
                    activeTouchMode = TouchMode.NONE
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
                duration = 170
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
}
