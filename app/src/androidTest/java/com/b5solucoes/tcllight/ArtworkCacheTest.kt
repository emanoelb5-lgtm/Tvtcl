package com.b5solucoes.tcllight

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ArtworkCacheTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val root=File(context.cacheDir,"artwork-contract-tests")
    private val main=Handler(Looper.getMainLooper())
    private val cleanup=ArrayList<()->Unit>()
    private fun ui(action:()->Unit)=instrumentation.runOnMainSync(action)
    private fun await(latch:CountDownLatch) { assertTrue("O carregamento não terminou",latch.await(8,TimeUnit.SECONDS)) }
    private fun bitmap(color:Int=Color.BLUE)=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    @After fun clean() { ui { cleanup.forEach { it() } };root.deleteRecursively() }

    @Test fun appRowsShareOneDecodeAndPersistentCacheSurvivesRecreationAndPackageRevision() {
        val calls=AtomicInteger();val component=ComponentName("sample.video","sample.video.Main")
        val gate=CountDownLatch(1);val finished=CountDownLatch(2)
        val first=AppArtwork(context,File(root,"apps")) {
            assertNotEquals("A decodificação não pode bloquear o controle",Looper.getMainLooper(),Looper.myLooper())
            calls.incrementAndGet();gate.await(4,TimeUnit.SECONDS);Artwork(bitmap(),true)
        }
        cleanup.add { first.close() }
        val images=ArrayList<Artwork>()
        ui {
            first.register(component,"v1")
            repeat(2) { first.load(component) { assertNotNull(it);images.add(it!!);finished.countDown() } }
        }
        gate.countDown();await(finished)
        assertEquals(1,calls.get());assertSame(images[0].bitmap,images[1].bitmap)
        ui {
            var immediate=false
            first.load(component) { assertSame(images[0].bitmap,it!!.bitmap);immediate=true }
            assertTrue("O cache em RAM deve atender sem esperar por worker",immediate)
            first.close()
        }
        val offline=AppArtwork(context,File(root,"apps")) { calls.incrementAndGet();null }
        cleanup.add { offline.close() };val restored=CountDownLatch(1)
        ui { offline.register(component,"v1");offline.load(component) { assertNotNull(it);assertTrue(it!!.banner);restored.countDown() } }
        await(restored);assertEquals("Recriar a Home deve reutilizar o thumbnail local",1,calls.get())
        val changed=CountDownLatch(1)
        ui { offline.register(component,"v2");offline.load(component) { assertNull("Uma versão nova não deve reutilizar o ícone antigo",it);changed.countDown() } }
        await(changed);assertEquals(2,calls.get())
    }

    @Test fun coversShareInFlightWorkAndRestoreFromDiskWithSourceOffline() {
        val calls=AtomicInteger();val gate=CountDownLatch(1)
        val first=MediaArtwork(context,main,File(root,"covers"),{
            assertNotEquals(Looper.getMainLooper(),Looper.myLooper());calls.incrementAndGet()
            gate.await(4,TimeUnit.SECONDS);bitmap(Color.GREEN)
        })
        cleanup.add { first.close() }
        val done=CountDownLatch(2);val uri="https://example.org/title/cover.webp"
        ui { repeat(2) { first.load(uri) { assertNotNull(it);done.countDown() } } }
        gate.countDown();await(done);assertEquals(1,calls.get())
        ui { assertNotNull(first.peek(uri));first.trim();assertNull(first.peek(uri));first.close() }
        val offline=MediaArtwork(context,main,File(root,"covers"),{ calls.incrementAndGet();null })
        cleanup.add { offline.close() };val restored=CountDownLatch(1)
        ui { offline.load(uri) { assertNotNull("A capa deve aparecer mesmo sem repetir o download",it);restored.countDown() } }
        await(restored);assertEquals(1,calls.get())
        ui {
            var immediate=false;offline.load(uri) { immediate=true };assertTrue(immediate)
        }
    }

    @Test fun cachedCoverDoesNotWaitBehindTwoSlowDownloads() {
        val entered=CountDownLatch(2);val release=CountDownLatch(1)
        val repository=MediaArtwork(context,main,File(root,"fast-disk"),{ uri ->
            if(uri.endsWith("slow")) { entered.countDown();release.await(6,TimeUnit.SECONDS) }
            bitmap()
        })
        cleanup.add { repository.close() }
        val warm=CountDownLatch(1)
        ui { repository.load("https://example.org/cached") { warm.countDown() } };await(warm)
        ui {
            repository.trim()
            repository.load("https://example.org/a/slow")
            repository.load("https://example.org/b/slow")
        }
        await(entered)
        val reused=CountDownLatch(1)
        ui { repository.load("https://example.org/cached") { assertNotNull(it);reused.countDown() } }
        try { assertTrue("A leitura do cache local não pode esperar pelos downloads",reused.await(2,TimeUnit.SECONDS)) }
        finally { release.countDown() }
    }

    @Test fun corruptExpiredAndOversizedThumbnailsFallBackWithoutCrashingAndDiskIsBounded() {
        var clock=100000L
        val folder=File(root,"bounded")
        val store=ThumbnailStore(folder,1024L*1024,2) { clock }
        val original=StoredThumbnail(bitmap(),true)
        store.write("old",original);clock++
        store.write("second",original);clock++
        assertNotNull(store.read("old"));clock++
        store.write("third",original)
        assertNotNull(store.read("old"));assertNull(store.read("second"));assertNotNull(store.read("third"))
        assertTrue(folder.listFiles()!!.sumOf { it.length() }<=1024*1024)
        assertTrue(folder.listFiles()!!.all { it.name.matches(Regex("[0-9a-f]{64}\\.thumb")) })
        clock+=10000
        assertNull("Uma imagem vencida precisa ser consultada na origem",store.read("old",100L))
        store.write("broken",original)
        folder.listFiles()!!.forEach { it.writeText("cache incompleto") }
        assertNull(store.read("broken"))
        val huge=Bitmap.createBitmap(900,400,Bitmap.Config.RGB_565)
        store.write("too-large",StoredThumbnail(huge));assertNull(store.read("too-large"));huge.recycle()
    }

    @Test fun cacheWriteFailureStillDeliversDecodedAppsAndCovers() {
        root.mkdirs();val blocked=File(root,"blocked").apply { writeText("arquivo") }
        val component=ComponentName("sample.video","sample.video.Main")
        val apps=AppArtwork(context,blocked) { Artwork(bitmap(),true) }
        val covers=MediaArtwork(context,main,blocked,{ bitmap() })
        cleanup.add { apps.close();covers.close() };val done=CountDownLatch(2)
        ui {
            apps.load(component) { assertNotNull(it);done.countDown() }
            covers.load("https://example.org/a.jpg") { assertNotNull(it);done.countDown() }
        }
        await(done)
    }

    @Test fun lastAppCatalogRestoresComponentsAndRevisionThenAcceptsInstallationChanges() {
        val file=File(root,"catalog.json");root.mkdirs()
        val cache=AppCatalogCache(context,file)
        val first=CachedTvApp("Vídeo",ComponentName("sample.video","sample.video.Main"),"100:3")
        val second=CachedTvApp("Música",ComponentName("sample.music","sample.music.Main"),"200:4")
        cache.write(listOf(first,second));assertEquals(listOf(first,second),AppCatalogCache(context,file).read())
        cache.write(listOf(second.copy(revision="300:5")))
        assertEquals(listOf(second.copy(revision="300:5")),cache.read())
        val data=org.json.JSONObject(file.readText()).put("configuration","outro idioma")
        file.writeText(data.toString());assertTrue(cache.read().isEmpty())
        file.writeText("incompleto");assertTrue(cache.read().isEmpty())
        assertFalse("O snapshot deve ser descartável",File(context.filesDir,"app-catalog-v1.json").exists())
    }
}
