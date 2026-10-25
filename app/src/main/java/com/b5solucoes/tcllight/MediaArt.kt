package com.b5solucoes.tcllight

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Handler
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.io.File
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** Home, My List and search share requests and a single bounded cover cache. */
internal class MediaArtwork(context: Context,private val handler: Handler,
    private val directory: File=File(context.cacheDir,"media-thumbnails-v1"),
    private val source: ((String)->Bitmap?)?=null,private val now:()->Long=System::currentTimeMillis) {
    private data class Cached(val bitmap: Bitmap,val at: Long)
    private val app=context.applicationContext
    private val worker=ArtworkQueue("tv-media-art")
    private val diskWorker=Executors.newSingleThreadExecutor { r->Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run() },"tv-media-thumbnails").apply { isDaemon=true } }
    private val memoryBytes=if((context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice) 2*1024*1024 else 3*1024*1024
    private val cache=object:LruCache<String,Cached>(memoryBytes) { override fun sizeOf(key: String,value: Cached)=value.bitmap.allocationByteCount }
    private val disk=ThumbnailStore(directory,16L*1024*1024)
    private val waiting=HashMap<String,MutableList<(Bitmap?)->Unit>>()
    private val failures=LinkedHashMap<String,Long>()
    @Volatile private var closed=false
    fun peek(uri: String): Bitmap? = cache.get(uri)?.takeIf { now()-it.at in 0..30*60*1000L }?.bitmap
    fun load(uri: String,visible: Boolean=true,result:(Bitmap?)->Unit={}) {
        if(closed) return
        peek(uri)?.let { result(it);return }
        waiting[uri]?.let { it.add(result);if(visible) worker.promote(uri);return }
        if(!visible && waiting.size>=12 || waiting.size>=64 || failures[uri]?.let { now()-it<30000L }==true) { result(null);return }
        waiting[uri]=mutableListOf(result)
        diskWorker.execute {
            val age=if(uri.startsWith("content:")) 60*60*1000L else 7L*24*60*60*1000
            val saved=disk.read(uri,age)?.bitmap
            if(saved!=null) deliver(uri,saved)
            else if(!closed) runCatching { worker.execute(uri,visible) {
                val bitmap=try { source?.invoke(uri) ?: if(source==null) read(uri) else null }
                    catch(_:Exception) { null } catch(_:OutOfMemoryError) { null }
                if(bitmap==null) deliver(uri,null)
                else if(!closed) runCatching { diskWorker.execute {
                    disk.write(uri,StoredThumbnail(bitmap),opaque=true);deliver(uri,bitmap)
                } }
            } }
        }
    }
    private fun deliver(uri: String,bitmap: Bitmap?) {
        handler.post {
            if(!closed) {
                if(bitmap!=null) { cache.put(uri,Cached(bitmap,now()));failures.remove(uri) }
                else { failures[uri]=now();if(failures.size>64) failures.remove(failures.keys.first()) }
                waiting.remove(uri)?.forEach { it(bitmap) }
            }
        }
    }
    fun promote(uri: String) { worker.promote(uri) }
    fun trim(severe: Boolean=true) { if(severe) cache.evictAll() else cache.trimToSize(memoryBytes/2) }
    fun close() { closed=true;waiting.clear();cache.evictAll();worker.close();diskWorker.shutdownNow() }
    private fun read(raw: String): Bitmap? {
        val uri=MediaLinks.coverUri(raw) ?: return null
        var connection: HttpsURLConnection?=null
        val input=if(uri.scheme == "https") {
            var url=URL(raw);var resolved: HttpsURLConnection?=null
            for(step in 0..3) {
                require(url.protocol == "https" && url.userInfo == null && url.host.isNotBlank() && !url.host.equals("localhost",true))
                val current=url.openConnection() as HttpsURLConnection
                current.connectTimeout=3000;current.readTimeout=3000;current.instanceFollowRedirects=false
                current.setRequestProperty("Accept","image/*")
                try {
                    val status=current.responseCode
                    if(status in 300..399) { val next=current.getHeaderField("Location");current.disconnect();if(next==null) return null;url=URL(url,next) }
                    else if(status == 200) { resolved=current;break }
                    else { current.disconnect();return null }
                } catch(error:Exception) { current.disconnect();throw error }
            }
            connection=resolved ?: return null
            if((connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L) > 1024*1024) { connection.disconnect();return null }
            try { connection.inputStream } catch(error:Exception) { connection.disconnect();throw error }
        } else app.contentResolver.openInputStream(uri) ?: return null
        val bytes=try {
            input.use { stream ->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) { val n=stream.read(buffer);if(n<0) break;require(output.size()+n <= 1024*1024);output.write(buffer,0,n) }
                output.toByteArray()
            }
        } finally { connection?.disconnect() }
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        if(options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 16000 || options.outHeight > 16000) return null
        options.inSampleSize=1
        while(options.outWidth/options.inSampleSize > 560 || options.outHeight/options.inSampleSize > 320) options.inSampleSize*=2
        options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.RGB_565
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
    }}

