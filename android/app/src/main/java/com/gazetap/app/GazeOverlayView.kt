package com.gazetap.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Draws either the calibration target or the gaze cursor with its dwell ring.
 *
 * Why a custom View: it needs one draw pass per frame over the camera preview, and a Canvas
 * overlay is far cheaper than animating View hierarchy nodes at 30 fps.
 */
class GazeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var cursorX: Float = 0f
    var cursorY: Float = 0f

    var showCursor: Boolean = false
        set(value) {
            field = value
            if (value) {
                if (hasCursorPosition) startCursorAnimation()
            } else {
                animating = false
                // Next time the cursor appears it must snap to the new gaze point instead of
                // gliding across the screen from wherever it was left.
                hasCursorPosition = false
            }
        }

    private var targetCursorX = 0f
    private var targetCursorY = 0f
    private var animating = false
    private var hasCursorPosition = false

    /**
     * Where the cursor should be. It is drawn towards this point at display refresh rate instead of
     * jumping there once per camera frame: analysis runs at ~15 fps, and moving the dot in one step
     * per analysed frame is what made gaze look like it teleports between random points.
     */
    fun setCursorTarget(x: Float, y: Float) {
        targetCursorX = x
        targetCursorY = y
        if (!hasCursorPosition) {
            // First appearance: appear where the eye is, never travel there from (0,0).
            cursorX = x
            cursorY = y
            hasCursorPosition = true
            invalidate()
            return
        }
        if (!animating && showCursor) startCursorAnimation()
    }

    private fun startCursorAnimation() {
        if (animating) return
        animating = true
        postOnAnimation(cursorAnimator)
    }

    private val cursorAnimator = object : Runnable {
        override fun run() {
            if (!showCursor) {
                animating = false
                return
            }
            val dx = targetCursorX - cursorX
            val dy = targetCursorY - cursorY
            val distance = hypot(dx, dy)
            if (distance <= 0.5f) {
                cursorX = targetCursorX
                cursorY = targetCursorY
                invalidate()
                animating = false
                return
            }
            // Ease towards the target: at 0.45 per display frame the dot needs about four frames
            // (~66 ms, one camera-frame interval) to close the gap, so it is always moving instead
            // of arriving early and waiting. A hard snap reads as jumps; 0.30 read as lag.
            val step = (distance * 0.45f).coerceIn(1f, 200f)
            cursorX += dx / distance * step
            cursorY += dy / distance * step
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onDetachedFromWindow() {
        animating = false
        removeCallbacks(cursorAnimator)
        super.onDetachedFromWindow()
    }

    /** 0..1 dwell progress for the ring around the cursor. */
    var dwellProgress: Float = 0f

    var showCalibrationTarget: Boolean = false
    var targetX: Float = 0f
    var targetY: Float = 0f
    var targetProgress: Float = 0f

    private val targetFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.FILL
    }
    private val targetRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val targetProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEB3B")
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }
    private val cursorRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (showCalibrationTarget) {
            val radius = min(width, height) * 0.035f
            canvas.drawCircle(targetX, targetY, radius, targetFill)
            canvas.drawCircle(targetX, targetY, radius + 8f, targetRing)
            if (targetProgress > 0f) {
                val sweep = 360f * targetProgress.coerceIn(0f, 1f)
                canvas.drawArc(
                    targetX - radius - 20f,
                    targetY - radius - 20f,
                    targetX + radius + 20f,
                    targetY + radius + 20f,
                    -90f, sweep, false, targetProgressPaint
                )
            }
        }

        if (showCursor) {
            canvas.drawCircle(cursorX, cursorY, 14f, cursorFill)
            if (dwellProgress > 0f) {
                val sweep = 360f * dwellProgress.coerceIn(0f, 1f)
                canvas.drawArc(
                    cursorX - 30f, cursorY - 30f, cursorX + 30f, cursorY + 30f,
                    -90f, sweep, false, cursorRing
                )
            }
        }
    }
}
