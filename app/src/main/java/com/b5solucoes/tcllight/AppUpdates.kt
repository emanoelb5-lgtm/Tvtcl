package com.b5solucoes.tcllight

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

internal data class UpdateStream(val input: InputStream, val length: Long, val closeConnection: () -> Unit = {}) : Closeable {
    override fun close() { try { input.close() } finally { closeConnection() } }
}

internal interface UpdateTransport {
    fun open(url: String): UpdateStream
    fun cancel() {}
}

/** HTTPS only, with explicit limits on GitHub asset redirects and time spent on each request. */
internal class GitHubUpdateTransport : UpdateTransport {
    @Volatile private var active: HttpsURLConnection? = null

    companion object {
        internal fun allowed(url: URL): Boolean = url.protocol == "https" && url.userInfo == null &&
            (url.port == -1 || url.port == 443) &&
            url.host.lowercase(java.util.Locale.ROOT) in setOf("github.com", "objects.githubusercontent.com",
                "release-assets.githubusercontent.com", "github-releases.githubusercontent.com")
    }

    override fun open(url: String): UpdateStream {
        var target = URL(url)
        repeat(6) {
            if (!allowed(target)) throw UpdateFailure("O endereço da atualização não pôde ser confirmado.")
            val connection = (target.openConnection() as HttpsURLConnection).apply {
                connectTimeout = 8000; readTimeout = 15000; instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", "TCL-Light/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Cache-Control", "no-cache")
            }
            active = connection
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                        ?: throw UpdateFailure("O download não está disponível agora.")
                    target = URL(target, location)
                    connection.disconnect(); active = null
                } else {
                    if (status != 200) throw UpdateFailure(if (status == 404)
                        "Ainda não há uma atualização disponível neste canal." else "O GitHub não respondeu. Tente novamente mais tarde.")
                    val input = connection.inputStream
                    val length = if (Build.VERSION.SDK_INT >= 24) connection.contentLengthLong else connection.contentLength.toLong()
                    return UpdateStream(input, length) {
                        connection.disconnect()
                        if (active === connection) active = null
                    }
                }
            } catch (error: Exception) {
                connection.disconnect()
                if (active === connection) active = null
                throw error
            }
        }
        throw UpdateFailure("O download não está disponível agora.")
    }

    override fun cancel() { active?.disconnect() }
}

internal enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }
internal data class UpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val release: UpdateRelease? = null,
    val percent: Int = 0, val message: String = "Verifique se há uma nova versão do TCL Light.")

