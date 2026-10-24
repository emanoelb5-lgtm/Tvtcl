package com.b5solucoes.tcllight

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

internal data class StreamOffer(val service: String,val type: String,val url: String,val price: String="") {
    val label get()=when(type) { "FLATRATE"->"Assinatura";"ADS"->"Grátis com anúncios";"FREE"->"Grátis";"RENT"->"Aluguel";"BUY"->"Compra";else->"Assinatura e compra" }+
        if(price.isBlank()) "" else " · $price"
}
internal data class StreamTitle(val id: String,val title: String,val year: Int,val kind: String,val cover: String,
    val page: String,val offers: List<StreamOffer>) {
    val detail get()=(if(kind=="series") "Série" else "Filme")+if(year>0) " · $year" else ""
    val services get()=offers.map { it.service }.distinct().joinToString(" · ").ifBlank { "Sem oferta informada no Brasil" }
}
internal data class CatalogResult(val query: String,val titles: List<StreamTitle> = emptyList(),val message: String="",val failed: Boolean=false)

/** Reads only the public Brazil search page, without accounts, partner tokens or JavaScript execution. */
internal object StreamingPages {
    const val MAX_BYTES=2*1024*1024
    fun searchUrl(query: String)="https://www.justwatch.com/br/busca?q="+URLEncoder.encode(query,"UTF-8")
    fun web(raw: String): String?=runCatching {
        require(raw.length<=2048 && MediaLibrary.validText(raw,2048))
        val u=Uri.parse(raw);val host=u.host.orEmpty().lowercase(Locale.ROOT)
        require(u.scheme=="https" && u.userInfo==null && (u.port==-1 || u.port==443))
        require(host.contains('.') && host.none { it==':' } && !host.matches(Regex("[0-9.]+")) &&
            !host.endsWith(".local") && !host.endsWith(".localhost") && host!="localhost")
        raw
    }.getOrNull()

