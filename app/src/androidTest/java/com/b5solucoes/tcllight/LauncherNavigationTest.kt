package com.b5solucoes.tcllight

import android.app.Activity
import android.content.Context
import android.content.Intent
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
    }
}
