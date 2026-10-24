package com.b5solucoes.tcllight

import android.content.Context
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal

internal data class WatchItem(val entry: MediaEntry, val position: Long, val duration: Long, val lastUsed: Long) {
    val percent: Int get() = if(duration <= 0) 0 else (position.coerceIn(0,duration).toDouble()/duration*100).toInt()
    val progressLabel get() = if(duration > 0) "$percent% assistido" else "Continuar no aplicativo"
}
internal data class GuideProgram(val id: Long, val channelId: Long, val title: String, val start: Long, val end: Long)
internal data class GuideChannel(val id: Long, val number: String, val name: String, val programs: List<GuideProgram> = emptyList()) {
    fun entry()=MediaEntry(title=(number+" "+name).trim().take(128),kind="channel",link=TvContract.buildChannelUri(id).toString())
    fun now(time: Long)=programs.firstOrNull { it.start <= time && it.end > time }
    fun next(time: Long)=programs.firstOrNull { it.start > time }
}
internal data class WatchCatalog(val items: List<WatchItem> = emptyList(), val message: String = "Consultando os aplicativos…")
internal data class GuideCatalog(val channels: List<GuideChannel> = emptyList(), val message: String = "Consultando a programação da TV…")

/** Bounded provider queries. No account login, scraping, hidden permissions or synthetic program data. */
internal class TvMediaCatalog(context: Context, private val query: (Uri,Array<String>,String?,Array<String>?,CancellationSignal)->Cursor? =
    { uri,columns,selection,args,signal -> context.contentResolver.query(uri,columns,selection,args,null,signal) }) {
    fun watch(signal: CancellationSignal = CancellationSignal()): WatchCatalog {
        if(Build.VERSION.SDK_INT < 26) return WatchCatalog(message="Esta versão do Android não oferece o Continuar assistindo.")
        return try {
            val columns=arrayOf("_id","package_name","title","intent_uri","poster_art_uri","last_playback_position_millis",
                "duration_millis","last_engagement_time_utc_millis","episode_title","watch_next_type")
            val result=ArrayList<WatchItem>()
            query(TvContract.WatchNextPrograms.CONTENT_URI,columns,null,null,signal)?.use { cursor ->
                var scanned=0
                while(scanned++ < 200 && cursor.moveToNext()) {
                    signal.throwIfCanceled()
                    val owner=cursor.text("package_name");val title=cursor.text("title").take(128)
                    val link=cursor.text("intent_uri")
                    if(!MediaLibrary.validPackage(owner) || title.isBlank() || MediaLinks.parse(link,owner) == null) continue
                    // Next/new episodes are also useful; a Watchlist-only item belongs in the app's own list.
                    if(cursor.number("watch_next_type") == 3L) continue
                    val cover=cursor.text("poster_art_uri").takeIf { MediaLinks.coverUri(it) != null } ?: ""
                    val episode=cursor.text("episode_title").take(80)
                    val entry=MediaEntry(id=java.util.UUID.nameUUIDFromBytes((owner+":"+cursor.number("_id")).toByteArray()).toString(),
                        title=(if(episode.isBlank())title else "$title · $episode").take(128),kind=if(episode.isBlank())"movie" else "series",
                        packageName=owner,link=link,cover=cover)
                    result.add(WatchItem(entry,cursor.number("last_playback_position_millis").coerceAtLeast(0),
                        cursor.number("duration_millis").coerceAtLeast(0),cursor.number("last_engagement_time_utc_millis")))
                }
            }
            WatchCatalog(result.sortedByDescending { it.lastUsed }.distinctBy { it.entry.id }.take(24),
                if(result.isEmpty()) "Os aplicativos ainda não compartilharam itens acessíveis ao TCL Light." else "Conteúdo compartilhado pelos aplicativos instalados.")
        } catch(_:SecurityException) { WatchCatalog(message="A TV restringiu o acesso ao Continuar assistindo. Você pode usar a Minha lista.") }
        catch(_:Exception) { WatchCatalog(message="O Continuar assistindo não está disponível nesta TV. Você pode usar a Minha lista.") }
    }

    fun guide(now: Long = System.currentTimeMillis(), signal: CancellationSignal = CancellationSignal()): GuideCatalog = try {
        val channels=ArrayList<GuideChannel>()
        query(TvContract.Channels.CONTENT_URI,arrayOf("_id","display_number","display_name","browsable","type"),null,null,signal)?.use { cursor ->
            var scanned=0
            while(scanned++ < 400 && cursor.moveToNext()) {
                signal.throwIfCanceled()
                val id=cursor.number("_id");val name=cursor.text("display_name").take(100)
                if(id < 0 || name.isBlank() || cursor.number("browsable") == 0L || cursor.text("type") == "TYPE_PREVIEW") continue
                channels.add(GuideChannel(id,cursor.text("display_number").take(20),name))
                if(channels.size >= 120) break
            }
        }
        if(channels.isEmpty()) GuideCatalog(message="A TV não compartilhou canais com o launcher. Abra o guia da TV ou crie um lembrete manual.")
        else {
            var programsAllowed=true
            val programs=ArrayList<GuideProgram>()
            try {
                // Third-party callers must use the provider's URI time filters; SQL selection requires system access.
                val programsUri=TvContract.Programs.CONTENT_URI.buildUpon().appendQueryParameter("start_time",now.toString())
                    .appendQueryParameter("end_time",(now+24*60*60*1000L).toString()).build()
                query(programsUri,arrayOf("_id","channel_id","title","start_time_utc_millis","end_time_utc_millis"),null,null,signal)?.use { cursor ->
                    var scanned=0
                    while(scanned++ < 1200 && cursor.moveToNext()) {
                        signal.throwIfCanceled()
                        val title=cursor.text("title").take(128);val start=cursor.number("start_time_utc_millis");val end=cursor.number("end_time_utc_millis")
                        if(title.isNotBlank() && end > start && end > now && start < now+24*60*60*1000L)
                            programs.add(GuideProgram(cursor.number("_id"),cursor.number("channel_id"),title,start,end))
                    }
                }
            } catch(_:Exception) { programsAllowed=false }
            val grouped=programs.groupBy { it.channelId }
            GuideCatalog(channels.distinctBy { it.id }.sortedWith(compareBy<GuideChannel> { it.number.takeWhile { c-> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.number })
                .map { it.copy(programs=grouped[it.id].orEmpty().sortedBy { p->p.start }.take(30)) },
                if(!programsAllowed) "Canais disponíveis. A TV restringiu os horários; consulte o guia nativo."
                else if(programs.isEmpty()) "Canais disponíveis. Nenhuma programação foi compartilhada para as próximas 24 horas."
                else "Agora e próximas 24 horas · Dados fornecidos pela TV")
        }
    } catch(_:SecurityException) { GuideCatalog(message="A TV restringiu o acesso aos canais e horários. Abra o guia da TV ou crie um lembrete manual.") }
    catch(_:Exception) { GuideCatalog(message="A programação não está disponível nesta TV. Abra o guia nativo ou crie um lembrete manual.") }

    private fun Cursor.text(name: String): String { val i=getColumnIndex(name);return if(i<0 || isNull(i)) "" else getString(i).orEmpty() }
    private fun Cursor.number(name: String): Long { val i=getColumnIndex(name);return if(i<0 || isNull(i)) 0L else runCatching { getLong(i) }.getOrDefault(0L) }
}