/** One opt-in worker, no service, polling, location access or Activity held in the background. */
internal class AppUpdates internal constructor(context: Context,
    private val transport: UpdateTransport = GitHubUpdateTransport(),
    private val identity: () -> InstalledUpdateApp = { UpdateApkVerifier.installed(context.applicationContext) }) {
    companion object {
        @Volatile private var shared: AppUpdates? = null
        fun get(context: Context): AppUpdates = shared ?: synchronized(this) {
            shared ?: AppUpdates(context.applicationContext).also { shared = it }
        }
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { work ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); work.run() }, "tv-updates")
    }
    private val busy = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val observers = LinkedHashSet<(UpdateState) -> Unit>()
    private val directory get() = File(app.cacheDir, "updates").apply { mkdirs() }
    private val apk get() = UpdateApkProvider.apk(app)
    @Volatile var state = UpdateState()
        private set

    fun observe(observer: (UpdateState) -> Unit) { observers.add(observer); observer(state) }
    fun removeObserver(observer: (UpdateState) -> Unit) { observers.remove(observer) }

    private fun publish(next: UpdateState) {
        main.post { state = next; observers.toList().forEach { it(next) } }
    }

    /** Publish an actionable state only after this operation and its cleanup have left the worker. */
    private fun complete(next: UpdateState, after: () -> Unit = {}) {
        try {
            executor.execute {
                main.post { busy.set(false);state=next;observers.toList().forEach { it(next) };after() }
            }
        } catch(_:java.util.concurrent.RejectedExecutionException) { busy.set(false) }
    }

    fun check() {
        if (!busy.compareAndSet(false, true)) return
        cancelled.set(false)
        publish(UpdateState(UpdatePhase.CHECKING, message = "Procurando atualizações…"))
        executor.execute {
            try {
                File(directory, "update.part").delete()
                val json = transport.open(UpdatePolicy.FEED + "?check=" + System.currentTimeMillis() / 60000).use { stream ->
                    val bytes = stream.input.readBytesLimited(UpdatePolicy.MAX_FEED_SIZE)
                    bytes.toString(Charsets.UTF_8)
                }
                val release = UpdatePolicy.parse(json)
                val installed = identity()
                if (release.versionCode <= installed.versionCode) {
                    apk.delete()
                    complete(UpdateState(UpdatePhase.CURRENT, message = "Você já está na versão mais recente."))
                } else if (release.minSdk > Build.VERSION.SDK_INT) {
                    throw UpdateFailure("Esta atualização exige uma versão mais recente do Android.")
                } else {
                    if (apk.isFile) {
                        try {
                            UpdateApkVerifier.verify(app.packageManager, apk, release, installed)
                            complete(UpdateState(UpdatePhase.READY, release, 100, "A atualização está pronta para instalar."))
                            return@execute
                        } catch (_: Exception) { apk.delete() }
                    }
                    complete(UpdateState(UpdatePhase.AVAILABLE, release, message = "Uma nova versão está disponível."))
                }
            } catch (error: Exception) { failed(error) }
        }
    }

    fun download() {
        val release = state.release ?: return
        if (state.phase != UpdatePhase.AVAILABLE && state.phase != UpdatePhase.ERROR) return
        if (!busy.compareAndSet(false, true)) return
        cancelled.set(false)
        publish(UpdateState(UpdatePhase.DOWNLOADING, release, message = "Baixando a atualização…"))
        executor.execute {
            val partial = File(directory, "update.part")
            try {
                if (directory.usableSpace < release.size * 2 + 1024 * 1024)
                    throw UpdateFailure("Não há espaço livre suficiente para baixar a atualização.")
                transport.open(release.apkUrl).use { stream ->
                    if (stream.length > 0 && stream.length != release.size)
                        throw UpdateFailure("O arquivo disponível não corresponde à atualização anunciada.")
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var received = 0L
                        var lastReport = 0L
                        while (true) {
                            if (cancelled.get()) throw InterruptedException()
                            val count = stream.input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > release.size) throw UpdateFailure("O arquivo disponível não corresponde à atualização anunciada.")
                            output.write(buffer, 0, count)
                            val now = SystemClock.uptimeMillis()
                            if (now - lastReport >= 200L) {
                                lastReport = now
                                publish(UpdateState(UpdatePhase.DOWNLOADING, release, (received * 100 / release.size).toInt(), "Baixando a atualização…"))
                            }
                        }
                    }
                }
                if (cancelled.get()) throw InterruptedException()
                publish(UpdateState(UpdatePhase.VERIFYING, release, 100, "Conferindo a atualização…"))
                UpdateApkVerifier.verify(app.packageManager, partial, release, identity())
                apk.delete()
                if (!partial.renameTo(apk)) throw UpdateFailure("Não foi possível guardar a atualização. Tente novamente.")
                complete(UpdateState(UpdatePhase.READY, release, 100, "A atualização está pronta para instalar."))
            } catch (error: Exception) {
                if (cancelled.get()) complete(UpdateState(UpdatePhase.AVAILABLE, release, message = "Download cancelado."))
                else failed(error, release)
            } finally { partial.delete() }
        }
    }

    fun cancel() {
        if (state.phase != UpdatePhase.DOWNLOADING) return
        cancelled.set(true)
        transport.cancel()
    }

    fun prepareInstall(ready: () -> Unit) {
        val release = state.release ?: return
        if (state.phase != UpdatePhase.READY || !busy.compareAndSet(false, true)) return
        publish(UpdateState(UpdatePhase.VERIFYING, release, 100, "Conferindo a atualização…"))
        executor.execute {
            try {
                UpdateApkVerifier.verify(app.packageManager, apk, release, identity())
                complete(UpdateState(UpdatePhase.READY, release, 100, "Confirme a instalação na próxima tela da TV."),ready)
            } catch (error: Exception) { apk.delete(); failed(error, release) }
        }
    }

    fun installMessage(message: String) {
        val current = state
        if (current.phase == UpdatePhase.READY) publish(current.copy(message = message))
    }

    private fun failed(error: Exception, release: UpdateRelease? = null) {
        complete(UpdateState(UpdatePhase.ERROR, release, message = (error as? UpdateFailure)?.message
            ?: "Não foi possível concluir a atualização. Verifique a conexão e tente novamente."))
    }

    internal fun close() { cancelled.set(true); transport.cancel(); executor.shutdownNow(); observers.clear() }
}

private fun InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) throw UpdateFailure("As informações da atualização são inválidas.")
        output.write(buffer, 0, count)
    }
}
