package com.b5solucoes.tcllight

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** Cinco fotografias WebP embaladas no APK (1280 x 720, offline).
 * O slideshow não usa rede, vídeos ou serviços. Atualiza apenas quando visível.
 */
class WallpaperDeck(context: Context, private val prefs: SharedPreferences) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val imageA = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val imageB = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f
    }
    private val veil = View(context)
    private val photos = intArrayOf(
        R.drawable.wallpaper_01, R.drawable.wallpaper_02,
        R.drawable.wallpaper_03, R.drawable.wallpaper_04, R.drawable.wallpaper_05
    )
    private var index = 0
    private var foreground = false
    private val advance = object : Runnable {
        override fun run() {
            if (!foreground || prefs.getBoolean("eco_mode", false) ||
                prefs.getInt("wallpaper_mode", -1) != -1) return
            val next = (index + 1) % photos.size
            imageB.setImageResource(photos[next])
            imageB.alpha = 0f
            imageB.animate().alpha(1f).setDuration(900L)
                .withEndAction {
                    imageA.setImageResource(photos[next])
                    imageB.alpha = 0f
                    imageB.setImageDrawable(null)
                    index = next
                }.start()
            handler.postDelayed(this, 120000L)
        }
    }

    init {
        addView(imageA, LayoutParams(-1,-1))
        addView(imageB, LayoutParams(-1,-1))
        addView(veil, LayoutParams(-1,-1))
        reload()
    }

    fun reload() {
        handler.removeCallbacks(advance)
        imageB.animate().cancel()
        imageB.alpha = 0f
        imageB.setImageDrawable(null)
        val mode = prefs.getInt("wallpaper_mode", -1)
        index = if (mode in 0..4) mode else 0
        if (mode == 5) {
            imageA.setImageDrawable(null)
            imageA.setBackgroundColor(Color.rgb(13, 22, 33))
        } else {
            imageA.setBackgroundColor(Color.TRANSPARENT)
            imageA.setImageResource(photos[index])
        }
        val darkness = (prefs.getInt("wallpaper_dim", 50).coerceIn(20, 80) * 255 / 100)
        veil.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb((darkness * .45f).toInt(), 3, 9, 17),
                Color.argb(darkness, 3, 9, 17),
                Color.argb((darkness * 1.25f).toInt().coerceAtMost(250), 2, 6, 12)))
        if (foreground) onForeground()
    }

    fun onForeground() {
        foreground = true
        handler.removeCallbacks(advance)
        if (prefs.getInt("wallpaper_mode", -1) == -1 &&
            !prefs.getBoolean("eco_mode", false)) {
            handler.postDelayed(advance, 120000L)
        }
    }

    fun onBackground() {
        foreground = false
        handler.removeCallbacks(advance)
        imageB.animate().cancel()
        imageB.alpha = 0f
        imageB.setImageDrawable(null)
    }

    override fun onDetachedFromWindow() {
        onBackground()
        super.onDetachedFromWindow()
    }
}
