package com.b5solucoes.tcllight

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal data class Artwork(val bitmap: Bitmap,val banner: Boolean)

/** Shared by both app rows: RAM first, pre-sized disk thumbnail second, package resources last. */
internal class AppArtwork(context: Context,private val directory: File=File(context.cacheDir,"app-thumbnails-v1"),
    private val source: ((ComponentName)->Artwork?)?=null) {
    private data class Source(val revision: String,val info: ActivityInfo?)
    private val manager=context.applicationContext.packageManager
    private val identity=context.resources.configuration.let {
        @Suppress("DEPRECATION")
        val language=if(android.os.Build.VERSION.SDK_INT>=24) it.locales.toLanguageTags() else it.locale.toString()
        "$language|${it.densityDpi}|${it.uiMode and 48}"
    }
    private val sources=ConcurrentHashMap<String,Source>()
    private val worker=ArtworkQueue("tv-artwork")
    private val main=Handler(Looper.getMainLooper())
    private val waiting=HashMap<String,MutableList<(Artwork?)->Unit>>()
    private val memoryBytes=if((context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice) 2*1024*1024 else 4*1024*1024
    private val cache=object:LruCache<String,Artwork>(memoryBytes) { override fun sizeOf(key: String,value: Artwork)=value.bitmap.allocationByteCount }
    private val disk=ThumbnailStore(directory,8L*1024*1024)
    private var closed=false
    private var generation=0
    fun register(component: ComponentName,revision: String,info: ActivityInfo?=null) { sources[component.flattenToString()]=Source(revision,info) }
    private fun key(component: ComponentName)=component.flattenToString()+"|"+sources[component.flattenToString()]?.revision.orEmpty()+"|"+identity
    fun load(component: ComponentName,visible: Boolean=true,result:(Artwork?)->Unit) {
        if(closed) return
        val cacheKey=key(component)
        cache.get(cacheKey)?.let { result(it);return }
        waiting[cacheKey]?.let { it.add(result);if(visible) worker.promote(cacheKey);return }
        waiting[cacheKey]=mutableListOf(result)
        val request=generation
        worker.execute(cacheKey,visible) {
            val art=try {
                disk.read(cacheKey)?.let { Artwork(it.bitmap,it.banner) } ?: (source?.invoke(component) ?: if(source==null) decode(component) else null)?.also {
                    disk.write(cacheKey,StoredThumbnail(it.bitmap,it.banner))
                }
            } catch(_:Exception) { null } catch(_:OutOfMemoryError) { null }
            main.post {
                if(!closed && generation==request) {
                    if(art!=null && cacheKey==key(component)) cache.put(cacheKey,art)
                    waiting.remove(cacheKey)?.forEach { it(art.takeIf { cacheKey==key(component) }) }
                }
            }
        }
    }
    private fun decode(component: ComponentName): Artwork? {
        @Suppress("DEPRECATION")
        val info=sources[component.flattenToString()]?.info ?: manager.getActivityInfo(component,0)
        val wide=info.loadBanner(manager)?.takeIf { it.intrinsicWidth>0 && it.intrinsicHeight>0 && it.intrinsicWidth.toFloat()/it.intrinsicHeight>=1.45f }
        val drawable=wide ?: info.loadIcon(manager)
        val w=if(wide!=null) 320 else 128;val h=if(wide!=null) 180 else 128
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val ratio=if(drawable.intrinsicWidth>0 && drawable.intrinsicHeight>0) minOf(w.toFloat()/drawable.intrinsicWidth,h.toFloat()/drawable.intrinsicHeight) else 1f
        val dw=if(drawable.intrinsicWidth>0) (drawable.intrinsicWidth*ratio).toInt() else w
        val dh=if(drawable.intrinsicHeight>0) (drawable.intrinsicHeight*ratio).toInt() else h
        drawable.setBounds((w-dw)/2,(h-dh)/2,(w+dw)/2,(h+dh)/2);drawable.draw(Canvas(bitmap))
        return Artwork(bitmap,wide!=null)
    }
    fun invalidate() { generation++;waiting.values.flatten().forEach { it(null) };waiting.clear();cache.evictAll() }
    fun trim(severe: Boolean=true) { if(severe) cache.evictAll() else cache.trimToSize(memoryBytes/2) }
    fun close() { closed=true;invalidate();worker.close();main.removeCallbacksAndMessages(null) }
}
