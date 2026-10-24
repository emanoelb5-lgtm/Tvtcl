package com.b5solucoes.tcllight

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.provider.Settings
import android.service.notification.NotificationListenerService

/** Android requires this explicit access for third-party controllers. Notification contents are never consumed. */
class MediaAccessService : NotificationListenerService()

internal data class PlaybackInfo(val owner: String = "", val title: String = "Nenhuma reprodução ativa",
    val detail: String = "Abra um aplicativo de mídia para começar.", val playing: Boolean = false,
    val canPlay: Boolean = false, val canPause: Boolean = false)

/** Observe active sessions only while Home is visible; release every controller callback on stop. */
internal class MediaPlayback(private val context: Context, private val handler: Handler, private val changed: () -> Unit) {
    private val manager=context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component=ComponentName(context,MediaAccessService::class.java)
    private val controllers=ArrayList<MediaController>()
    private var selected: MediaController? = null
    private var started=false
    private var listening=false
    private var accessible=false
    var info=PlaybackInfo()
        private set
    private val callback=object:MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?)=refreshInfo()
        override fun onPlaybackStateChanged(state: PlaybackState?)=refreshInfo()
        override fun onSessionDestroyed()=refresh()
    }
    private val listener=MediaSessionManager.OnActiveSessionsChangedListener { sessions -> bind(sessions.orEmpty()) }
    fun enabled(): Boolean = runCatching { Settings.Secure.getString(context.contentResolver,"enabled_notification_listeners").orEmpty()
        .split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it == component } }.getOrDefault(false)
    fun start() {
        if(started) return
        started=true;accessible=false
        if(!enabled()) { info=PlaybackInfo(detail="Ative o acesso aos controles de mídia para usar este painel.");changed();return }
        try {
            manager.addOnActiveSessionsChangedListener(listener,component,handler);listening=true
            refresh()
        } catch(_:Exception) { info=PlaybackInfo(detail="A TV não permitiu acesso aos controles de mídia.");changed() }
    }
    fun refresh() {
        if(!started) return
        try { bind(manager.getActiveSessions(component));accessible=true }
        catch(_:Exception) { bind(emptyList());accessible=false;info=PlaybackInfo(detail="A TV não permitiu acesso aos controles de mídia.");changed() }
    }
    private fun bind(items: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } };controllers.clear()
        if(!started) return
        controllers.addAll(items.filter { it.packageName != context.packageName }.take(8))
        controllers.forEach { runCatching { it.registerCallback(callback,handler) } }
        refreshInfo()
    }
    private fun refreshInfo() {
        selected=controllers.sortedBy { when(it.playbackState?.state) { PlaybackState.STATE_PLAYING->0;PlaybackState.STATE_PAUSED->1;else->2 } }.firstOrNull()
        val active=selected
        info=if(active == null) PlaybackInfo() else {
            val meta=active.metadata;val state=active.playbackState;val actions=state?.actions ?: 0L
            fun supports(action: Long)=actions and action != 0L || actions and PlaybackState.ACTION_PLAY_PAUSE != 0L
            PlaybackInfo(active.packageName,meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.take(160)?.ifBlank { null }
                ?: runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(active.packageName,0)).toString() }.getOrDefault("Aplicativo de mídia"),
                meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.take(160).orEmpty().ifBlank { if(state?.state == PlaybackState.STATE_PLAYING) "Em reprodução" else "Reprodução pausada ou encerrada" },
                state?.state == PlaybackState.STATE_PLAYING,supports(PlaybackState.ACTION_PLAY),supports(PlaybackState.ACTION_PAUSE))
        }
        changed()
    }
    fun toggle(): Boolean { return try {
        val active=selected ?: return false
        if(info.playing && info.canPause) active.transportControls.pause()
        else if(!info.playing && info.canPlay) active.transportControls.play()
        else return false
        true
    } catch(_:Exception) { false } }
    fun open(): Boolean { return try { val active=selected ?: return false;val intent=active.sessionActivity ?: return false;intent.send();true } catch(_:Exception) { false } }
    fun stop() {
        started=false
        if(listening) runCatching { manager.removeOnActiveSessionsChangedListener(listener) }
        listening=false;accessible=false
        controllers.forEach { runCatching { it.unregisterCallback(callback) } };controllers.clear();selected=null
        info=PlaybackInfo()
    }
}
