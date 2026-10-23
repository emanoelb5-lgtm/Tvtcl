package com.b5solucoes.tcllight

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import android.os.Handler

internal data class TvSource(val id: String, val label: String, val passthrough: Boolean,
    val state: Int, val packageName: String) {
    val detail: String get() = when {
        !passthrough -> "TV e canais"
        state == TvInputManager.INPUT_STATE_CONNECTED -> "Conectada"
        state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "Em espera"
        else -> "Sem sinal"
    }

    fun intent(): Intent = Intent(Intent.ACTION_VIEW,
        if (passthrough) TvContract.buildChannelUriForPassthroughInput(id)
        else TvContract.buildChannelsUriForInput(id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Video inputs come only from the TV framework, never from keyboards or mice. */
internal class TvSources(private val context: Context, private val handler: Handler,
    private val changed: () -> Unit) {
    private val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
    private var registered = false
    private val callback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(inputId: String) = changed()
        override fun onInputRemoved(inputId: String) = changed()
        override fun onInputUpdated(inputId: String) = changed()
        override fun onInputStateChanged(inputId: String, state: Int) = changed()
        override fun onTvInputInfoUpdated(inputInfo: TvInputInfo) = changed()
    }

    fun read(): List<TvSource> = try {
        val entries = manager?.tvInputList.orEmpty()
        val ids = entries.map { it.id }.toSet()
        entries.filter { info ->
            (Build.VERSION.SDK_INT < 24 || !info.isHidden(context)) &&
                (info.parentId == null || info.parentId !in ids)
        }.map { info ->
            val custom = if (Build.VERSION.SDK_INT >= 24) info.loadCustomLabel(context) else null
            val label = (custom ?: info.loadLabel(context)).toString().trim().ifBlank {
                if (info.type == TvInputInfo.TYPE_TUNER) "TV / Antena" else "Entrada de vídeo"
            }
            TvSource(info.id, label, info.isPassthroughInput,
                manager?.getInputState(info.id) ?: TvInputManager.INPUT_STATE_DISCONNECTED,
                info.serviceInfo.packageName)
        }.distinctBy { it.id }.sortedWith(compareBy<TvSource> { it.passthrough }.thenBy { it.label })
    } catch (_: Exception) { emptyList() }

    fun start() {
        if (!registered) try { manager?.registerCallback(callback, handler); registered = manager != null }
        catch (_: Exception) { }
    }

    fun stop() {
        if (registered) try { manager?.unregisterCallback(callback) } catch (_: Exception) { }
        registered = false
    }
}
