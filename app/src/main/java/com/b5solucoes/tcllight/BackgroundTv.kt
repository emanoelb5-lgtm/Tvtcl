package com.b5solucoes.tcllight

import android.content.Context
import android.graphics.Color
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

internal interface BackgroundVideoBackend {
    val view: View
    fun tune(inputId: String)
    fun reset()
}

internal class NativeBackgroundVideo(context: Context, playing: () -> Unit, failed: (String) -> Unit) : BackgroundVideoBackend {
    private val tv = TvView(context).apply {
        isFocusable = false; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setStreamVolume(0f)
        setCallback(object : TvView.TvInputCallback() {
            override fun onVideoAvailable(inputId: String) { setStreamVolume(0f); playing() }
            override fun onConnectionFailed(inputId: String) { failed("A TV não permitiu exibir essa entrada ao fundo.") }
            override fun onDisconnected(inputId: String) { failed("A conexão com a entrada foi encerrada.") }
            override fun onVideoUnavailable(inputId: String, reason: Int) {
                if (reason != TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
                    failed("O vídeo desta entrada não está disponível ao fundo.")
            }
        })
    }
    override val view: View get() = tv
    override fun tune(inputId: String) { tv.setStreamVolume(0f); tv.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId)) }
    override fun reset() = tv.reset()
}

internal enum class BackgroundTvState { OFF, STARTING, PLAYING, UNAVAILABLE }

/** Opt-in, muted, foreground-only trial. Failure restores the wallpaper and releases the session. */
internal class BackgroundTv(private val root: FrameLayout, private val handler: Handler,
    private val visibility: (Boolean) -> Unit, private val changed: (BackgroundTvState, String) -> Unit,
    private val factory: (() -> Unit, (String) -> Unit) -> BackgroundVideoBackend = { playing, failed -> NativeBackgroundVideo(root.context, playing, failed) },
    private val timeoutMs: Long = 8000L) {
    var state = BackgroundTvState.OFF
        private set
    var inputId: String? = null
        private set
    var message = "Desativado"
        private set
    private var backend: BackgroundVideoBackend? = null
    private var layer: FrameLayout? = null
    private var generation = 0
    private var timeout: Runnable? = null

    private fun publish(next: BackgroundTvState, detail: String) { state = next; message = detail; changed(next, detail) }

    fun start(source: TvSource?, economy: Boolean) {
        stop()
        if (economy) { publish(BackgroundTvState.UNAVAILABLE, "Desative o modo leve para testar a TV ao fundo."); return }
        if (source == null || !source.passthrough || source.state != TvInputManager.INPUT_STATE_CONNECTED) {
            publish(BackgroundTvState.UNAVAILABLE, "Escolha uma entrada de vídeo conectada disponibilizada pela TV."); return
        }
        inputId = source.id
        val token = generation
        publish(BackgroundTvState.STARTING, "Testando ${source.label}…")
        try {
            val native = factory({
                handler.post {
                    if (token != generation || backend == null) return@post
                    timeout?.let { handler.removeCallbacks(it) }; timeout = null
                    visibility(true); publish(BackgroundTvState.PLAYING, "${source.label} · Sem áudio")
                }
            }, { detail -> handler.post { if (token == generation) fail(detail) } })
            backend = native
            val container = FrameLayout(root.context).apply {
                tag = "home:tv-background"
                addView(native.view, FrameLayout.LayoutParams(-1,-1))
                addView(View(context).apply { setBackgroundColor(Color.argb(176,4,12,22)); isFocusable = false }, FrameLayout.LayoutParams(-1,-1))
            }
            layer = container
            root.addView(container,0,FrameLayout.LayoutParams(-1,-1))
            timeout = Runnable { if (token == generation && state == BackgroundTvState.STARTING) fail("A entrada não respondeu. O papel de parede foi mantido.") }
                .also { handler.postDelayed(it,timeoutMs) }
            native.tune(source.id)
        } catch (_: Exception) { fail("O firmware da TV não disponibilizou essa entrada ao launcher.") }
    }

    fun fail(detail: String) { release(); publish(BackgroundTvState.UNAVAILABLE, detail) }
    fun stop() { release(); publish(BackgroundTvState.OFF,"Desativado") }

    private fun release() {
        generation++
        timeout?.let { handler.removeCallbacks(it) }; timeout = null
        try { backend?.reset() } catch (_: Exception) {}
        backend = null
        layer?.let { root.removeView(it) }; layer = null; inputId = null
        visibility(false)
    }
}
