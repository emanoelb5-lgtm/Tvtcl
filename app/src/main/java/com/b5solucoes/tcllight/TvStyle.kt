package com.b5solucoes.tcllight

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.TextView

/** Native TV surfaces. Focus changes reuse drawables and only animate transforms. */
internal class TvStyle(val context: Context, val economy: () -> Boolean) {
    val ink = Color.rgb(244, 247, 250)
    val muted = Color.rgb(166, 179, 195)
    val quiet = Color.rgb(117, 137, 158)
    val accent = Color.rgb(171, 239, 222)
    val base = Color.rgb(9, 15, 24)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()

    fun text(value: String, size: Float, color: Int = ink, medium: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", 0)
        }

    fun surface(color: Int, radius: Int = 14, border: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), border)
        }

    fun states(radius: Int = 14, transparent: Boolean = false) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed),
            surface(Color.rgb(49, 78, 81), radius, accent))
        addState(intArrayOf(android.R.attr.state_focused),
            surface(Color.rgb(34, 62, 65), radius, accent))
        addState(intArrayOf(), surface(if (transparent) Color.TRANSPARENT
            else Color.argb(204, 22, 32, 46), radius, Color.argb(24, 230, 241, 255)))
    }

    fun focus(view: View, key: String, radius: Int = 14, transparent: Boolean = false,
              changed: (Boolean) -> Unit = {}) {
        view.id = View.generateViewId()
        view.tag = key
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.background = states(radius, transparent)
        view.setOnFocusChangeListener { v, focused ->
            v.animate().cancel()
            val scale = if (focused && !economy()) 1.025f else 1f
            if (economy()) { v.scaleX = scale; v.scaleY = scale }
            else v.animate().scaleX(scale).scaleY(scale).setDuration(110L).start()
            changed(focused)
        }
    }
}
