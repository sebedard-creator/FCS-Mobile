package com.fcsmobile.app

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import androidx.media3.ui.TimeBar
import kotlin.math.abs

/** Horizontal navigation on the media surface, leaving video controls alone. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SwipeMediaFrame(context: Context) : FrameLayout(context) {
    var navigate: (Int) -> Unit = {}
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var blocked = false
    private var dragging = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                dragging = false
                blocked = touchesControl(this, event.rawX.toInt(), event.rawY.toInt())
            }
            MotionEvent.ACTION_POINTER_DOWN -> blocked = true
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (!blocked && abs(dx) > slop && abs(dx) > abs(event.y - downY) * 1.5f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_POINTER_DOWN -> { blocked = true; resetPosition() }
            MotionEvent.ACTION_MOVE -> if (!blocked) {
                val dx = event.x - downX
                if (abs(dx) > slop && abs(dx) > abs(event.y - downY) * 1.5f) dragging = true
                if (dragging) for (index in 0 until childCount) {
                    getChildAt(index).translationX = (dx * .25f).coerceIn(-width * .2f, width * .2f)
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val change = !blocked && abs(dx) >= maxOf(48f * density, width * .12f) &&
                    abs(dx) > abs(event.y - downY) * 1.5f
                resetPosition()
                if (change) navigate(if (dx < 0) 1 else -1)
                else if (!dragging) performClick()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> { resetPosition(); dragging = false }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun resetPosition() {
        for (index in 0 until childCount) getChildAt(index).animate().translationX(0f).setDuration(120).start()
    }

    private fun touchesControl(view: View, x: Int, y: Int): Boolean {
        if (!view.isShown) return false
        if (view is ImageButton || view is Button || view is SeekBar || view is TimeBar) {
            val bounds = Rect()
            if (view.getGlobalVisibleRect(bounds) && bounds.contains(x, y)) return true
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            if (touchesControl(view.getChildAt(index), x, y)) return true
        }
        return false
    }
}
