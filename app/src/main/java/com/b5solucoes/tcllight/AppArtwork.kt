package com.b5solucoes.tcllight

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal data class Artwork(val bitmap: Bitmap,val banner: Boolean)

/** Small shared resident images. Visible cards are pinned; only actual pressure evicts offscreen art. */
internal class AppArtwork(context: Context,private val directory: File=File(context.cacheDir,"app-thumbnails-v1"),
    private val evicted: (ComponentName,Bitmap)->Unit={_,_->},private val source: ((ComponentName)->Artwork?)?=null) {
    private data class Source(val revision: String,val info: ActivityInfo?,val key:String)
    private data class Resident(val component: ComponentName,val art: Artwork)
    private val manager=context.applicationContext.packageManager
    private val identity=context.resources.configuration.let {
        @Suppress("DEPRECATION")
        val language=if(android.os.Build.VERSION.SDK_INT>=24) it.locales.toLanguageTags() else it.locale.toString()
        "$language|${it.densityDpi}|${it.uiMode and 48}"
    }
    private val sources=ConcurrentHashMap<ComponentName,Source>()
    private val worker=ArtworkQueue("tv-artwork")
    private val main=Handler(Looper.getMainLooper())
    private val waiting=HashMap<String,MutableList<(Artwork?)->Unit>>()
    private val memoryBytes=if((context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice) 4*1024*1024 else 6*1024*1024
    private val cache=LinkedHashMap<String,Resident>(32,.75f,true)
    private var bytes=0
    private var visible=emptySet<ComponentName>()
    private val disk=ThumbnailStore(directory,8L*1024*1024)
    private var closed=false
    private var generation=0
    fun register(component: ComponentName,revision: String,info: ActivityInfo?=null) {
        sources[component]=Source(revision,info,component.flattenToString()+"|"+revision+"|"+identity)
    }
    fun revision(component: ComponentName)=sources[component]?.key ?: (component.flattenToString()+"||"+identity)
    fun peek(component: ComponentName): Artwork? = cache[revision(component)]?.art
    fun contains(bitmap: Bitmap)=cache.values.any { it.art.bitmap===bitmap }
    fun setVisible(components: Set<ComponentName>) {
        visible=components
        components.forEach { cache[revision(it)] } // Merely seeing an already-bound image is a real cache access.
        prune(memoryBytes)
    }
    private fun prune(limit: Int) {
        val removed=ArrayList<Resident>()
        val iterator=cache.entries.iterator()
        while(bytes>limit && iterator.hasNext()) {
            val entry=iterator.next()
            if(entry.value.component in visible && entry.key==revision(entry.value.component)) continue
            bytes-=entry.value.art.bitmap.allocationByteCount;removed.add(entry.value);iterator.remove()
        }
        removed.forEach { evicted(it.component,it.art.bitmap) }
    }
    private fun remember(key: String,component: ComponentName,art: Artwork) {
        cache.put(key,Resident(component,art))?.let { bytes-=it.art.bitmap.allocationByteCount }
        bytes+=art.bitmap.allocationByteCount;prune(memoryBytes)
    }
    fun load(component: ComponentName,visible: Boolean=true,result:(Artwork?)->Unit) {
        if(closed) return
        val cacheKey=revision(component)
        cache[cacheKey]?.let { result(it.art);return }
        waiting[cacheKey]?.let { if(visible) it.add(result);if(visible) worker.promote(cacheKey);return }
        waiting[cacheKey]=mutableListOf(result)
        val request=generation
        worker.execute(cacheKey,visible) {
            val art=try {
                cached(cacheKey) ?: (source?.invoke(component) ?: if(source==null) decode(component) else null)?.also {
                    disk.write(cacheKey,StoredThumbnail(it.bitmap,it.banner))
                }
            } catch(_:Exception) { null } catch(_:OutOfMemoryError) { null }
            main.post {
                if(!closed && generation==request) {
                    if(art!=null && cacheKey==revision(component)) remember(cacheKey,component,art)
                    waiting.remove(cacheKey)?.forEach { it(art.takeIf { cacheKey==revision(component) }) }
                }
            }
        }
    }
    private fun cached(key:String): Artwork? {
        val saved=disk.read(key) ?: return null
        if(!saved.banner || saved.bitmap.width<=256 && saved.bitmap.height<=144) return Artwork(saved.bitmap,saved.banner)
        val ratio=minOf(256f/saved.bitmap.width,144f/saved.bitmap.height)
        val bitmap=Bitmap.createScaledBitmap(saved.bitmap,maxOf(1,(saved.bitmap.width*ratio).toInt()),maxOf(1,(saved.bitmap.height*ratio).toInt()),true)
        disk.write(key,StoredThumbnail(bitmap,true))
        return Artwork(bitmap,true)
    }
    private fun decode(component: ComponentName): Artwork? {
        @Suppress("DEPRECATION")
        val info=sources[component]?.info ?: manager.getActivityInfo(component,0)
        val wide=info.loadBanner(manager)?.takeIf { it.intrinsicWidth>0 && it.intrinsicHeight>0 && it.intrinsicWidth.toFloat()/it.intrinsicHeight>=1.45f }
        val drawable=wide ?: info.loadIcon(manager)
        val w=if(wide!=null) 256 else 128;val h=if(wide!=null) 144 else 128
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val ratio=if(drawable.intrinsicWidth>0 && drawable.intrinsicHeight>0) minOf(w.toFloat()/drawable.intrinsicWidth,h.toFloat()/drawable.intrinsicHeight) else 1f
        val dw=if(drawable.intrinsicWidth>0) (drawable.intrinsicWidth*ratio).toInt() else w
        val dh=if(drawable.intrinsicHeight>0) (drawable.intrinsicHeight*ratio).toInt() else h
        drawable.setBounds((w-dw)/2,(h-dh)/2,(w+dw)/2,(h+dh)/2);drawable.draw(Canvas(bitmap))
        return Artwork(bitmap,wide!=null)
    }
    fun invalidate() {
        generation++
        val callbacks=waiting.values.flatten();waiting.clear();callbacks.forEach { it(null) }
        val removed=cache.values.toList();cache.clear();bytes=0
        removed.forEach { evicted(it.component,it.art.bitmap) }
    }
    fun trim(severe: Boolean=true) { prune(if(severe) 0 else memoryBytes/2) }
    fun close() { closed=true;visible=emptySet();invalidate();worker.close();main.removeCallbacksAndMessages(null) }
}
