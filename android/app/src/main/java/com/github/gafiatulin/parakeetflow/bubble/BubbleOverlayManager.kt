package com.github.gafiatulin.parakeetflow.bubble

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.github.gafiatulin.parakeetflow.core.model.AppPhase
import com.github.gafiatulin.parakeetflow.core.model.BubblePosition
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot

@Singleton
class BubbleOverlayManager @Inject constructor() {

    companion object {
        private const val TAG = "BubbleOverlay"
        private const val BUBBLE_SIZE_DP = 120  // 64dp bubble + room for pulsation
        private const val DISMISS_DISTANCE_DP = 80  // closeable area radius (library default ~67dp)
        private const val CLOSE_BOTTOM_PADDING_DP = 80  // close icon bottom padding
        private const val EDGE_MARGIN_PX = 16
        private const val BOTTOM_SAFE_MARGIN_DP = 64  // keep bubble above gesture nav area
    }

    private var windowManager: WindowManager? = null
    private var appContext: Context? = null
    private var density = 3f

    // Bubble window
    private var bubbleView: ComposeView? = null
    private var lifecycleOwner: BubbleLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // Dismiss zone window
    private var dismissView: ComposeView? = null
    private var dismissLifecycleOwner: BubbleLifecycleOwner? = null

    // State
    val phase = mutableStateOf(AppPhase.IDLE)
    private val dismissProgress = mutableFloatStateOf(0f)

    private var screenWidth = 0
    private var screenHeight = 0
    private var bubbleSizePx = 0
    private var dismissRadiusPx = 0

    // Current position (Gravity.TOP | Gravity.LEFT coordinates)
    private var currentX = 0
    private var currentY = 0
    private var minBubbleY = 0  // highest Y the bubble can reach (negative = over status bar)

    // Dismiss target center (bottom center of screen)
    private var dismissCenterX = 0
    private var dismissCenterY = 0
    private var maxBubbleY = 0  // lowest Y the bubble can rest at (above gesture nav)

    private var wasInDismissZone = false
    private var snapAnim: SpringAnimation? = null

    private var onDismiss: (() -> Unit)? = null
    private var onPositionChanged: ((BubblePosition) -> Unit)? = null

    fun show(
        context: Context,
        initialPosition: BubblePosition,
        onTap: () -> Unit,
        onHoldStart: () -> Unit,
        onHoldEnd: () -> Unit,
        onDismiss: () -> Unit,
        onPositionChanged: (BubblePosition) -> Unit
    ) {
        if (bubbleView != null) return

        this.onDismiss = onDismiss
        this.onPositionChanged = onPositionChanged
        this.appContext = context.applicationContext

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val bounds = wm.maximumWindowMetrics.bounds
        screenWidth = bounds.width()
        screenHeight = bounds.height()
        density = context.resources.displayMetrics.density
        bubbleSizePx = (BUBBLE_SIZE_DP * density).toInt()
        dismissRadiusPx = (DISMISS_DISTANCE_DP * density).toInt()

        // Dismiss target = bottom center, matching the dismiss circle position (200dp + 48dp half-icon from bottom)
        dismissCenterX = screenWidth / 2
        dismissCenterY = screenHeight - (248 * density).toInt()
        maxBubbleY = screenHeight - ((BOTTOM_SAFE_MARGIN_DP + BUBBLE_SIZE_DP) * density).toInt()

        // The visible bubble (64dp) is centered in a larger container.
        // Offset default position so the visible circle sits at the screen edge.
        val containerPaddingPx = ((BUBBLE_SIZE_DP - 64) / 2 * density).toInt()

        // Allow the visible bubble to be dragged all the way to the top of the
        // screen (over the status bar), matching apps like Whisperian. The
        // overlay already uses FLAG_LAYOUT_NO_LIMITS so it can render up there.
        minBubbleY = -containerPaddingPx

        // Restore position or default to right edge, center
        if (initialPosition.x >= 0 && initialPosition.y >= 0) {
            currentX = initialPosition.x.coerceIn(
                EDGE_MARGIN_PX - containerPaddingPx,
                screenWidth - bubbleSizePx - EDGE_MARGIN_PX + containerPaddingPx
            )
            currentY = initialPosition.y.coerceIn(minBubbleY, maxBubbleY)
        } else {
            currentX = screenWidth - bubbleSizePx - EDGE_MARGIN_PX + containerPaddingPx
            currentY = screenHeight / 2 - bubbleSizePx / 2
        }

        // Use TOP|LEFT gravity for straightforward coordinate math
        val params = createOverlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = currentX
            y = currentY
        }
        layoutParams = params