/** Bindings stay on the UI thread. Cached visible images bind immediately; only nearby cards prefetch. */
internal class MediaArt(context: Context,private val handler: Handler,shared: MediaArtwork?=null) {
    private val repository=shared ?: MediaArtwork(context,handler)
    private val ownsRepository=shared==null
    private val bindings=ArrayList<Binding>()
    private class Binding(val image: ImageView,val placeholder: View,val uri: String,
        var loading: Boolean=false,var loaded: Boolean=false,var attemptedAt: Long=0)
    private val rect=Rect()
    private val location=IntArray(2)
    private var foreground=false
    private var closed=false
    fun clearBindings() { bindings.clear() }
    fun bind(image: ImageView,placeholder: View,uri: String) {
        if(uri.isNotEmpty()) {
            val binding=Binding(image,placeholder,uri);bindings.add(binding)
            repository.peek(uri)?.let { display(binding,it) }
        }
    }
    fun start() { foreground=true;bindings.forEach { it.attemptedAt=0 };refreshVisible() }
    fun stop() { foreground=false }
    fun trim(severe: Boolean=true) {
        if(ownsRepository) repository.trim(severe)
        bindings.forEach {
            if(severe || !foreground || !it.image.getGlobalVisibleRect(rect)) clear(it)
            it.attemptedAt=0
        }
    }
    fun close() { closed=true;stop();clearBindings();if(ownsRepository) repository.close() }
    private fun clear(binding: Binding) {
        if(binding.loaded) { binding.image.setImageDrawable(null);binding.placeholder.visibility=View.VISIBLE;binding.loaded=false }
    }
    private fun display(binding: Binding,bitmap: Bitmap) {
        binding.image.setImageBitmap(bitmap);binding.placeholder.visibility=View.GONE;binding.loaded=true
    }
    fun refreshVisible() {
        if(!foreground || closed) return
        val nearby=ArrayList<Binding>()
        for(binding in bindings) {
            if(!binding.image.isShown) { clear(binding);continue }
            val visible=binding.image.getGlobalVisibleRect(rect)
            if(!visible) {
                clear(binding)
                binding.image.getLocationOnScreen(location)
                val display=binding.image.resources.displayMetrics
                if(location[0]+binding.image.width>=-binding.image.width && location[0]<=display.widthPixels+binding.image.width &&
                    location[1]+binding.image.height>=0 && location[1]<=display.heightPixels+binding.image.height) nearby.add(binding)
                continue
            }
            if(binding.loaded) continue
            repository.peek(binding.uri)?.let { display(binding,it) } ?: request(binding)
        }
        // All visible requests enter first. At most two adjacent covers are fetched speculatively.
        nearby.take(2).forEach { repository.load(it.uri,false) }
    }
    private fun request(binding: Binding) {
        if(binding.loading) { repository.promote(binding.uri);return }
        if(System.currentTimeMillis()-binding.attemptedAt<30000L) return
        binding.loading=true;binding.attemptedAt=System.currentTimeMillis()
        repository.load(binding.uri) { bitmap ->
            binding.loading=false
            if(!closed && foreground && binding in bindings && binding.image.isAttachedToWindow &&
                binding.image.getGlobalVisibleRect(rect) && bitmap!=null) display(binding,bitmap)
        }
    }
}
