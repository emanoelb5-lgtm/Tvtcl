package com.b5solucoes.tcllight

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

internal data class StoredThumbnail(val bitmap: Bitmap,val banner: Boolean=false)

/** All operations run on artwork workers. Hashed private cache files; no original full-size images. */
internal class ThumbnailStore(private val directory: File,private val maxBytes: Long,
    private val maxEntries: Int=128,private val now:()->Long=System::currentTimeMillis) {
    private fun file(key: String): File {
        val digest=MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val hex="0123456789abcdef"
        val hash=CharArray(digest.size*2)
        digest.forEachIndexed { index,byte->val n=byte.toInt() and 255;hash[index*2]=hex[n ushr 4];hash[index*2+1]=hex[n and 15] }
        return File(directory,String(hash)+".thumb")
    }
    @Synchronized fun read(key: String,maxAge: Long=30L*24*60*60*1000): StoredThumbnail? {
        val target=file(key)
        return try {
            if(!target.isFile || target.length() !in 18..MAX_FILE.toLong()) return null
            val bytes=DataInputStream(target.inputStream().buffered()).use { input ->
                require(input.readInt()==MAGIC)
                val created=input.readLong();require(now()-created in 0..maxAge)
                val flags=input.readUnsignedByte()
                val length=input.readInt();require(length in 1..MAX_FILE && length+17L==target.length())
                val data=ByteArray(length);input.readFully(data)
                flags to data
            }
            val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeByteArray(bytes.second,0,bytes.second.size,options)
            require(options.outWidth in 1..640 && options.outHeight in 1..360)
            options.inJustDecodeBounds=false
            options.inPreferredConfig=if(bytes.first and 2 != 0) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            val bitmap=BitmapFactory.decodeByteArray(bytes.second,0,bytes.second.size,options) ?: return null
            target.setLastModified(now())
            StoredThumbnail(bitmap,bytes.first and 1 != 0)
        } catch(_:Exception) { target.delete();null } catch(_:OutOfMemoryError) { null }
    }
    @Synchronized fun write(key: String,value: StoredThumbnail,opaque: Boolean=false) {
        if(value.bitmap.width>640 || value.bitmap.height>360 || value.bitmap.isRecycled) return
        runCatching {
            val encoded=ByteArrayOutputStream()
            if(!value.bitmap.compress(if(opaque) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,86,encoded)) return
            if(encoded.size()+17>MAX_FILE || encoded.size()+17>maxBytes) return
            if(!directory.isDirectory && !directory.mkdirs()) return
            val atomic=AtomicFile(file(key));val output=atomic.startWrite()
            try {
                val data=DataOutputStream(output)
                data.writeInt(MAGIC);data.writeLong(now());data.writeByte((if(value.banner)1 else 0) or (if(opaque)2 else 0))
                data.writeInt(encoded.size());encoded.writeTo(data);data.flush();atomic.finishWrite(output)
            } catch(error:Exception) { atomic.failWrite(output);throw error }
            prune()
        }
    }
    private fun prune() {
        val files=directory.listFiles()?.filter { it.name.endsWith(".thumb") }?.sortedByDescending { it.lastModified() }.orEmpty()
        var bytes=0L;var count=0
        files.forEach { entry ->
            val length=entry.length()
            if(count>=maxEntries || bytes+length>maxBytes) entry.delete()
            else { bytes+=length;count++ }
        }
    }
    companion object {
        private const val MAGIC=0x54434c31
        private const val MAX_FILE=1024*1024
    }
}
