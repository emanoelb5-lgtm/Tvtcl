package com.b5solucoes.tcllight

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

internal data class Artwork(val bitmap: Bitmap, val banner: Boolean)

/** Bounded thumbnails, decoded away from input dispatch; shared by both app rows. */
internal class AppArtwork(context: Context) {
    private val manager = context.applicationContext.packageManager
    private val worker = Executors.newSingleThreadExecutor { r -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() },"tv-artwork") }
    private val main = Handler(Looper.getMainLooper())
    private val waiting = HashMap<String,MutableList<(Artwork?) -> Unit>>()
    private val cache = object : LruCache<String,Artwork>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Artwork) = value.bitmap.allocationByteCount
    }
    private var closed = false
    private var generation = 0

    fun load(component: ComponentName, result: (Artwork?) -> Unit) {
        if(closed) return
        val key = component.flattenToString()
        cache.get(key)?.let { result(it); return }
        waiting[key]?.let { it.add(result); return }
        waiting[key] = mutableListOf(result)
        val request = generation
        worker.execute {
            val art = try {
                @Suppress("DEPRECATION")
                val info = manager.getActivityInfo(component,0)
                val wide = info.loadBanner(manager)?.takeIf {
                    it.intrinsicWidth > 0 && it.intrinsicHeight > 0 &&
                        it.intrinsicWidth.toFloat() / it.intrinsicHeight >= 1.45f
                }
                val drawable = wide ?: info.loadIcon(manager)
                val w = if(wide != null) 320 else 128
                val h = if(wide != null) 180 else 128
                val bitmap = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                val ratio = if(drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
                    minOf(w.toFloat()/drawable.intrinsicWidth,h.toFloat()/drawable.intrinsicHeight) else 1f
                val dw = if(drawable.intrinsicWidth > 0) (drawable.intrinsicWidth*ratio).toInt() else w
                val dh = if(drawable.intrinsicHeight > 0) (drawable.intrinsicHeight*ratio).toInt() else h
                drawable.setBounds((w-dw)/2,(h-dh)/2,(w+dw)/2,(h+dh)/2)
                drawable.draw(Canvas(bitmap))
                Artwork(bitmap,wide != null)
            } catch(_: Exception) { null } catch(_: OutOfMemoryError) { null }
            main.post {
                if(!closed && generation == request) {
                    if(art != null) cache.put(key,art)
                    waiting.remove(key)?.forEach { it(art) }
                }
            }
        }
    }

    fun invalidate() { generation++; waiting.clear(); cache.evictAll() }
    fun trim() { cache.evictAll() }
    fun close() { closed = true; invalidate(); worker.shutdownNow(); main.removeCallbacksAndMessages(null) }
}
