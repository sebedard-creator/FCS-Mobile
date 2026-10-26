package com.fcsmobile.app

import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.AbsListView
import androidx.appcompat.widget.AppCompatTextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** A temporary date landmark; it never intercepts the gallery's gestures. */
class ScrollDateIndicator(context: Context) : AppCompatTextView(context) {
    private val month = SimpleDateFormat("LLLL yyyy", Locale.FRENCH)
    private val year = SimpleDateFormat("yyyy", Locale.FRENCH)
    private var moving = false
    private var lastPosition = 0f
    private var lastSample = 0L
    private var speed = 0f
    private var yearsOnly = false
    private var takenAt = 0L
    private var labelDate = -1L
    private var labelYears = false
    private var fadingOut = false
    private val hide = Runnable {
        fadingOut = true
        animate().alpha(0f).setDuration(180).withEndAction {
            if (!moving) { visibility = View.INVISIBLE; fadingOut = false }
        }.start()
    }

    init {
        val style = AppStyle(context)
        setTextColor(style.onAccent)
        setTypeface(typeface, Typeface.BOLD)
        textSize = 19f
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
        minWidth = dp(92)
        maxLines = 1
        elevation = dp(6).toFloat()
        background = style.rounded(style.accent, 16)
        isClickable = false
        isFocusable = false
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        visibility = View.INVISIBLE
        alpha = 0f
    }

    fun onScrollStateChanged(state: Int) {
        moving = state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
        removeCallbacks(hide)
        if (!moving) {
            speed = 0f
            lastSample = 0
            yearsOnly = false
            if (visibility == View.VISIBLE) {
                updateLabel(false)
                postDelayed(hide, 700)
            }
        } else if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
            speed = 0f
            lastSample = 0
            yearsOnly = false
        }
    }

    fun onScroll(position: Float, photoTimestamp: Long) {
        takenAt = photoTimestamp
        val now = SystemClock.uptimeMillis()
        val distance = abs(position - lastPosition)
        if (!moving) {
            if (visibility == View.VISIBLE && !fadingOut) updateLabel(false)
            lastPosition = position
            lastSample = 0
            return
        }
        if (distance <= .002f) return
        if (lastSample > 0 && now > lastSample) {
            val elapsed = now - lastSample
            val current = distance * 1000f / elapsed
            speed = if (elapsed > 250) current else speed * .6f + current * .4f
        }
        lastPosition = position
        lastSample = now
        // Hysteresis keeps the label stable as a fling decelerates.
        yearsOnly = if (yearsOnly) speed >= 8f else speed >= 12f
        updateLabel(yearsOnly)
        removeCallbacks(hide)
        if (visibility != View.VISIBLE || fadingOut) {
            animate().cancel()
            fadingOut = false
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(100).start()
        }
    }

    private fun updateLabel(onlyYear: Boolean) {
        if (labelDate == takenAt && labelYears == onlyYear) return
        labelDate = takenAt
        labelYears = onlyYear
        val value = (if (onlyYear) year else month).format(Date(takenAt * 1000))
        if (text.toString() != value) text = value
        textSize = if (onlyYear) 27f else 19f
        contentDescription = "Repère de date : $value"
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hide)
        animate().cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
