package com.b5solucoes.tcllight

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.TextView
import android.widget.ImageView
import android.view.Gravity
import android.view.animation.DecelerateInterpolator

internal data class LauncherPalette(val name: String, val detail: String, val accent: Int, val base: Int, val panel: Int)

internal object LauncherPalettes {
    const val PREF = "appearance_theme"
    val values = listOf(
        LauncherPalette("Aurora","Verde suave e grafite",Color.rgb(154,235,211),Color.rgb(8,14,22),Color.rgb(16,25,36)),
        LauncherPalette("Safira","Azul sereno e noite",Color.rgb(159,199,255),Color.rgb(9,13,25),Color.rgb(18,24,41)),
        LauncherPalette("Âmbar","Champanhe e carvão",Color.rgb(242,209,153),Color.rgb(19,15,13),Color.rgb(32,26,23)))
    fun get(index: Int)=values[index.coerceIn(0,values.lastIndex)]
}

/** Native TV surfaces. Focus changes reuse drawables and only animate transforms. */
internal class TvStyle(val context: Context, val economy: () -> Boolean, private val fontScale: () -> Float = { 1f },
    private val insets: () -> Pair<Int,Int> = { 42 to 8 }, private val appearance: () -> Int = { 0 }) {
    val horizontalInset: Int get() = insets().first.coerceIn(24,72)
    val verticalInset: Int get() = insets().second.coerceIn(0,24)
    val textScale: Float get() = fontScale().coerceIn(1f, 1.2f)
    fun height(value: Int) = dp(kotlin.math.ceil(value * textScale).toInt())
    val ink = Color.rgb(245,247,251)
    val muted = Color.rgb(181,191,205)
    val quiet = Color.rgb(143,158,178)
    val palette get()=LauncherPalettes.get(appearance())
    val accent get()=palette.accent
    val base get()=palette.base
    val panelColor get()=palette.panel
    private val motion=DecelerateInterpolator(1.15f)
    fun mix(first: Int,second: Int,amount: Float): Int = Color.rgb(
        (Color.red(first)+(Color.red(second)-Color.red(first))*amount).toInt(),
        (Color.green(first)+(Color.green(second)-Color.green(first))*amount).toInt(),
        (Color.blue(first)+(Color.blue(second)-Color.blue(first))*amount).toInt())
    fun alpha(color: Int,value: Int)=Color.argb(value,Color.red(color),Color.green(color),Color.blue(color))
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()

    fun text(value: String, size: Float, color: Int = ink, medium: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size * textScale
            setTextColor(color)
            includeFontPadding = false
            typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", 0)
            if(medium) letterSpacing=-.01f
        }

    fun gradient(first: Int,second: Int,radius: Int=16,border: Int=Color.TRANSPARENT)=GradientDrawable(
        GradientDrawable.Orientation.TL_BR,intArrayOf(first,second)).apply {
        cornerRadius=dp(radius).toFloat();setStroke(dp(1),border)
    }

    fun cardSurface(radius: Int=12)=gradient(mix(panelColor,accent,.08f),mix(base,panelColor,.35f),radius,Color.argb(36,222,235,250))

    fun iconBadge(kind: TvIcon.Kind,size: Int=36,tint: Int=accent,selected: Boolean=false)=ImageView(context).apply {
        setImageDrawable(TvIcon(kind,if(selected) base else tint))
        val padding=dp((size*.25f).toInt());setPadding(padding,padding,padding,padding)
        background=if(selected) surface(tint,size/3) else surface(mix(panelColor,tint,.11f),size/3,alpha(tint,38))
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun surface(color: Int, radius: Int = 14, border: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), border)
        }

    fun states(radius: Int = 14, transparent: Boolean = false) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed),
            gradient(mix(panelColor,accent,.25f),mix(panelColor,accent,.15f),radius).apply { setStroke(dp(2),mix(accent,Color.WHITE,.35f)) })
        addState(intArrayOf(android.R.attr.state_focused),
            gradient(mix(panelColor,accent,.13f),mix(panelColor,accent,.06f),radius).apply { setStroke(dp(2),mix(accent,Color.WHITE,.45f)) })
        addState(intArrayOf(android.R.attr.state_selected),surface(mix(panelColor,accent,.07f),radius,alpha(accent,58)))
        addState(intArrayOf(),if(transparent) surface(Color.TRANSPARENT,radius)
            else gradient(alpha(mix(panelColor,Color.WHITE,.015f),232),alpha(base,236),radius,Color.argb(31,230,239,250)))
    }

    fun focus(view: View, key: String, radius: Int = 14, transparent: Boolean = false,
              changed: (Boolean) -> Unit = {}) {
        view.id = View.generateViewId()
        view.tag = key
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.background = states(radius, transparent)
        val card=key.startsWith("top:") || key.startsWith("grid:") || key.startsWith("media-home:") && key.count { it==':' }>1
        view.setOnFocusChangeListener { v, focused ->
            v.animate().cancel()
            val scale = if (focused && !economy() && card) 1.025f else 1f
            if (economy()) { v.scaleX = scale; v.scaleY = scale }
            else v.animate().scaleX(scale).scaleY(scale).setInterpolator(motion).setDuration(110L).start()
            changed(focused)
        }
    }
}
