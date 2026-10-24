package com.b5solucoes.tcllight

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Uses the actual, publicly shipped APK with the permanent certificate, never a mocked APK parser. */
@RunWith(AndroidJUnit4::class)
class UpdateFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var fixture: File
    private val engines = ArrayList<AppUpdates>()
    private val installed = InstalledUpdateApp(UpdatePolicy.PACKAGE, 6, UpdatePolicy.CERTIFICATE)
    private val manifest get() = JSONObject().apply {
        put("schema", 1); put("package", UpdatePolicy.PACKAGE)
        put("version_name", "0.7.0"); put("version_code", 7); put("min_sdk", 23)
        put("apk_url", "${UpdatePolicy.REPOSITORY}/releases/download/v0.7.0/TCL-Light-0.7.0.apk")
        put("size_bytes", fixture.length()); put("sha256", UpdatePolicy.sha256(fixture))
        put("certificate_sha256", UpdatePolicy.CERTIFICATE)
    }

    @Before fun setup() {
        File(context.cacheDir, "updates").deleteRecursively()
        fixture = File(context.cacheDir, "official-previous.apk")
        instrumentation.context.assets.open("previous-release.apk").use { source ->
            fixture.outputStream().use { source.copyTo(it) }
        }
    }

    @After fun cleanup() { engines.forEach { it.close() }; fixture.delete() }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("O arquivo ou metadado não confiável foi aceito") }
        catch (_: UpdateFailure) {}
    }

    private fun await(engine: AppUpdates, phase: UpdatePhase) {
        val deadline = android.os.SystemClock.uptimeMillis() + 12000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (engine.state.phase == phase) return
            Thread.sleep(20)
        }
        fail("Esperado $phase, recebido ${engine.state}")
    }

    private fun engine(transport: UpdateTransport): AppUpdates =
        AppUpdates(context, transport) { installed }.also { engines.add(it) }

    private fun memoryTransport(metadata: ByteArray = manifest.toString().toByteArray(),
                                apk: ByteArray = fixture.readBytes()) = object : UpdateTransport {
        override fun open(url: String): UpdateStream {
            val data = if (url.startsWith(UpdatePolicy.FEED)) metadata else apk
            return UpdateStream(ByteArrayInputStream(data), data.size.toLong())
        }
    }

    @Test fun testManifestRejectsForeignPackagesKeysUrlsAndUnboundedFiles() {
        assertEquals(7L, UpdatePolicy.parse(manifest.toString()).versionCode)
        for ((key, value) in listOf("package" to "other.app", "certificate_sha256" to "0".repeat(64),
            "apk_url" to "http://github.com/unsafe.apk", "apk_url" to "https://example.org/app.apk",
            "apk_url" to "${UpdatePolicy.REPOSITORY}/releases/download/beta/TCL-Light-beta.apk",
            "size_bytes" to UpdatePolicy.MAX_APK_SIZE + 1, "version_code" to 0, "version_code" to 7.5, "sha256" to "bad")) {
            rejects { UpdatePolicy.parse(JSONObject(manifest.toString()).put(key, value).toString()) }
        }
        assertFalse(GitHubUpdateTransport.allowed(java.net.URL("http://github.com/a.apk")))
        assertFalse(GitHubUpdateTransport.allowed(java.net.URL("https://github.com.evil.example/a.apk")))
        assertFalse(GitHubUpdateTransport.allowed(java.net.URL("https://github.com:444/a.apk")))
        assertTrue(GitHubUpdateTransport.allowed(java.net.URL("https://release-assets.githubusercontent.com/a.apk")))
    }

    @Test fun testRealApkNeedsThePermanentKeyExpectedVersionAndCompleteBytes() {
        val release = UpdatePolicy.parse(manifest.toString())
        UpdateApkVerifier.verify(context.packageManager, fixture, release, installed)
        rejects { UpdateApkVerifier.verify(context.packageManager, fixture, release, installed.copy(certificate = "0".repeat(64))) }
        rejects { UpdateApkVerifier.verify(context.packageManager, fixture, release, installed.copy(versionCode = 7)) }
        rejects { UpdateApkVerifier.verify(context.packageManager, fixture, release.copy(versionCode = 8), installed) }
        rejects { UpdateApkVerifier.verify(context.packageManager, fixture, release.copy(versionName = "0.7.1"), installed) }
        fixture.appendBytes(byteArrayOf(1))
        rejects { UpdateApkVerifier.verify(context.packageManager, fixture, release, installed) }
    }

    @Test fun testDownloadAndInstallPreparationRevalidateTheOfficialApk() {
        val engine = engine(memoryTransport())
        engine.check(); await(engine, UpdatePhase.AVAILABLE)
        engine.download(); await(engine, UpdatePhase.READY)
        assertEquals(UpdatePolicy.sha256(fixture), UpdatePolicy.sha256(UpdateApkProvider.apk(context)))
        val ready = CountDownLatch(1)
        engine.prepareInstall { ready.countDown() }
        assertTrue(ready.await(12, TimeUnit.SECONDS))
        await(engine, UpdatePhase.READY)
        UpdateApkProvider.apk(context).appendBytes(byteArrayOf(1))
        val invoked = AtomicBoolean(false)
        engine.prepareInstall { invoked.set(true) }
        await(engine, UpdatePhase.ERROR)
        assertFalse(invoked.get())
        assertFalse(UpdateApkProvider.apk(context).exists())
    }

    @Test fun testOfflineOversizedMetadataAndTruncatedApkLeaveNoInstallableFile() {
        val offline = engine(object : UpdateTransport {
            override fun open(url: String): UpdateStream = throw IOException("offline")
        })
        offline.check(); await(offline, UpdatePhase.ERROR)
        val oversized = engine(memoryTransport(ByteArray(UpdatePolicy.MAX_FEED_SIZE + 1)))
        oversized.check(); await(oversized, UpdatePhase.ERROR)
        val broken = engine(memoryTransport(apk = fixture.readBytes().copyOf(120)))
        broken.check(); await(broken, UpdatePhase.AVAILABLE)
        broken.download(); await(broken, UpdatePhase.ERROR)
        assertFalse(UpdateApkProvider.apk(context).exists())
        assertFalse(File(context.cacheDir, "updates/update.part").exists())
    }

    @Test fun testCancellationDeletesThePartialDownload() {
        val blocked = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val data = fixture.readBytes()
        val metadata = manifest.toString().toByteArray()
        val transport = object : UpdateTransport {
            override fun open(url: String): UpdateStream {
                if (url.startsWith(UpdatePolicy.FEED)) return UpdateStream(ByteArrayInputStream(metadata), metadata.size.toLong())
                return UpdateStream(object : InputStream() {
                    var offset = 0
                    override fun read(): Int = throw UnsupportedOperationException()
                    override fun read(bytes: ByteArray, start: Int, length: Int): Int {
                        if (offset >= 32768) { blocked.countDown(); releaseRead.await(12, TimeUnit.SECONDS) }
                        if (offset == data.size) return -1
                        val count = minOf(length, data.size - offset)
                        data.copyInto(bytes, start, offset, offset + count); offset += count
                        return count
                    }
                }, data.size.toLong())
            }
            override fun cancel() { releaseRead.countDown() }
        }
        val engine = engine(transport)
        engine.check(); await(engine, UpdatePhase.AVAILABLE)
        engine.download()
        assertTrue(blocked.await(12, TimeUnit.SECONDS))
        await(engine, UpdatePhase.DOWNLOADING)
        engine.cancel(); await(engine, UpdatePhase.AVAILABLE)
        assertEquals("Download cancelado.", engine.state.message)
        assertFalse(File(context.cacheDir, "updates/update.part").exists())
        assertFalse(UpdateApkProvider.apk(context).exists())
    }

    @Test fun testPrivateProviderExposesOnlyTheReadOnlyUpdateApk() {
        val apk = UpdateApkProvider.apk(context)
        apk.parentFile!!.mkdirs(); fixture.copyTo(apk, overwrite = true)
        val uri = UpdateApkProvider.uri(context)
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { assertEquals(fixture.length(), it.statSize) }
        try { context.contentResolver.openFileDescriptor(uri, "rw"); fail("Escrita permitida") }
        catch (_: java.io.FileNotFoundException) {}
        try { context.contentResolver.openFileDescriptor(Uri.parse("content://${context.packageName}.updates/../other.apk"), "r"); fail("Caminho extra permitido") }
        catch (_: java.io.FileNotFoundException) {}
        val info = context.packageManager.resolveContentProvider("${context.packageName}.updates", 0)!!
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
    }
}
