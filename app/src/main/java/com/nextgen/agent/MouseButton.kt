package com.nextgen.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.hypot

/**
 * Premium Virtual Mouse & Touchpad Manager.
 * Controls the Floating Ball, Mouse Pointer, and Cyber Touchpad/Joystick HUD overlay windows.
 * Executes accessibility actions (Click, Double Click, Long Press, Drag & Drop, Back, Scroll) across all Android apps.
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
    var currentPadMode: MousePadMode = MousePadMode.TRACKPAD
    var padScale: Float = 1.0f
        private set
    var padAlpha: Float = 0.92f
        private set
    var pointerScale: Float = 1.0f
        private set
    var pointerColor: String = "white"
        private set
    var hapticsEnabled: Boolean = true
        private set

    // Drag & Drop State
    var isDragActive: Boolean = false
        private set
    private var dragStartX: Float = 0f
    private var dragStartY: Float = 0f

    // Active overlay states
    var isBallShowing: Boolean = false
        private set
    var isPointerAndControllerShowing: Boolean = false
        private set

    // Overlays Views
    private var floatingBallView: FloatingBallView? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private var mousePointerView: MousePointerView? = null
    private var pointerParams: WindowManager.LayoutParams? = null

    private var mouseControllerView: CyberMouseControllerView? = null
    private var controllerParams: WindowManager.LayoutParams? = null

    // Cursor position in screen pixels (Hotspot strictly at arrow tip (0, 0))
    var cursorX: Float = 540f
    var cursorY: Float = 1100f

    // Screen dimensions & bounds
    var screenWidth: Int = 1080
        private set
    var screenHeight: Int = 2400
        private set

    var minPointerX: Float = 0f
        private set
    var minPointerY: Float = 0f
        private set
    var maxPointerX: Float = 1056f
        private set
    var maxPointerY: Float = 2368f
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

        val pointerWidth = (24f * dm.density * pointerScale)
        val pointerHeight = (30f * dm.density * pointerScale)
        minPointerX = 0f
        minPointerY = 0f
        maxPointerX = (screenWidth - pointerWidth).coerceAtLeast(0f)
        maxPointerY = (screenHeight - pointerHeight).coerceAtLeast(0f)

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
            currentPadMode = MousePadMode.TRACKPAD
            padScale = cfg.mousePadScale
            padAlpha = cfg.mousePadAlpha
            pointerScale = cfg.mousePointerSize
            pointerColor = cfg.mousePointerColor
            hapticsEnabled = cfg.mouseHaptics

            updateScreenDimensions()

            // Update live views if active
            mousePointerView?.let {
                it.setPointerScale(pointerScale)
                it.setPointerTheme(pointerColor)
            }
            mouseControllerView?.let {
                it.currentPadMode = currentPadMode
                it.applyCustomization(padAlpha)
            }

            if (isBallEnabled) {
                if (canDrawOverlays()) {
                    showFloatingBall()
                }
            } else {
                hideFloatingBall()
                hidePointerAndController()
            }
        }
    }

    @JvmOverloads
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
            if (AgentAccessibilityService.instance == null && !AgentAccessibilityService.isEnabled(appContext)) {
                context?.let {
                    Toast.makeText(
                        it,
                        "💡 Enable 'Aero Fenix' in Accessibility Settings so the virtual mouse can click in other apps",
                        Toast.LENGTH_LONG
                    ).show()
                    AgentAccessibilityService.openAccessibilitySettings(it)
                }
            }
        } else {
            hideFloatingBall()
            hidePointerAndController()
        }
    }

    fun setBallSize(sizeDp: Int) {
        ballSizeDp = sizeDp.coerceIn(36, 84)
        if (isBallShowing && floatingBallView != null) {
            val px = (ballSizeDp * appContext.resources.displayMetrics.density).toInt()
            mainHandler.post {
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

    fun setPadMode(mode: MousePadMode = MousePadMode.TRACKPAD) {
        currentPadMode = MousePadMode.TRACKPAD
        mainHandler.post {
            mouseControllerView?.let {
                it.currentPadMode = MousePadMode.TRACKPAD
                it.invalidate()
            }
        }
    }

    fun setPadScale(scale: Float) {
        padScale = scale.coerceIn(0.70f, 1.50f)
        mainHandler.post {
            if (isPointerAndControllerShowing && mouseControllerView != null && controllerParams != null) {
                val density = appContext.resources.displayMetrics.density
                controllerParams?.width = (248 * density * padScale).toInt()
                controllerParams?.height = (250 * density * padScale).toInt()
                try {
                    windowManager.updateViewLayout(mouseControllerView, controllerParams)
                } catch (ignored: Exception) {}
            }
        }
    }

    /** Quick cycle trackpad dimensions (Compact 85% ➔ Standard 100% ➔ Large 125%) */
    fun cyclePadScale(onNewScale: ((Float) -> Unit)? = null) {
        val nextScale = when {
            padScale < 0.95f -> 1.0f
            padScale < 1.15f -> 1.25f
            else -> 0.85f
        }
        setPadScale(nextScale)
        vibrate(30)
        val label = when {
            nextScale < 0.95f -> "Compact (85%)"
            nextScale < 1.15f -> "Standard (100%)"
            else -> "Large (125%)"
        }
        Toast.makeText(appContext, "📐 Trackpad: $label", Toast.LENGTH_SHORT).show()
        onNewScale?.invoke(nextScale)
    }

    fun setPadAlpha(alpha: Float) {
        padAlpha = alpha.coerceIn(0.4f, 1.0f)
        mainHandler.post {
            mouseControllerView?.applyCustomization(padAlpha)
        }
    }

    fun setPointerScale(scale: Float) {
        pointerScale = scale.coerceIn(0.5f, 2.4f)
        updateScreenDimensions()
        mainHandler.post {
            mousePointerView?.setPointerScale(pointerScale)
            if (mousePointerView != null && pointerParams != null) {
                val density = appContext.resources.displayMetrics.density
                pointerParams?.width = (28 * density * pointerScale).toInt()
                pointerParams?.height = (34 * density * pointerScale).toInt()
                try {
                    windowManager.updateViewLayout(mousePointerView, pointerParams)
                } catch (ignored: Exception) {}
            }
        }
    }

    fun setPointerColor(color: String) {
        pointerColor = color
        mainHandler.post {
            mousePointerView?.setPointerTheme(color)
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        hapticsEnabled = enabled
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
                            togglePointerAndController()
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
    // POINTER & CONTROLLER TOGGLE
    // ==========================================
    fun togglePointerAndController() {
        vibrate(35)
        if (isPointerAndControllerShowing) {
            hidePointerAndController()
        } else {
            showPointerAndController()
        }
    }

    fun showPointerAndController() {
        if (!canDrawOverlays()) return
        mainHandler.post {
            try {
                updateScreenDimensions()

                // Check Accessibility Service status
                if (AgentAccessibilityService.instance == null && !AgentAccessibilityService.isEnabled(appContext)) {
                    Toast.makeText(
                        appContext,
                        "💡 Enable 'Aero Fenix' Accessibility Service to click inside other apps",
                        Toast.LENGTH_LONG
                    ).show()
                    AgentAccessibilityService.openAccessibilitySettings(appContext)
                }

                val density = appContext.resources.displayMetrics.density

                // 1. Show Authentic Native Mouse Pointer Overlay
                val pointerWidthPx = (28 * density * pointerScale).toInt()
                val pointerHeightPx = (34 * density * pointerScale).toInt()

                if (mousePointerView == null) {
                    pointerParams = WindowManager.LayoutParams(
                        pointerWidthPx,
                        pointerHeightPx,
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
                    mousePointerView = MousePointerView(appContext).apply {
                        setPointerScale(this@MouseButtonManager.pointerScale)
                        setPointerTheme(this@MouseButtonManager.pointerColor)
                        setDragActive(this@MouseButtonManager.isDragActive)
                    }
                    windowManager.addView(mousePointerView, pointerParams)
                } else {
                    mousePointerView?.visibility = View.VISIBLE
                    mousePointerView?.setPointerScale(pointerScale)
                    mousePointerView?.setPointerTheme(pointerColor)
                    mousePointerView?.setDragActive(isDragActive)
                }

                // 2. Show Precision Trackpad Controller HUD Overlay
                val padWidthPx = (248 * density * padScale).toInt()
                val padHeightPx = (250 * density * padScale).toInt()

                if (mouseControllerView == null) {
                    controllerParams = WindowManager.LayoutParams(
                        padWidthPx,
                        padHeightPx,
                        getOverlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = (screenWidth - padWidthPx - (16 * density).toInt()).coerceAtLeast(10)
                        y = (screenHeight - padHeightPx - (100 * density).toInt()).coerceAtLeast(50)
                    }

                    mouseControllerView = CyberMouseControllerView(appContext).apply {
                        this.currentPadMode = MousePadMode.TRACKPAD
                        this.applyCustomization(this@MouseButtonManager.padAlpha)
                        this.setDragState(this@MouseButtonManager.isDragActive)
                        onTrackpadMove = { dx, dy ->
                            moveCursorBy(dx, dy)
                        }
                        onLeftClick = {
                            if (isDragActive) {
                                toggleDragMode()
                            } else {
                                performMouseClick()
                            }
                        }
                        onRightClick = {
                            performRightClick()
                        }
                        onDoubleTap = {
                            performDoubleTap()
                        }
                        onLongClick = {
                            performLongClick()
                        }
                        onDragToggle = {
                            toggleDragMode()
                        }
                        onScroll = { dir ->
                            performScroll(dir)
                        }
                        onResizeRequested = {
                            cyclePadScale { s ->
                                try {
                                    val store = Store(appContext)
                                    store.save(store.load().copy(mousePadScale = s))
                                } catch (ignored: Exception) {}
                            }
                        }
                        onSpeedGearToggled = {
                            togglePointerSpeedGear()
                        }
                        onAiSnapRequested = {
                            smartAiSnapAndClick()
                        }
                        onDismiss = {
                            hidePointerAndController()
                        }
                        onControllerDrag = { dx, dy ->
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
                    windowManager.addView(mouseControllerView, controllerParams)
                } else {
                    mouseControllerView?.visibility = View.VISIBLE
                    mouseControllerView?.currentPadMode = MousePadMode.TRACKPAD
                    mouseControllerView?.applyCustomization(padAlpha)
                    mouseControllerView?.setDragState(isDragActive)
                    mouseControllerView?.isHyperSpeed = (pointerSpeed > 1.5f)
                    mouseControllerView?.refreshAccessibilityStatus()
                }

                isPointerAndControllerShowing = true
                floatingBallView?.setControllerActive(true)

            } catch (e: Exception) {
                isPointerAndControllerShowing = false
            }
        }
    }

    fun hidePointerAndController() {
        mainHandler.post {
            try {
                if (isDragActive) {
                    isDragActive = false
                }
                if (mousePointerView != null) {
                    windowManager.removeView(mousePointerView)
                    mousePointerView = null
                }
                if (mouseControllerView != null) {
                    windowManager.removeView(mouseControllerView)
                    mouseControllerView = null
                }
                isPointerAndControllerShowing = false
                floatingBallView?.setControllerActive(false)
            } catch (ignored: Exception) {
                mousePointerView = null
                mouseControllerView = null
                isPointerAndControllerShowing = false
                floatingBallView?.setControllerActive(false)
            }
        }
    }

    /**
     * Precision Trackpad Movement (Sub-pixel micro precision + progressive exponential ballistics)
     */
    private fun moveCursorBy(dx: Float, dy: Float) {
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        // Apple/Windows Precision Ballistics Curve:
        // Slow strokes (<2.5dp) get fine-dampened 0.85x for micro precision.
        // Medium strokes get smooth linear tracking.
        // Swift flicks get dynamic acceleration up to 3.5x.
        val accel = when {
            dist < 2.5f -> 0.85f
            dist < 8.0f -> 1.0f + (dist / 14f)
            dist < 18.0f -> 1.35f + ((dist - 8f) / 10f)
            else -> (2.4f + (dist / 25f)).coerceAtMost(3.5f)
        }
        val moveX = dx * pointerSpeed * accel
        val moveY = dy * pointerSpeed * accel

        // STRICT SCREEN CLAMPING - Pointer tip NEVER leaves the screen
        cursorX = (cursorX + moveX).coerceIn(minPointerX, maxPointerX)
        cursorY = (cursorY + moveY).coerceIn(minPointerY, maxPointerY)

        // AI Magnetic Snap near clickable elements when moving gently
        if (dist < 5.0f && AgentAccessibilityService.instance != null) {
            val rect = AgentAccessibilityService.findClickableBoundsNear(cursorX.toInt(), cursorY.toInt(), 28)
            if (rect != null) {
                val cx = rect.centerX().toFloat().coerceIn(minPointerX, maxPointerX)
                val cy = rect.centerY().toFloat().coerceIn(minPointerY, maxPointerY)
                val toCenterX = cx - cursorX
                val toCenterY = cy - cursorY
                cursorX += toCenterX * 0.35f
                cursorY += toCenterY * 0.35f
                mousePointerView?.setMagneticLocked(true)
            } else {
                mousePointerView?.setMagneticLocked(false)
            }
        } else {
            mousePointerView?.setMagneticLocked(false)
        }

        updatePointerPositionOnScreen()
    }

    private fun updatePointerPositionOnScreen() {
        pointerParams?.let { p ->
            p.x = cursorX.toInt()
            p.y = cursorY.toInt()
            try {
                windowManager.updateViewLayout(mousePointerView, p)
            } catch (ignored: Exception) {}
        }
        mouseControllerView?.updateCursorCoords(cursorX.toInt(), cursorY.toInt())
    }

    /**
     * Drag & Drop Toggle
     */
    fun toggleDragMode() {
        if (!isDragActive) {
            isDragActive = true
            dragStartX = cursorX
            dragStartY = cursorY
            vibrate(50)
            mousePointerView?.setDragActive(true)
            mouseControllerView?.setDragState(true)
            Toast.makeText(appContext, "✋ Drag Held! Move pointer to destination, then tap DROP / CLICK", Toast.LENGTH_SHORT).show()
        } else {
            isDragActive = false
            mousePointerView?.setDragActive(false)
            mouseControllerView?.setDragState(false)
            vibrate(70)
            val dropX = cursorX
            val dropY = cursorY
            AgentAccessibilityService.performDrag(dragStartX, dragStartY, dropX, dropY, 400)
            Toast.makeText(appContext, "✅ Item Dropped", Toast.LENGTH_SHORT).show()
        }
    }

    fun performClick() = performMouseClick()

    fun togglePointerSpeedGear() {
        val nextFast = (pointerSpeed <= 1.2f)
        pointerSpeed = if (nextFast) 2.5f else 1.0f
        mouseControllerView?.isHyperSpeed = nextFast
        mouseControllerView?.invalidate()
        vibrate(35)
        val msg = if (nextFast) "🚀 Trackpad: Hyper Velocity (2.5x)" else "⚡ Trackpad: Precision Mode (1.0x)"
        Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
    }

    fun smartAiSnapAndClick() {
        vibrate(40)
        mousePointerView?.triggerClickAnimation()
        val a11y = AgentAccessibilityService.instance
        if (a11y != null) {
            val rect = AgentAccessibilityService.findClickableBoundsNear(cursorX.toInt(), cursorY.toInt(), 60)
            if (rect != null) {
                cursorX = rect.centerX().toFloat().coerceIn(minPointerX, maxPointerX)
                cursorY = rect.centerY().toFloat().coerceIn(minPointerY, maxPointerY)
                updatePointerPositionOnScreen()
                mousePointerView?.setMagneticLocked(true)
                mainHandler.postDelayed({ mousePointerView?.setMagneticLocked(false) }, 800)
                AgentAccessibilityService.performClickAt(cursorX, cursorY)
                a11y.showFloatingHud("🎯 AI Snap: Clicked target")
                return
            }
        }
        performMouseClick()
    }

    fun centerPointer() {
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f
        updatePointerPositionOnScreen()
        vibrate(25)
    }

    /**
     * Primary Mouse Click (Left-Click) inside ANY active Android app.
     */
    fun performMouseClick() {
        vibrate(35)
        mousePointerView?.triggerClickAnimation()

        val ok = AgentAccessibilityService.performClickAt(cursorX, cursorY)
        if (!ok) {
            if (AgentAccessibilityService.instance == null) {
                mainHandler.post {
                    Toast.makeText(
                        appContext,
                        "⚠️ Enable 'Aero Fenix' in Accessibility Settings to click inside other apps",
                        Toast.LENGTH_LONG
                    ).show()
                    AgentAccessibilityService.openAccessibilitySettings(appContext)
                }
            } else {
                mainHandler.postDelayed({
                    AgentAccessibilityService.performClickAt(cursorX, cursorY)
                }, 40)
            }
        }
    }

    /**
     * Double Click
     */
    fun performDoubleTap() {
        vibrate(45)
        mousePointerView?.triggerClickAnimation()
        AgentAccessibilityService.performDoubleTapAt(cursorX, cursorY)
    }

    /**
     * Long Click / Select
     */
    fun performLongClick() {
        vibrate(70)
        mousePointerView?.triggerClickAnimation()
        AgentAccessibilityService.performLongClickAt(cursorX, cursorY)
    }

    /**
     * Right-Click: In Android, physical mouse right-click performs BACK navigation.
     */
    fun performRightClick() {
        vibrate(30)
        val ok = AgentAccessibilityService.perform(AccessibilityService.GLOBAL_ACTION_BACK)
        if (!ok && AgentAccessibilityService.instance == null) {
            AgentAccessibilityService.openAccessibilitySettings(appContext)
        }
    }

    /**
     * 4-Way directional scroll gesture (UP, DOWN, LEFT, RIGHT) across any third-party app
     */
    fun performScroll(direction: ScrollDirection) {
        vibrate(22)
        val midX = screenWidth / 2f
        val midY = screenHeight / 2f

        when (direction) {
            ScrollDirection.UP -> {
                val startY = screenHeight * 0.35f
                val endY = screenHeight * 0.70f
                AgentAccessibilityService.performSwipe(midX, startY, midX, endY, 200)
            }
            ScrollDirection.DOWN -> {
                val startY = screenHeight * 0.70f
                val endY = screenHeight * 0.35f
                AgentAccessibilityService.performSwipe(midX, startY, midX, endY, 200)
            }
            ScrollDirection.LEFT -> {
                // Swipe content left: swipe finger from left to right across screen
                val startX = screenWidth * 0.25f
                val endX = screenWidth * 0.75f
                AgentAccessibilityService.performSwipe(startX, midY, endX, midY, 200)
            }
            ScrollDirection.RIGHT -> {
                // Swipe content right: swipe finger from right to left across screen
                val startX = screenWidth * 0.75f
                val endX = screenWidth * 0.25f
                AgentAccessibilityService.performSwipe(startX, midY, endX, midY, 200)
            }
        }
    }

    /**
     * Smooth page scroll gesture (swipe up / swipe down) across any third-party app
     */
    fun performScroll(scrollUp: Boolean) {
        performScroll(if (scrollUp) ScrollDirection.UP else ScrollDirection.DOWN)
    }

    private fun vibrate(ms: Long) {
        if (!hapticsEnabled) return
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

        @JvmStatic
        fun getInstance(context: Context): MouseButtonManager {
            return instance ?: synchronized(this) {
                instance ?: MouseButtonManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