    fun parse(html: String,query: String): CatalogResult {
        require(html.toByteArray(Charsets.UTF_8).size<=MAX_BYTES)
        val script=Regex("<script\\b[^>]*\\bid=[\"']__NUXT_DATA__[\"'][^>]*>(.*?)</script>",setOf(RegexOption.DOT_MATCHES_ALL,RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.get(1) ?: throw IllegalArgumentException("Search data missing")
        val table=JSONArray(script);require(table.length()<=30000)
        val entities=HashMap<String,Any>()
        for(i in 0 until table.length()) {
            val obj=table.optJSONObject(i) ?: continue
            obj.keys().forEach { key->if(key.startsWith("Movie:") || key.startsWith("Show:") || key.startsWith("Offer:") || key.startsWith("Package:")) entities[key]=obj.get(key) }
        }
        fun value(raw: Any?,depth: Int=0): Any? {
            if(depth>12) return null
            val v=if(raw is Int) { if(raw in 0 until table.length()) table.opt(raw) else null } else raw
            if(v==JSONObject.NULL) return null
            if(v is JSONArray && v.optString(0) in setOf("Reactive","ShallowReactive","Ref","ShallowRef")) return value(v.opt(1),depth+1)
            if(v is JSONObject && v.has("__ref")) {
                val key=value(v.opt("__ref"),depth+1) as? String ?: return null
                return value(entities[key],depth+1)
            }
            return v
        }
        fun field(obj: Any?,name: String): Any? {
            val o=obj as? JSONObject ?: return null
            val key=o.keys().asSequence().firstOrNull { it==name || it.startsWith("$name(") } ?: return null
            return value(o.opt(key))
        }
        fun text(obj: Any?,name: String)=(field(obj,name) as? String).orEmpty()
        var connection: JSONObject?=null
        for(i in 0 until table.length()) {
            val obj=table.optJSONObject(i) ?: continue
            val key=obj.keys().asSequence().firstOrNull { key->
                if(!key.startsWith("searchTitles(")) false else runCatching {
                    val args=JSONObject(key.substringAfter('(').dropLast(1))
                    args.optString("country")=="BR" && args.optJSONObject("filter")?.optString("searchQuery").equals(query,true)
                }.getOrDefault(false)
            } ?: continue
            connection=value(obj.get(key)) as? JSONObject;break
        }
        require(connection!=null)
        val edges=field(connection,"edges") as? JSONArray ?: throw IllegalArgumentException("Search results missing")
        val titles=ArrayList<StreamTitle>()
        for(i in 0 until minOf(edges.length(),20)) {
            val node=field(value(edges.opt(i)),"node") ?: continue
            val kind=text(node,"__typename");if(kind!="Movie" && kind!="Show") continue
            val content=field(node,"content");val title=text(content,"title");val path=text(content,"fullPath")
            val id=text(node,"id");if(!id.matches(Regex("t[ms][0-9]{1,14}")) || title.isBlank() || !MediaLibrary.validText(title,128)) continue
            if(!path.startsWith("/br/filme/") && !path.startsWith("/br/serie/")) continue
            val page=web("https://www.justwatch.com$path") ?: continue
            val backdrop=(field(content,"backdrops") as? JSONArray)?.let { field(value(it.opt(0)),"backdropUrl") as? String }.orEmpty()
            val rawCover=backdrop.ifBlank { text(content,"posterUrl") }
            val cover=if(rawCover.startsWith("/backdrop/") || rawCover.startsWith("/poster/"))
                web("https://images.justwatch.com"+rawCover.replace("{profile}",if(backdrop.isBlank()) "s332" else "s640").replace("{format}","webp")).orEmpty() else ""
            val offers=ArrayList<StreamOffer>()
            // Use only the complete BR web offer list, never recommendations, other regions or physical media.
            val offerKey=(node as? JSONObject)?.keys()?.asSequence()?.firstOrNull {
                it.startsWith("offers(") && runCatching { val a=JSONObject(it.substringAfter('(').dropLast(1));a.optString("country")=="BR" &&
                    a.optString("platform")=="WEB" && a.optJSONObject("filter")?.has("monetizationTypes")!=true && a.optJSONObject("filter")?.has("presentationTypes")!=true }.getOrDefault(false)
            }
            val list=offerKey?.let { value((node as JSONObject).opt(it)) as? JSONArray }
            if(list!=null) for(j in 0 until minOf(list.length(),160)) {
                val offer=value(list.opt(j));val provider=field(offer,"package");val service=text(provider,"clearName")
                val type=text(offer,"monetizationType");val country=text(offer,"country")
                if(country.isNotEmpty() && country!="BR" || type !in setOf("FLATRATE","FLATRATE_AND_BUY","ADS","FREE","RENT","BUY")) continue
                if(service.isBlank() || !MediaLibrary.validText(service,100)) continue
                val url=web(text(offer,"preAffiliatedStandardWebURL")) ?: web(text(offer,"standardWebURL")) ?: continue
                val price=text(offer,"retailPrice").takeIf { MediaLibrary.validText(it,64) }.orEmpty()
                offers.add(StreamOffer(service,type,url,price))
            }
            titles.add(StreamTitle(id,title,(field(content,"originalReleaseYear") as? Number)?.toInt()?.takeIf { it in 1880..2200 } ?: 0,
                if(kind=="Show") "series" else "movie",cover,page,offers.distinctBy { it.service+it.type }.take(32)))
        }
        return CatalogResult(query,titles.distinctBy { it.id },if(titles.isEmpty()) "Nenhum título encontrado. Tente outro nome." else "Disponibilidade no Brasil · JustWatch")
    }

    fun intent(context: Context,offer: StreamOffer,apps: List<MediaApp>): Intent? {
        val url=web(offer.url) ?: return null
        val generic=Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val service=offer.service.lowercase(Locale.ROOT).replace(" plus","+")
        val candidates=apps.filter { app->
            val name=app.name.lowercase(Locale.ROOT).replace(" plus","+")
            service==name || service.contains(name) && name.length>=4 || name.contains(service) && service.length>=4
        }
        for(app in candidates) {
            val specific=Intent(generic).setPackage(app.packageName)
            val target=context.packageManager.resolveActivity(specific,0)?.activityInfo
            if(target!=null && target.enabled && target.exported && target.packageName==app.packageName) return specific
        }
        return generic
    }
}

internal interface CatalogTransport { fun read(query: String): String;fun cancel() {} }
internal class PublicCatalogTransport:CatalogTransport {
    @Volatile private var connection: HttpsURLConnection?=null
    override fun read(query: String): String {
        val c=URL(StreamingPages.searchUrl(query)).openConnection() as HttpsURLConnection
        c.connectTimeout=5000;c.readTimeout=6000;c.instanceFollowRedirects=false;c.useCaches=false
        c.setRequestProperty("User-Agent","TCL-Light/${BuildConfig.VERSION_NAME}")
        c.setRequestProperty("Accept","text/html");c.setRequestProperty("Accept-Encoding","gzip")
        connection=c
        val deadline=android.os.SystemClock.elapsedRealtime()+15000L
        try {
            require(c.responseCode==200)
            val input=if(c.contentEncoding.equals("gzip",true)) GZIPInputStream(c.inputStream) else c.inputStream
            return input.use {
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) { require(android.os.SystemClock.elapsedRealtime()<deadline);val n=it.read(buffer);if(n<0) break;require(output.size()+n<=StreamingPages.MAX_BYTES);output.write(buffer,0,n) }
                output.toString("UTF-8")
            }
        } finally { c.disconnect();if(connection===c) connection=null }
    }
    override fun cancel() { connection?.disconnect() }
}

/** Four in-memory searches, 15-minute freshness, one cancellable worker and no periodic catalog activity. */
internal class StreamingSearch(private val transport: CatalogTransport=PublicCatalogTransport(),private val now:()->Long=System::currentTimeMillis) {
    private val worker=Executors.newSingleThreadExecutor { r->Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run() },"tv-content-search") }
    private val handler=Handler(Looper.getMainLooper())
    private val cache=LinkedHashMap<String,Pair<Long,CatalogResult>>()
    @Volatile private var generation=0
    @Volatile private var closed=false
    fun cancel() { generation++;transport.cancel() }
    fun search(query: String,done:(CatalogResult)->Unit) {
        require(query.isNotBlank() && MediaLibrary.validText(query,128))
        cancel();val request=generation
        cache[query]?.takeIf { now()-it.first in 0 until 15*60*1000L }?.let { done(it.second);return }
        worker.execute {
            if(closed || request!=generation) return@execute
            val result=runCatching { StreamingPages.parse(transport.read(query),query) }.getOrElse {
                CatalogResult(query,message="Não foi possível consultar o catálogo agora. Tente novamente ou abra o JustWatch.",failed=true)
            }
            handler.post {
                if(!closed && request==generation) {
                    if(!result.failed) { cache[query]=now() to result;while(cache.size>4) cache.remove(cache.keys.first()) }
                    done(result)
                }
            }
        }
    }
    fun close() { closed=true;cancel();worker.shutdownNow();cache.clear() }
}
