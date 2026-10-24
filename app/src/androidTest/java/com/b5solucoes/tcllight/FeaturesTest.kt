package com.b5solucoes.tcllight

import android.content.Context
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeaturesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun onUi(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val until=SystemClock.uptimeMillis()+4000
        while(SystemClock.uptimeMillis()<until) { instrumentation.waitForIdleSync();if(condition())return;Thread.sleep(20) }
        fail("O recurso não atingiu o estado esperado")
    }
    private fun invalid(block: () -> Unit) { try { block();fail("O backup inválido foi aceito") } catch(_:BackupFailure){} }

    @Test fun testBackupRestoresOrganizationAppearanceAndUsageWithoutPrivateState() {
        val prefs=context.getSharedPreferences("backup-test",Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("wallpaper_dim",65).putInt("text_scale",120).putInt("safe_horizontal",72)
            .putInt(LauncherPalettes.PREF,2)
            .putBoolean("eco_mode",true).putStringSet("hidden_apps",setOf("sample.hidden"))
            .putString("order.grid",AppOrdering.encode(listOf("sample.two","sample.one")))
            .putString(HomeShortcuts.PREF,HomeShortcuts.encode(listOf(HomeShortcut("tv","Antena"),HomeShortcut("input:sample/.Tv/HW1","Videogame"))))
            .putInt("usage.count.sample.one",8).putLong("usage.time.sample.one",1234567890123L)
            .putString("updater.private","never-export").putString("tv_background_input","sample/.Tv/HW1").commit()
        val bytes=LauncherBackup.export(prefs,false)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("never-export"))
        assertFalse(bytes.toString(Charsets.UTF_8).contains("tv_background_input"))
        val data=LauncherBackup.parse(bytes)
        prefs.edit().putInt("wallpaper_dim",20).putString("order.grid","[]").putInt("usage.count.sample.one",1).commit()
        LauncherBackup.restore(prefs,data)
        assertEquals(65,prefs.getInt("wallpaper_dim",0));assertEquals(120,prefs.getInt("text_scale",0))
        assertEquals(2,prefs.getInt(LauncherPalettes.PREF,-1))
        assertEquals(listOf("sample.two","sample.one"),AppOrdering.decode(prefs.getString("order.grid",null)))
        assertEquals(8,prefs.getInt("usage.count.sample.one",0));assertEquals(1234567890123L,prefs.getLong("usage.time.sample.one",0))
        assertEquals("Antena",HomeShortcuts.decode(prefs.getString(HomeShortcuts.PREF,null))[0].name)
        assertEquals("never-export",prefs.getString("updater.private",null))
        assertFalse(prefs.contains("tv_background_input"))
        prefs.edit().clear().commit()
    }

    @Test fun testBackupRejectsUnknownKeysWrongTypesDuplicatesAndOversizedFilesBeforeRestoring() {
        val prefs=context.getSharedPreferences("backup-validation",0)
        prefs.edit().clear().putInt("wallpaper_dim",65).commit()
        val good=LauncherBackup.export(prefs,false).toString(Charsets.UTF_8)
        for ((key,value) in listOf("text_scale" to 300,LauncherPalettes.PREF to 3,"eco_mode" to "true","tv_background_input" to "unsafe",
            "updater.url" to "https://invalid.example", "hidden_apps" to JSONArray(listOf("sample.one","sample.one")))) {
            val root=JSONObject(good);root.getJSONObject("preferences").put(key,value)
            invalid { LauncherBackup.parse(root.toString().toByteArray()) }
            assertEquals(65,prefs.getInt("wallpaper_dim",0))
        }
        invalid { LauncherBackup.parse(JSONObject(good).put("schema",1.5).toString().toByteArray()) }
        invalid { LauncherBackup.read(java.io.ByteArrayInputStream(ByteArray(LauncherBackup.MAX_BYTES+1))) }
        prefs.edit().clear().commit()
    }

    @Test fun testBackupsMadeBeforeColorThemesRestoreToAuroraWithoutLosingPreferences() {
        val prefs=context.getSharedPreferences("backup-legacy-theme",0)
        prefs.edit().clear().putInt("wallpaper_dim",65).putBoolean("eco_mode",true).commit()
        val root=JSONObject(LauncherBackup.export(prefs,false).toString(Charsets.UTF_8))
        root.getJSONObject("preferences").remove(LauncherPalettes.PREF)
        val legacy=LauncherBackup.parse(root.toString().toByteArray())
        prefs.edit().putInt(LauncherPalettes.PREF,2).putInt("wallpaper_dim",20).commit()
        LauncherBackup.restore(prefs,legacy)
        assertEquals(0,prefs.getInt(LauncherPalettes.PREF,-1))
        assertEquals(65,prefs.getInt("wallpaper_dim",0));assertTrue(prefs.getBoolean("eco_mode",false))
        prefs.edit().clear().commit()
    }

    @Test fun testShortcutsOnlyDescribeNativeTvOrInputIdsAndKeepNamesInOrder() {
        val entries=listOf(HomeShortcut("tv","Antena"),HomeShortcut("input:sample/.Tv/HW1","Videogame"))
        assertEquals(entries,HomeShortcuts.decode(HomeShortcuts.encode(entries)))
        assertTrue(HomeShortcuts.decode("[]").isEmpty())
        assertEquals("tv",HomeShortcuts.decode(null).single().key)
        val source=TvSource("sample/.Tv/HW1","HDMI 1",true,TvInputManager.INPUT_STATE_CONNECTED,"sample")
        assertEquals(source,entries[1].source(listOf(source)))
        assertNull(entries[1].source(emptyList()))
        for (array in listOf(JSONArray().put(JSONObject().put("key","https://example.org")),
            JSONArray().put(JSONObject().put("key","tv")).put(JSONObject().put("key","tv")),
            JSONArray().put(JSONObject().put("key","tv").put("name","x".repeat(33))))) {
            try { HomeShortcuts.parse(array);fail("Atalho inválido aceito") } catch(_:IllegalArgumentException){}
        }
    }

    @Test fun testNetworkValidationDoesNotConfuseWifiWithInternet() {
        assertEquals("Sem conexão de rede",NetworkStatus.internetLabel(false,false,false))
        assertEquals("Internet disponível",NetworkStatus.internetLabel(true,true,false))
        assertEquals("A rede exige uma página de acesso",NetworkStatus.internetLabel(true,false,true))
        assertEquals("Internet não confirmada pelo Android",NetworkStatus.internetLabel(true,false,false))
        val ip=NetworkStatus("Wi-Fi","Internet disponível",listOf("192.0.2.4"),listOf("2001:db8::1"),true)
        assertEquals("192.0.2.4",ip.address)
    }

    @Test fun testBackgroundVideoReleasesItsSessionAndIgnoresLateCallbacks() {
        lateinit var controller:BackgroundTv
        lateinit var playing:()->Unit
        var reset=0;var visible=false;lateinit var root:FrameLayout
        onUi {
            root=FrameLayout(context)
            controller=BackgroundTv(root,Handler(Looper.getMainLooper()),{visible=it},{_,_->}, { ready,_ ->
                playing=ready
                object:BackgroundVideoBackend { override val view=View(context);override fun tune(inputId:String){};override fun reset(){reset++} }
            },500L)
            controller.start(TvSource("sample/.Tv/HW1","HDMI",true,0,"sample"),false)
        }
        assertEquals(BackgroundTvState.STARTING,controller.state)
        playing();await { controller.state==BackgroundTvState.PLAYING }
        assertTrue(visible)
        onUi { controller.stop() }
        assertEquals(1,reset);assertEquals(0,root.childCount);assertFalse(visible)
        playing();instrumentation.waitForIdleSync()
        assertEquals(BackgroundTvState.OFF,controller.state)
    }

    @Test fun testBackgroundVideoCannotRunInEcoAndFallsBackOnTimeoutOrFailure() {
        lateinit var controller:BackgroundTv;lateinit var root:FrameLayout
        var created=0;var reset=0;var visible=false
        onUi {
            root=FrameLayout(context)
            controller=BackgroundTv(root,Handler(Looper.getMainLooper()),{visible=it},{_,_->},{_,_->
                created++
                object:BackgroundVideoBackend {override val view=View(context);override fun tune(inputId:String){};override fun reset(){reset++}}
            },120L)
            controller.start(TvSource("sample/.Tv/HW1","HDMI",true,0,"sample"),true)
        }
        assertEquals(0,created);assertEquals(0,root.childCount)
        onUi { controller.start(TvSource("sample/.Tv/HW1","HDMI",true,0,"sample"),false) }
        await { controller.state==BackgroundTvState.UNAVAILABLE }
        assertEquals(1,created);assertEquals(1,reset);assertFalse(visible);assertEquals(0,root.childCount)
        onUi { controller.start(TvSource("sample/.Tv/Tuner","Antena",false,0,"sample"),false) }
        assertEquals(1,created)
    }
}
