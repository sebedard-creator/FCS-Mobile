package com.fcsmobile.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import android.widget.EditText
import androidx.core.content.ContextCompat

/** Shared native styling; every screen reads the same resource palette. */
class AppStyle(private val context: Context) {
    val background = color(R.color.app_background)
    val surface = color(R.color.app_surface)
    val raised = color(R.color.app_surface_raised)
    val border = color(R.color.app_border)
    val ink = color(R.color.app_text)
    val muted = color(R.color.app_text_muted)
    val accent = color(R.color.app_accent)
    val accentContainer = color(R.color.app_accent_container)
    val onAccent = color(R.color.app_on_accent)
    val danger = color(R.color.app_danger)
    val mediaCaption = color(R.color.app_media_caption)

    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun rounded(fill: Int, radius: Int = 16, outline: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        outline?.let { setStroke(dp(1).coerceAtLeast(1), it) }
    }

    fun touchable(fill: Int, radius: Int = 12) = RippleDrawable(
        ColorStateList.valueOf(color(R.color.app_ripple)), rounded(fill, radius), rounded(-1, radius))

    fun button(view: Button, primary: Boolean = false, destructive: Boolean = false) = view.apply {
        val fill = if (primary) accent else raised
        val text = if (primary) onAccent else if (destructive) danger else ink
        backgroundTintList = null
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(surface, 12))
            addState(intArrayOf(), touchable(fill))
        }
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, text)))
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }

    fun field(view: EditText) = view.apply {
        backgroundTintList = null
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(raised, 12, accent))
            addState(intArrayOf(), rounded(this@AppStyle.background, 12, border))
        }
        setTextColor(ink)
        setHintTextColor(muted)
        textSize = 16f
        minHeight = dp(52)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }
}
