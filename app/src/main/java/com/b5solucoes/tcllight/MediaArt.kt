package com.b5solucoes.tcllight

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** Cover art is optional: at most 1 MiB downloaded per image, sampled to TV-card size, 2 MiB RAM cache. */
internal class MediaArt(context: Context, private val handler: Handler) {
    private val app=context.applicationContext
    private val worker=Executors.newFixedThreadPool(2) { run -> Thread(run,"tv-media-art").apply { isDaemon=true } }
    private val cache=object:LruCache<String,Bitmap>(2*1024*1024) { override fun sizeOf(key: String,value: Bitmap)=value.byteCount }
    private val bindings=ArrayList<Binding>()
    private class Binding(val image: ImageView,val placeholder: View,val uri: String,var loading: Boolean=false,var attempted: Boolean=false)
    @Volatile private var foreground=false
    @Volatile private var closed=false
    fun clearBindings() { bindings.clear() }
    fun bind(image: ImageView,placeholder: View,uri: String) { if(uri.isNotEmpty()) bindings.add(Binding(image,placeholder,uri)) }
    fun start() { foreground=true;bindings.forEach { it.attempted=false };refreshVisible() }
    fun stop() { foreground=false;bindings.forEach { it.image.setImageDrawable(null);it.placeholder.visibility=View.VISIBLE } }
    fun trim() { cache.evictAll();bindings.forEach { it.image.setImageDrawable(null);it.placeholder.visibility=View.VISIBLE;it.attempted=true } }
    fun close() { closed=true;stop();bindings.clear();cache.evictAll();worker.shutdownNow() }
    fun refreshVisible() {
        for(binding in bindings) {
            val visible=foreground && binding.image.isShown && binding.image.getGlobalVisibleRect(android.graphics.Rect())
            if(!visible) { binding.image.setImageDrawable(null);binding.placeholder.visibility=View.VISIBLE;continue }
            val cached=cache.get(binding.uri)
            if(cached != null) { binding.image.setImageBitmap(cached);binding.placeholder.visibility=View.GONE;continue }
            if(binding.loading || binding.attempted || closed) continue
            binding.loading=true;binding.attempted=true
            worker.execute {
                val bitmap=runCatching { read(binding.uri) }.getOrNull()
                if(bitmap != null && foreground && !closed) cache.put(binding.uri,bitmap)
                handler.post {
                    binding.loading=false
                    if(!closed && foreground && binding.image.isAttachedToWindow && binding.image.getGlobalVisibleRect(android.graphics.Rect()) && bitmap != null) {
                        binding.image.setImageBitmap(bitmap);binding.placeholder.visibility=View.GONE
                    }
                }
            }
        }
    }
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
    }
}
