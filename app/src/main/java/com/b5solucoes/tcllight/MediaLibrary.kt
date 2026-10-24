package com.b5solucoes.tcllight

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class MediaEntry(val id: String = UUID.randomUUID().toString(), val title: String,
    val kind: String = "movie", val packageName: String = "", val link: String = "", val cover: String = "") {
    val kindLabel get() = when(kind) { "series" -> "Série"; "channel" -> "Canal"; "link" -> "Link"; else -> "Filme" }
    fun json(): JSONObject = JSONObject().put("id",id).put("title",title).put("kind",kind)
        .put("package",packageName).put("link",link).put("cover",cover)
}

/** Local, bounded list. It never imports a streaming account or a playback history. */
internal object MediaLibrary {
    const val PREF = "media.library"
    const val MAX_ITEMS = 80
    const val MAX_BYTES = 64 * 1024
    val kinds = listOf("movie","series","channel","link")
    fun validText(value: String, max: Int) = value.length <= max && value.none { Character.isISOControl(it) }
    fun validPackage(value: String) = value.length <= 255 && value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
    fun parseEntry(obj: JSONObject): MediaEntry {
        fun field(key: String): String { val value=obj.get(key);require(value is String);return value }
        val result=MediaEntry(field("id"),field("title"),field("kind"),field("package"),field("link"),field("cover"))
        require(runCatching { UUID.fromString(result.id).toString() == result.id }.getOrDefault(false))
        require(result.title.isNotBlank() && validText(result.title,128) && result.kind in kinds)
        require(result.packageName.isEmpty() || validPackage(result.packageName))
        require(result.link.isEmpty() || MediaLinks.parse(result.link,result.packageName) != null)
        require(result.cover.isEmpty() || MediaLinks.coverUri(result.cover) != null)
        return result
    }
    fun parse(array: JSONArray): List<MediaEntry> {
        require(array.length() <= MAX_ITEMS && array.toString().toByteArray().size <= MAX_BYTES)
        val entries=(0 until array.length()).map { parseEntry(array.getJSONObject(it)) }
        require(entries.map { it.id }.distinct().size == entries.size)
        return entries
    }
    fun decode(raw: String?): List<MediaEntry> = if(raw == null) emptyList() else runCatching { parse(JSONArray(raw)) }.getOrDefault(emptyList())
    fun encode(entries: List<MediaEntry>): String = JSONArray().apply { entries.forEach { put(it.json()) } }.also { parse(it) }.toString()
    fun add(entries: List<MediaEntry>, entry: MediaEntry): List<MediaEntry> {
        parseEntry(entry.json())
        val duplicate=entries.firstOrNull { it.packageName == entry.packageName && it.link == entry.link && it.title.equals(entry.title,true) }
        if(duplicate != null) return entries
        require(entries.size < MAX_ITEMS) { "Sua lista já tem $MAX_ITEMS itens." }
        return (entries + entry).also { encode(it) }
    }
    fun move(entries: List<MediaEntry>, id: String, offset: Int): List<MediaEntry> = entries.toMutableList().apply {
        val index=indexOfFirst { it.id == id }
        if(index >= 0 && index+offset in indices) java.util.Collections.swap(this,index,index+offset)
    }
}

/** Provider links are confined to their owning application; grants, selectors and unsafe schemes are dropped. */
internal object MediaLinks {
    private val unsafe=setOf("file","javascript","data","content","intent","android-app")
    fun channel(uri: Uri): Boolean = uri.scheme == "content" && uri.authority == "android.media.tv" &&
        uri.pathSegments.size == 2 && uri.pathSegments[0] == "channel" && uri.pathSegments[1].toLongOrNull()?.let { it >= 0 } == true &&
        uri.query == null && uri.fragment == null
    fun coverUri(value: String): Uri? = runCatching {
        require(MediaLibrary.validText(value,2048))
        Uri.parse(value).also { uri ->
            require((uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) ||
                (uri.scheme == "content" && !uri.authority.isNullOrBlank() && uri.authority != UpdatePolicy.PACKAGE+".updates") ||
                (uri.scheme == "android.resource" && !uri.authority.isNullOrBlank()))
        }
    }.getOrNull()
    fun parse(value: String, owner: String): Intent? = runCatching {
        require(value.isNotBlank() && MediaLibrary.validText(value,2048))
        require(owner.isEmpty() || MediaLibrary.validPackage(owner))
        val source=Intent.parseUri(value,Intent.URI_INTENT_SCHEME)
        require(source.action == null || source.action == Intent.ACTION_VIEW)
        require(source.selector == null && (source.`package` == null || source.`package` == owner))
        require(source.component == null || source.component!!.packageName == owner)
        val data=source.data
        if(data != null) {
            val scheme=data.scheme?.lowercase(java.util.Locale.ROOT)
            require(scheme != null)
            if(scheme == "content") require(channel(data) && owner.isEmpty())
            else {
                require(scheme !in unsafe && (scheme == "https" || owner.isNotEmpty()))
                require(data.userInfo == null)
                if(scheme == "https") require(!data.host.isNullOrBlank())
            }
        }
        require(data != null || (owner.isNotEmpty() && source.component != null))
        Intent(Intent.ACTION_VIEW,data).apply {
            if(owner.isNotEmpty()) setPackage(owner)
            component=source.component
            // Intent URI extras are primitives. Keep bounded text/numbers for content IDs, never URI permissions.
            source.extras?.keySet()?.take(16)?.forEach { key ->
                if(key.length <= 80 && !key.startsWith("android.") && key != "browser_fallback_url") when(val item=source.extras?.get(key)) {
                    is String -> if(MediaLibrary.validText(item,512)) putExtra(key,item)
                    is Int -> putExtra(key,item)
                    is Long -> putExtra(key,item)
                    is Boolean -> putExtra(key,item)
                }
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }.getOrNull()

    fun search(context: Context, owner: String, query: String): Intent? {
        if(!MediaLibrary.validPackage(owner) || query.isBlank() || !MediaLibrary.validText(query,128)) return null
        val request=Intent(Intent.ACTION_SEARCH).setPackage(owner).putExtra(SearchManager.QUERY,query)
        val info=context.packageManager.resolveActivity(request,0)?.activityInfo ?: return null
        return request.takeIf { info.exported && info.enabled && info.packageName == owner }
    }
}
