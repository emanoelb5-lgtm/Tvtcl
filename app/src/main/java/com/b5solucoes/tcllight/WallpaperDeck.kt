package com.b5solucoes.tcllight

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.concurrent.Executors

/** Offline 16:9 photographs. Only the two visible frames are kept for cross-fades. */
internal class WallpaperDeck(context: Context, private val prefs: SharedPreferences,
                              private val economy: () -> Boolean) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor { r -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() },"tv-wallpaper") }
    private var front = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private var back = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val pictures = FrameLayout(context)
    private val veil = View(context)
    private val sideVeil = View(context)
    private val photos = intArrayOf(R.drawable.wallpaper_01,R.drawable.wallpaper_02,
        R.drawable.wallpaper_03,R.drawable.wallpaper_04,R.drawable.wallpaper_05)
    private var index = 0
    private var loadedIndex = -1
    private var active = false
    private var closed = false
    private var generation = 0
    private var loading = false
    private val advance = Runnable {
        if(active && !economy() && prefs.getInt("wallpaper_mode",-1) == -1)
            decode((index+1)%photos.size,true)
    }

    init {
        setBackgroundColor(Color.rgb(9,15,24))
        pictures.addView(front,LayoutParams(-1,-1)); pictures.addView(back,LayoutParams(-1,-1))
        addView(pictures,LayoutParams(-1,-1))
        addView(veil,LayoutParams(-1,-1)); addView(sideVeil,LayoutParams(-1,-1))
        reload()
    }

    fun reload() {
        if(closed) return
        generation++
        loading = false
        main.removeCallbacks(advance)
        settle()
        val mode = prefs.getInt("wallpaper_mode",-1)
        val selected = if(mode in photos.indices) mode else index
        val dim = prefs.getInt("wallpaper_dim",50).coerceIn(20,80)/100f
        val base=LauncherPalettes.get(prefs.getInt(LauncherPalettes.PREF,0)).base
        setBackgroundColor(base)
        fun tint(alpha: Int)=Color.argb(alpha,Color.red(base),Color.green(base),Color.blue(base))
        veil.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(
            tint((255*(.30f+dim*.35f)).toInt()),
            tint((255*(.18f+dim*.58f)).toInt()),
            tint((255*(.83f+dim*.16f)).toInt())))
        sideVeil.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(
            tint(58),Color.TRANSPARENT,tint(12)))
        if(mode == 5) {
            front.setImageDrawable(null); back.setImageDrawable(null); loadedIndex = -1
        } else if(loadedIndex != selected) decode(selected,false)
        schedule()
    }

    private fun decode(next: Int,animate: Boolean) {
        if(loading || closed) return
        loading = true
        val request = generation
        decoder.execute {
            val bitmap = try {
                BitmapFactory.decodeResource(resources,photos[next],BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inScaled = false
                })
            } catch(_: Exception) { null } catch(_: OutOfMemoryError) { null }
            main.post {
                if(closed || request != generation) return@post
                loading = false
                if(bitmap == null || (animate && !active)) { schedule(); return@post }
                index = next; loadedIndex = next
                if(animate && active && !economy()) {
                    back.bringToFront()
                    back.setImageBitmap(bitmap); back.alpha = 0f
                    back.animate().alpha(1f).setDuration(650L).withEndAction {
                        val previous = front
                        front = back; back = previous
                        front.alpha = 1f; back.alpha = 0f
                        back.setImageDrawable(null)
                        schedule()
                    }.start()
                } else {
                    front.setImageBitmap(bitmap)
                    schedule()
                }
            }
        }
    }

    private fun schedule() {
        main.removeCallbacks(advance)
        if(active && !closed && !loading && !economy() && prefs.getInt("wallpaper_mode",-1) == -1)
            main.postDelayed(advance,120000L)
    }

    private fun settle() {
        front.animate().cancel(); back.animate().cancel()
        // If a transition was interrupted, keep the already decoded incoming frame.
        if(back.drawable != null) {
            val previous = front; front = back; back = previous
        }
        front.alpha = 1f; back.alpha = 0f; back.setImageDrawable(null)
    }

    fun onForeground() { active = true; schedule() }
    fun onBackground() { active = false; main.removeCallbacks(advance); settle() }
    fun close() {
        closed = true; generation++; onBackground()
        decoder.shutdownNow(); main.removeCallbacksAndMessages(null)
        front.setImageDrawable(null); back.setImageDrawable(null)
    }
    override fun onDetachedFromWindow() { onBackground(); super.onDetachedFromWindow() }
}
