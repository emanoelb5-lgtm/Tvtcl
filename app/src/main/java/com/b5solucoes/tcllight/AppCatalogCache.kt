package com.b5solucoes.tcllight

import android.content.ComponentName
import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class CachedTvApp(val name: String,val component: ComponentName,val revision: String)

/** A small startup snapshot, always reconciled with PackageManager in the background. */
internal class AppCatalogCache(context: Context,private val file: File=File(context.cacheDir,"app-catalog-v1.json")) {
    private val configuration=context.resources.configuration
    private val identity=configuration.let {
        @Suppress("DEPRECATION")
        val language=if(android.os.Build.VERSION.SDK_INT>=24) it.locales.toLanguageTags() else it.locale.toString()
        "$language|${it.densityDpi}|${it.uiMode and 48}|${android.os.Build.FINGERPRINT}"
    }
    fun read(): List<CachedTvApp> = try {
        if(!file.isFile || file.length() !in 1..262144) emptyList()
        else {
            val data=JSONObject(file.readText())
            require(data.getString("configuration")==identity)
            val entries=data.getJSONArray("apps");require(entries.length()<=256)
            val seen=HashSet<String>()
            (0 until entries.length()).map { index ->
                val entry=entries.getJSONObject(index)
                val component=ComponentName.unflattenFromString(entry.getString("component")) ?: error("component")
                val name=entry.getString("name");val revision=entry.getString("revision")
                require(name.isNotBlank() && name.length<=200 && revision.length<=100)
                require(component.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
                require(component.className.length<=300 && seen.add(component.packageName))
                CachedTvApp(name,component,revision)
            }
        }
    } catch(_:Exception) { emptyList() }
    fun write(entries: List<CachedTvApp>) {
        if(entries.size>256) return
        runCatching {
            val array=JSONArray()
            entries.forEach { array.put(JSONObject().put("name",it.name.take(200)).put("component",it.component.flattenToString()).put("revision",it.revision)) }
            val bytes=JSONObject().put("configuration",identity).put("apps",array).toString().toByteArray(Charsets.UTF_8)
            if(bytes.size>262144) return
            val atomic=AtomicFile(file);val output=atomic.startWrite()
            try { output.write(bytes);atomic.finishWrite(output) } catch(error:Exception) { atomic.failWrite(output);throw error }
        }
    }
}
