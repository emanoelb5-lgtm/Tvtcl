package com.b5solucoes.tcllight

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File

/** A launchable user app verifies that non-streaming apps remain available. Test APK only. */
class FixtureActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply { text = "Arquivos de teste" })
    }
}

@RunWith(AndroidJUnit4::class)
class LauncherNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var screen: MainActivity
    private val fixture get() = instrumentation.context.packageName

    @Before fun setUp() {
        instrumentation.targetContext.getSharedPreferences("launcher",Context.MODE_PRIVATE)
            .edit().clear().commit()
        instrumentation.setInTouchMode(false)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { screen = it }
        await { find("grid:"+fixture) != null }
    }

    @After fun tearDown() { scenario.close() }

    private fun <T> onUi(block: () -> T): T {
        if(Looper.myLooper() == Looper.getMainLooper()) return block()
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }

    private fun find(tag: String): View? = onUi {
        screen.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>(tag)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis()+12000L
        while(SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if(condition()) return
            SystemClock.sleep(50L)
        }
        fail("A interface não atingiu o estado esperado")
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
    }

    private fun click(tag: String) {
        val target = find(tag)
        assertNotNull("Ação ausente: "+tag,target)
        onUi { target!!.requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private val samples get() = listOf("sample.files", "sample.video", "sample.music")
    private fun seedOrderCatalog() = onUi {
        val type = Class.forName("com.b5solucoes.tcllight.MainActivity\$TvApp")
        val constructor = type.getDeclaredConstructor(String::class.java, String::class.java, ComponentName::class.java)
            .apply { isAccessible = true }
        val apps = samples.mapIndexed { index, id ->
            constructor.newInstance("Aplicativo " + index, id, ComponentName(fixture, FixtureActivity::class.java.name))
        }
        MainActivity::class.java.getDeclaredField("mediaApps").apply { isAccessible = true }.set(screen, apps)
        MainActivity::class.java.getDeclaredMethod("renderApps").apply { isAccessible = true }.invoke(screen)
    }

    private fun laneOrder(lane: String): List<String> = onUi {
        val tags = ArrayList<String>()
        fun visit(view: View) {
            (view.tag as? String)?.takeIf { it.startsWith(lane + ":") }?.let { tags.add(it.substringAfter(':')) }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(screen.findViewById(android.R.id.content))
        tags
    }

    @Test fun testHeaderHasInformationWithoutFocusableBrandOrRepeatedAppNames() {
        assertNull(find("nav:home"))
        assertNotNull(find("nav:inputs"))
        assertFalse(find("header:weather")!!.isFocusable)
        val clock = find("header:time")!!
        val date = find("header:date")!!
        onUi {
            val a = IntArray(2); val b = IntArray(2)
            clock.getLocationOnScreen(a); date.getLocationOnScreen(b)
            assertTrue("Hora e data devem estar lado a lado", b[0] >= a[0] + clock.width)
            assertTrue(date.width > 0 && date.height > 0)
        }
        for (lane in listOf("top", "grid")) {
            val card = find(lane + ":" + fixture) as? ViewGroup ?: continue
            onUi {
                fun repeatedLabel(view: View): Boolean {
                    if (view is TextView && view.text.toString() == card.contentDescription.toString()) return true
                    return view is ViewGroup && (0 until view.childCount).any { repeatedLabel(view.getChildAt(it)) }
                }
                assertFalse("O nome continua aparecendo sob o cartão", repeatedLabel(card))
            }
        }
    }

    @Test fun testMoveBothLanesCanBeCancelledAndSavedAcrossRecreation() {
        seedOrderCatalog()
        val prefs = instrumentation.targetContext.getSharedPreferences("launcher", 0)
        for (lane in listOf("grid", "top")) {
            val id = samples.first()
            val original = laneOrder(lane)
            onUi { find(lane + ":" + id)!!.performLongClick() }
            click("app:right")
            await { laneOrder(lane).indexOf(id) == 1 }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals(2, laneOrder(lane).indexOf(id))
            key(KeyEvent.KEYCODE_BACK)
            assertEquals(original, laneOrder(lane))
            assertFalse(prefs.contains("order." + lane))
            onUi { find(lane + ":" + id)!!.performLongClick() }
            click("app:right")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            val saved = laneOrder(lane)
            assertEquals(saved, AppOrdering.decode(prefs.getString("order." + lane, null)))
            assertNull(find("launcher-panel"))
            scenario.recreate()
            scenario.onActivity { screen = it }
            await { find("grid:" + fixture) != null }
            seedOrderCatalog()
            await { laneOrder(lane) == saved }
        }
    }

    @Test fun testVideoInputsPanelDoesNotShowKeyboardOrMouseInventory() {
        key(KeyEvent.KEYCODE_MENU)
        click("action:inputs")
        await { find("inputs:native") != null }
        assertNull(find("devices:bluetooth"))
        val text = onUi {
            val labels = ArrayList<String>()
            fun visit(view: View) {
                if (view is TextView) labels.add(view.text.toString())
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            visit(find("launcher-panel")!!)
            labels.joinToString(" ").lowercase()
        }
        assertFalse(text.contains("entradas detectadas") || text.contains("sim-mouse") || text.contains("sim-qb"))
        key(KeyEvent.KEYCODE_BACK)
        await { find("action:inputs")?.hasFocus() == true }
    }

    @Test fun testPanelContainsRemoteFocusAndRestoresHomeFocus() {
        onUi { find("nav:central")!!.requestFocus() }
        key(KeyEvent.KEYCODE_MENU)
        await { find("action:wifi")?.hasFocus() == true }
        for(code in listOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_DOWN)) repeat(12) {
            key(code)
            assertTrue("O foco saiu do painel",onUi {
                val panel = find("launcher-panel") as ViewGroup
                panel.hasFocus()
            })
        }
        key(KeyEvent.KEYCODE_BACK)
        assertNull(find("launcher-panel"))
        assertTrue(find("nav:central")!!.hasFocus())
    }

    @Test fun testNestedCustomizationPersistsAndBackReturnsOneLevel() {
        key(KeyEvent.KEYCODE_MENU)
        click("action:customize")
        click("pref:size")
        click("choice:2")
        assertEquals(2,instrumentation.targetContext.getSharedPreferences("launcher",0).getInt("icon_scale",-1))
        await { find("pref:size")?.hasFocus() == true }
        key(KeyEvent.KEYCODE_BACK)
        await { find("action:customize")?.hasFocus() == true }
        key(KeyEvent.KEYCODE_BACK)
        assertNull(find("launcher-panel"))
    }

    @Test fun testUserFileManagerCanBeHiddenAndRestored() {
        assertNotNull(find("grid:"+fixture))
        assertNull(find("grid:com.android.settings"))
        onUi { find("grid:"+fixture)!!.performLongClick() }
        click("app:hide")
        await { find("grid:"+fixture) == null }
        key(KeyEvent.KEYCODE_MENU)
        click("action:hidden")
        click("hidden:"+fixture)
        key(KeyEvent.KEYCODE_MENU)
        await { find("grid:"+fixture) != null }
    }

    @Test fun testReturningFromExternalAppReusesGridViews() {
        val before = find("grid:"+fixture)
        onUi {
            screen.startActivity(Intent().setClassName(fixture,FixtureActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        await { onUi { !screen.hasWindowFocus() } }
        // The fixture runs as an external app; shell input returns across packages.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("input keyevent 4")
        ).use { it.readBytes() }
        instrumentation.waitForIdleSync()
        await { onUi { screen.hasWindowFocus() } }
        assertSame("A grade foi recriada sem alteração nos apps",before,find("grid:"+fixture))
    }

    @Test fun testEconomyModeAndRepeatedPanelOpenClose() {
        key(KeyEvent.KEYCODE_MENU)
        val prefs = instrumentation.targetContext.getSharedPreferences("launcher",0)
        val lowRam = (instrumentation.targetContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
        val previous = prefs.getBoolean("eco_mode",lowRam)
        click("action:eco")
        assertEquals(!previous,prefs.getBoolean("eco_mode",false))
        key(KeyEvent.KEYCODE_MENU)
        repeat(8) {
            key(KeyEvent.KEYCODE_MENU)
            await { find("action:wifi")?.hasFocus() == true }
            key(KeyEvent.KEYCODE_MENU)
            assertNull(find("launcher-panel"))
            assertNotNull(onUi { screen.currentFocus })
        }
    }

    @Test fun testZCaptureNativeTvScreens() {
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null),"qa").apply { mkdirs() }
        fun capture(name: String) {
            SystemClock.sleep(400L)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(bitmap)
            assertEquals("A TV deve usar 16:9",16f/9f,bitmap.width.toFloat()/bitmap.height,.02f)
            File(folder,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        capture("home.png")
        key(KeyEvent.KEYCODE_MENU)
        await { find("action:wifi")?.hasFocus() == true }
        capture("action-center.png")
        click("action:customize")
        capture("customization.png")
        key(KeyEvent.KEYCODE_BACK)
        click("action:inputs")
        capture("inputs.png")
        key(KeyEvent.KEYCODE_MENU)
        onUi { find("grid:" + fixture)!!.performLongClick() }
        capture("app-options.png")
    }
}
