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
            currentPadMode = if (cfg.mousePadMode.equals("analog", ignoreCase = true)) {
                MousePadMode.JOYSTICK
            } else {
                MousePadMode.TRACKPAD
            }
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

    fun setPadMode(mode: MousePadMode) {
        currentPadMode = mode
        mainHandler.post {
            mouseControllerView?.let {
                it.currentPadMode = mode
                it.invalidate()
            }
        }
    }

    fun setPadScale(scale: Float) {
        padScale = scale.coerceIn(0.75f, 1.4f)
        mainHandler.post {
            if (isPointerAndControllerShowing && mouseControllerView != null && controllerParams != null) {
                val density = appContext.resources.displayMetrics.density
                controllerParams?.width = (226 * density * padScale).toInt()
                controllerParams?.height = (230 * density * padScale).toInt()
                try {
                    windowManager.updateViewLayout(mouseControllerView, controllerParams)
                } catch (ignored: Exception) {}
            }
        }
    }

    fun setPadAlpha(alpha: Float) {
        padAlpha = alpha.coerceIn(0.4f, 1.0f)
        mainHandler.post {
            mouseControllerView?.applyCustomization(padAlpha)
        }
    }

    fun setPointerScale(scale: Float) {
        pointerScale = scale.coerceIn(0.6f, 1.8f)
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

                // 2. Show Dual-Mode Touchpad/Joystick Controller HUD Overlay
                val padWidthPx = (226 * density * padScale).toInt()
                val padHeightPx = (230 * density * padScale).toInt()

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
                        this.currentPadMode = this@MouseButtonManager.currentPadMode
                        this.applyCustomization(this@MouseButtonManager.padAlpha)
                        this.setDragState(this@MouseButtonManager.isDragActive)
                        onTrackpadMove = { dx, dy ->
                            moveCursorBy(dx, dy)
                        }
                        onStickMove = { normX, normY ->
                            moveCursorAnalog(normX, normY)
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
                        onScroll = { scrollUp ->
                            performScroll(scrollUp)
                        }
                        onModeToggled = { mode ->
                            this@MouseButtonManager.currentPadMode = mode
                            vibrate(25)
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
                    mouseControllerView?.currentPadMode = currentPadMode
                    mouseControllerView?.applyCustomization(padAlpha)
                    mouseControllerView?.setDragState(isDragActive)
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
     * Precision Trackpad Movement (1:1 finger displacement with natural velocity acceleration)
     */
    private fun moveCursorBy(dx: Float, dy: Float) {
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val accel = (1.0f + (dist / 14f)).coerceIn(1.0f, 3.2f)
        val moveX = dx * pointerSpeed * accel
        val moveY = dy * pointerSpeed * accel

        // STRICT SCREEN CLAMPING - Pointer tip NEVER leaves the screen
        cursorX = (cursorX + moveX).coerceIn(minPointerX, maxPointerX)
        cursorY = (cursorY + moveY).coerceIn(minPointerY, maxPointerY)

        updatePointerPositionOnScreen()
    }

    /**
     * Joystick Movement (Continuous speed proportional to thumb tilt)
     */
    private fun moveCursorAnalog(normX: Float, normY: Float) {
        val mag = hypot(normX.toDouble(), normY.toDouble()).toFloat()
        if (mag > 0.05f) {
            val speedFactor = (20f * pointerSpeed) * (mag * mag.coerceAtLeast(0.35f))
            val moveX = (normX / mag) * speedFactor
            val moveY = (normY / mag) * speedFactor

            cursorX = (cursorX + moveX).coerceIn(minPointerX, maxPointerX)
            cursorY = (cursorY + moveY).coerceIn(minPointerY, maxPointerY)

            updatePointerPositionOnScreen()
        }
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
     * Smooth page scroll gesture (swipe up / swipe down) across any third-party app
     */
    fun performScroll(scrollUp: Boolean) {
        vibrate(25)
        val midX = screenWidth / 2f
        val startY: Float
        val endY: Float

        if (scrollUp) {
            startY = screenHeight * 0.35f
            endY = screenHeight * 0.70f
        } else {
            startY = screenHeight * 0.70f
            endY = screenHeight * 0.35f
        }
        AgentAccessibilityService.performSwipe(midX, startY, midX, endY, 240)
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

        fun getInstance(context: Context): MouseButtonManager {
            return instance ?: synchronized(this) {
                instance ?: MouseButtonManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