        val owner = BubbleLifecycleOwner()
        lifecycleOwner = owner

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                BubbleView(
                    phase = phase.value,
                    dismissProgress = dismissProgress.floatValue,
                    onTap = onTap,
                    onHoldStart = onHoldStart,
                    onHoldEnd = onHoldEnd,
                    onDragStart = { onDragStarted(context) },
                    onDrag = { delta -> handleDrag(delta) },
                    onDragEnd = { handleDragEnd() }
                )
            }
        }

        owner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        bubbleView = view
        wm.addView(view, params)
        Log.d(TAG, "Bubble shown at ($currentX, $currentY)")
    }

    // -- Drag handling --

    private fun onDragStarted(context: Context) {
        wasInDismissZone = false
        dismissProgress.floatValue = 0f
        snapAnim?.cancel()
        showDismissZone(context)
    }

    private fun handleDrag(delta: Offset) {
        // Always move the bubble with the finger — never reposition the window independently
        currentX = (currentX + delta.x.toInt()).coerceIn(0, screenWidth - bubbleSizePx)
        currentY = (currentY + delta.y.toInt()).coerceIn(minBubbleY, screenHeight - bubbleSizePx)
        updateBubblePosition()

        // Calculate distance from bubble center to dismiss target
        val bubbleCenterX = currentX + bubbleSizePx / 2
        val bubbleCenterY = currentY + bubbleSizePx / 2
        val dist = hypot(
            (bubbleCenterX - dismissCenterX).toDouble(),
            (bubbleCenterY - dismissCenterY).toDouble()
        )

        val progress = (1.0 - (dist / dismissRadiusPx)).coerceIn(0.0, 1.0).toFloat()
        dismissProgress.floatValue = progress

        // Below the top edge of the dismiss icon counts as in dismiss zone
        val dismissIconTopY = dismissCenterY - (48 * density).toInt()  // 96dp icon / 2
        val belowDismiss = bubbleCenterY > dismissIconTopY
        if (belowDismiss && progress < 0.3f) {
            dismissProgress.floatValue = 0.5f  // visual feedback when below dismiss area
        }
        val inDismissZone = progress > 0.3f || belowDismiss

        // Haptic tick on entering/leaving dismiss zone
        if (inDismissZone && !wasInDismissZone) {
            performHapticTick()
        } else if (!inDismissZone && wasInDismissZone) {
            performHapticTick()
        }
        wasInDismissZone = inDismissZone
    }

    private fun handleDragEnd() {
        val dismissIconTopY = dismissCenterY - (48 * density).toInt()
        val bubbleCenterY = currentY + bubbleSizePx / 2
        val belowDismiss = bubbleCenterY > dismissIconTopY
        val inDismissZone = dismissProgress.floatValue > 0.3f || belowDismiss
        dismissProgress.floatValue = 0f
        hideDismissZone()

        if (inDismissZone) {
            Log.d(TAG, "Bubble dismissed via drag")
            performHapticTick()
            onDismiss?.invoke()
            return
        }

        animateSnapToEdge()
        onPositionChanged?.invoke(BubblePosition(currentX, currentY))
    }

    private fun animateSnapToEdge() {
        snapAnim?.cancel()

        // The visible bubble (64dp) is centered in a 120dp container.
        // Offset so the visible circle sits flush against the screen edge.
        val containerPaddingPx = ((BUBBLE_SIZE_DP - 64) / 2 * density).toInt()
        val bubbleCenterX = currentX + bubbleSizePx / 2
        val targetX = if (bubbleCenterX < screenWidth / 2) {
            EDGE_MARGIN_PX - containerPaddingPx
        } else {
            screenWidth - bubbleSizePx - EDGE_MARGIN_PX + containerPaddingPx
        }

        // Clamp Y so bubble doesn't rest in gesture nav area
        currentY = currentY.coerceAtMost(maxBubbleY)
        updateBubblePosition()

        snapAnim = SpringAnimation(FloatValueHolder(currentX.toFloat())).apply {
            spring = SpringForce(targetX.toFloat()).apply {
                stiffness = SpringForce.STIFFNESS_LOW
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            }
            addUpdateListener { _, value, _ ->
                currentX = value.toInt()
                updateBubblePosition()
            }
            addEndListener { _, _, _, _ ->
                snapAnim = null
                onPositionChanged?.invoke(BubblePosition(currentX, currentY))
            }
            start()
        }
    }

    private fun updateBubblePosition() {
        val params = layoutParams ?: return
        val wm = windowManager ?: return
        val view = bubbleView ?: return

        params.x = currentX
        params.y = currentY
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    // -- Dismiss zone overlay --

    private fun showDismissZone(context: Context) {
        if (dismissView != null) return
        val wm = windowManager ?: return

        val owner = BubbleLifecycleOwner()
        dismissLifecycleOwner = owner

        // Use explicit full-screen size to cover status bar and nav bar
        val params = createOverlayParams(screenWidth, screenHeight).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                DismissZoneView(progress = dismissProgress.floatValue)
            }
        }

        owner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        dismissView = view
        wm.addView(view, params)
    }

    private fun hideDismissZone() {
        dismissLifecycleOwner?.let {
            it.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            it.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            it.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        dismissLifecycleOwner = null
        dismissView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        dismissView = null
    }

    // -- Haptics --

    private fun performHapticTick() {
        try {
            val ctx = appContext ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic feedback failed", e)
        }
    }

    // -- Public API --

    fun updatePhase(newPhase: AppPhase) {
        phase.value = newPhase
    }

    val isShowing: Boolean
        get() = bubbleView != null

    fun dismiss() {
        snapAnim?.cancel()
        snapAnim = null
        hideDismissZone()

        lifecycleOwner?.let {
            it.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            it.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            it.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        lifecycleOwner = null

        bubbleView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {
                Log.e(TAG, "Error removing bubble view", e)
            }
        }
        bubbleView = null
        layoutParams = null
        Log.d(TAG, "Bubble dismissed")
    }

    private fun createOverlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )
}

private class BubbleLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        savedStateRegistryController.performRestore(null)
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}
