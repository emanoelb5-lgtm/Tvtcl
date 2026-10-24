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
    private lateinit var uiUpdates:AppUpdates
    private var updateCode=BuildConfig.VERSION_CODE

    @Before fun setUp() {
        instrumentation.targetContext.getSharedPreferences("launcher",Context.MODE_PRIVATE)
            .edit().clear().commit()
        updateCode=BuildConfig.VERSION_CODE
        val updatePrefs=instrumentation.targetContext.getSharedPreferences("ui-test-updates",0)
        updatePrefs.edit().clear().commit()
        uiUpdates=AppUpdates(instrumentation.targetContext,object:UpdateTransport {
            override fun open(url:String):UpdateStream {
                val bytes=DiscoveryFixtures.feed(updateCode).toByteArray()
                return UpdateStream(java.io.ByteArrayInputStream(bytes),bytes.size.toLong())
            }
        },{InstalledUpdateApp(UpdatePolicy.PACKAGE,BuildConfig.VERSION_CODE.toLong(),UpdatePolicy.CERTIFICATE)},updatePrefs)
        AppUpdates::class.java.getDeclaredField("shared").apply { isAccessible=true }.set(null,uiUpdates)
        instrumentation.setInTouchMode(false)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { screen = it }
        await { find("grid:"+fixture) != null }
    }

    @After fun tearDown() {
        scenario.close();onUi { uiUpdates.close() }
        AppUpdates::class.java.getDeclaredField("shared").apply { isAccessible=true }.set(null,null)
    }

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

    private fun openMedia(tag:String) {
        if(find("launcher-panel")!=null) key(KeyEvent.KEYCODE_MENU)
        key(KeyEvent.KEYCODE_MENU);click("action:media");click(tag)
    }

    private fun fixtureSearch() {
        onUi {
            val hub=MainActivity::class.java.getDeclaredField("mediaHub").apply { isAccessible=true }.get(screen) as MediaHub
            val field=MediaHub::class.java.getDeclaredField("online").apply { isAccessible=true }
            (field.get(hub) as StreamingSearch).close()
            field.set(hub,StreamingSearch(object:CatalogTransport { override fun read(query:String)=DiscoveryFixtures.page(query) }))
        }
    }

    private val samples get() = listOf("sample.files", "sample.video", "sample.music")
    private fun seedOrderCatalog(ids: List<String> = samples) {
        // This scenario supplies a synthetic catalog; real package broadcasts must not replace it halfway through remote input.
        onUi {
            val registered=MainActivity::class.java.getDeclaredField("packageReceiverRegistered").apply { isAccessible=true }
            if(registered.getBoolean(screen)) {
                val receiver=MainActivity::class.java.getDeclaredField("packageEvents").apply { isAccessible=true }.get(screen) as android.content.BroadcastReceiver
                screen.unregisterReceiver(receiver);registered.setBoolean(screen,false)
            }
        }
        instrumentation.waitForIdleSync()
        await { onUi {
            fun flag(name: String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.getBoolean(screen)
            !flag("scanning") && !flag("scanAgain")
        } }
        onUi {
        MainActivity::class.java.getDeclaredField("appsDirty").apply { isAccessible=true }.setBoolean(screen,false)
        val type = Class.forName("com.b5solucoes.tcllight.MainActivity\$TvApp")
        val constructor = type.getDeclaredConstructor(String::class.java, String::class.java, ComponentName::class.java)
            .apply { isAccessible = true }
        val apps = ids.mapIndexed { index, id ->
            constructor.newInstance("Aplicativo " + index, id, ComponentName(fixture, FixtureActivity::class.java.name))
        }
        MainActivity::class.java.getDeclaredField("mediaApps").apply { isAccessible = true }.set(screen, apps)
        MainActivity::class.java.getDeclaredMethod("renderApps").apply { isAccessible = true }.invoke(screen)
        }
        await { onUi { find("grid:"+ids.first())?.let { it.isLaidOut && it.height>0 }==true } }
    }

    @Test fun testScrolledCardsStayInsideViewportAtEveryCardSize() {
        val ids = (0 until 42).map { "sample.app.$it" }
        val header = find("home:header")!!
        val original = android.graphics.Rect()
        onUi { header.getGlobalVisibleRect(original) }
        for (size in 0..2) {
            instrumentation.targetContext.getSharedPreferences("launcher", 0).edit()
                .putInt("icon_scale", size).commit()
            seedOrderCatalog(ids)
            await { onUi { find("grid:"+ids.first())?.let { it.isLaidOut && it.height>0 } == true } }
            onUi { find("grid:" + ids.first())!!.requestFocus() }
            repeat(18) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            SystemClock.sleep(350L)
            onUi {
                val viewport = find("home:viewport") as android.widget.ScrollView
                assertTrue("O cenário deve realmente rolar", viewport.scrollY > 0)
                val headerAfter = android.graphics.Rect()
                header.getGlobalVisibleRect(headerAfter)
                assertEquals("O cabeçalho deve permanecer fixo", original, headerAfter)
                val location = IntArray(2)
                viewport.getLocationOnScreen(location)
                var visible = 0
                for (id in ids) {
                    val card = find("grid:" + id) ?: continue
                    val rect = android.graphics.Rect()
                    if (card.getGlobalVisibleRect(rect)) {
                        visible++
                        assertTrue("Um cartão invadiu a barra superior", rect.top >= location[1] + viewport.paddingTop)
                        assertTrue("Um cartão invadiu o rodapé", rect.bottom <= location[1] + viewport.height - viewport.paddingBottom)
                    }
                }
                assertTrue("Nenhum cartão visível: size=$size focus=${screen.currentFocus?.tag} scroll=${viewport.scrollY} child=${viewport.getChildAt(0).height} viewport=${viewport.height}",visible > 0)
                assertTrue(screen.currentFocus?.tag.toString().startsWith("grid:"))
                val selected = screen.currentFocus!!
                val selectedRect = android.graphics.Rect()
                assertTrue(selected.getGlobalVisibleRect(selectedRect))
                assertTrue("O cartão em foco deve aparecer inteiro", selectedRect.height() >= selected.height)
            }
        }
    }

    @Test fun testPlayStoreIsAllowedWithoutExposingTechnicalSystemApps() {
        onUi {
            val policy = MainActivity::class.java.getDeclaredMethod("isVisibleSystemApp", String::class.java,
                String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
            assertEquals(true, policy.invoke(screen, "com.android.vending", "Google Play Store", -1))
            assertEquals(false, policy.invoke(screen, "com.android.settings", "Configurações", -1))
            assertEquals(false, policy.invoke(screen, "com.google.android.packageinstaller", "Instalador", -1))
        }
        val manager = instrumentation.targetContext.packageManager
        if (manager.getLeanbackLaunchIntentForPackage("com.android.vending") != null ||
            manager.getLaunchIntentForPackage("com.android.vending") != null) {
            assertNotNull("A loja instalada deve aparecer na grade", find("grid:com.android.vending"))
            key(KeyEvent.KEYCODE_MENU)
            assertNotNull(find("action:store"))
        }
    }

    @Test fun testUpdatesPanelUsesRemoteAndReturnsToItsAction() {
        key(KeyEvent.KEYCODE_MENU)
        click("action:update")
        await { find("update:check")?.hasFocus() == true }
        assertNotNull(find("update:status"))
        key(KeyEvent.KEYCODE_BACK)
        await { find("action:update")?.hasFocus() == true }
        key(KeyEvent.KEYCODE_BACK)
        assertNull(find("launcher-panel"))
    }

    @Test fun testAutomaticUpdateBadgeAppearsPersistsAndLeavesNavigationWhenCurrent() {
        updateCode=BuildConfig.VERSION_CODE+1
        onUi { uiUpdates.check() }
        await { find("nav:update")?.visibility==View.VISIBLE }
        click("nav:update");assertNotNull(find("update:download"))
        key(KeyEvent.KEYCODE_MENU);assertTrue(find("nav:update")!!.hasFocus())
        assertTrue(find("nav:update")!!.contentDescription.toString().contains("0.99.0"))
        updateCode=BuildConfig.VERSION_CODE;onUi { uiUpdates.check() }
        await { find("nav:update")?.visibility==View.GONE }
        assertTrue(find("nav:search")!!.hasFocus())
        repeat(8) { key(KeyEvent.KEYCODE_DPAD_RIGHT);assertFalse(find("nav:update")!!.hasFocus()) }
    }

    @Test fun testRemoteKeyboardFindsServicesAndOpensTheExactExternalTitleLink() {
        fixtureSearch();click("nav:search")
        await { find("search-key:A")?.hasFocus()==true }
        for(ch in "MATRIX") click("search-key:$ch")
        click("search-key:space");click("search-key:2");click("search-key:backspace");click("search-key:backspace")
        assertEquals("MATRIX",onUi { (find("media-search:query") as android.widget.EditText).text.toString() })
        click("media-search:submit")
        await { find("stream-title:tm1")!=null }
        onUi {
            val card=find("stream-title:tm1") as ViewGroup;val cover=card.getChildAt(0)
            assertEquals(16f/9f,cover.width.toFloat()/cover.height,.02f)
        }
        click("stream-title:tm1");assertNotNull(find("stream-offer:0"));assertNotNull(find("stream-offer:1"))
        click("stream-offer:0")
        await { nativeNode { it.packageName?.toString()==fixture && it.text?.toString()=="Conteúdo: https://stream.example.org/title/matrix" }!=null }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 4")).use { it.readBytes() }
        await { onUi { screen.hasWindowFocus() } }
        key(KeyEvent.KEYCODE_BACK);await { find("stream-title:tm1")?.hasFocus()==true }
        key(KeyEvent.KEYCODE_BACK);assertNull(find("launcher-panel"));assertTrue(find("nav:search")!!.hasFocus())
        assertNull(find("shortcut:tv"));assertTrue(find("nav:tv")!!.height<=onUi { screen.resources.displayMetrics.density*39 })
    }

    @Test fun testKeyboardAndResultsRemainUsableWithLargeTextAndMaximumSafeMargins() {
        val prefs=instrumentation.targetContext.getSharedPreferences("launcher",0)
        prefs.edit().putInt("text_scale",120).putInt("safe_horizontal",72).putInt("safe_vertical",24).commit()
        scenario.recreate();scenario.onActivity { screen=it };await { find("nav:search")!=null }
        fixtureSearch();click("nav:search")
        onUi { (find("media-search:query") as android.widget.EditText).setText("MATRIX") }
        click("media-search:submit");await { find("stream-title:tm1")!=null }
        for(tag in listOf("search-key:A","search-key:9","media-search:submit")) onUi {
            val view=find(tag)!!;val rect=android.graphics.Rect();assertTrue(view.getGlobalVisibleRect(rect));assertEquals(view.height,rect.height())
        }
        click("search-key:clear");assertEquals("",onUi { (find("media-search:query") as android.widget.EditText).text.toString() })
        key(KeyEvent.KEYCODE_BACK);assertTrue(find("nav:search")!!.hasFocus())
    }

    @Test fun testTvShortcutsCanBePinnedMovedAndRemovedByRemote() {
        val id="sample.tv/.Input/HW1"
        onUi {
            MainActivity::class.java.getDeclaredField("videoInputs").apply { isAccessible=true }
                .set(screen,listOf(TvSource(id,"HDMI 1",true,0,"sample.tv")))
        }
        key(KeyEvent.KEYCODE_MENU);click("action:shortcuts")
        click("shortcut-option:input:"+id)
        key(KeyEvent.KEYCODE_MENU)
        assertEquals(View.VISIBLE,find("nav:tv")!!.visibility);assertNotNull(find("shortcut:input:"+id))
        onUi { find("shortcut:input:"+id)!!.performLongClick() }
        click("shortcut:move:-1")
        val prefs=instrumentation.targetContext.getSharedPreferences("launcher",0)
        assertEquals("input:"+id,HomeShortcuts.decode(prefs.getString(HomeShortcuts.PREF,null)).first().key)
        click("shortcut:remove")
        assertNull(find("shortcut:input:"+id))
        onUi { find("nav:tv")!!.performLongClick() };click("shortcut:remove")
        assertEquals(View.GONE,find("nav:tv")!!.visibility)
        assertTrue(HomeShortcuts.decode(prefs.getString(HomeShortcuts.PREF,null)).isEmpty())
    }

    @Test fun testNetworkSheetShowsTheSystemAddressAndRestoresFocus() {
        key(KeyEvent.KEYCODE_MENU);click("action:wifi")
        val actual=NetworkStatus.read(instrumentation.targetContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
        assertTrue((find("network:address") as TextView).text.toString().contains(actual.address))
        assertTrue((find("network:status") as TextView).text.isNotBlank())
        key(KeyEvent.KEYCODE_BACK)
        await { find("action:wifi")?.hasFocus()==true }
    }

    @Test fun testLocalBackupCanBeReviewedAndRestoredAcrossRecreation() {
        val prefs=instrumentation.targetContext.getSharedPreferences("launcher",0)
        prefs.edit().putInt("wallpaper_dim",65).putString("order.grid",AppOrdering.encode(listOf(fixture))).commit()
        key(KeyEvent.KEYCODE_MENU);click("action:backup");click("backup:save")
        await { (find("backup:status") as? TextView)?.text.toString().contains("Cópia salva") }
        prefs.edit().putInt("wallpaper_dim",20).putString("order.grid","[]").commit()
        click("backup:local")
        await { find("backup:confirm") != null }
        assertEquals(20,prefs.getInt("wallpaper_dim",0))
        click("backup:confirm")
        await {
            scenario.onActivity { screen=it }
            (find("backup:status") as? TextView)?.text.toString().contains("Backup restaurado")
        }
        assertEquals(65,prefs.getInt("wallpaper_dim",0))
        assertEquals(listOf(fixture),AppOrdering.decode(prefs.getString("order.grid",null)))
        assertNull(find("home:tv-background"))
    }

    @Test fun testLargeTextAndMaximumSafeMarginsKeepHeaderAndFocusedCardInside() {
        key(KeyEvent.KEYCODE_MENU);click("action:customize");click("pref:display")
        for ((key,value) in listOf("text_scale" to 120,"safe_horizontal" to 72,"safe_vertical" to 24)) {
            click("display:"+key);click("display-choice:"+value)
            await { scenario.onActivity { screen=it };find("display:"+key)?.hasFocus()==true }
        }
        key(KeyEvent.KEYCODE_MENU)
        val ids=(0 until 42).map { "sample.large.$it" };seedOrderCatalog(ids)
        onUi { find("grid:"+ids.first())!!.requestFocus() }
        repeat(18) { key(KeyEvent.KEYCODE_DPAD_DOWN) };SystemClock.sleep(400)
        onUi {
            val header=android.graphics.Rect();find("home:header")!!.getGlobalVisibleRect(header)
            val date=android.graphics.Rect();find("header:date")!!.getGlobalVisibleRect(date)
            val network=android.graphics.Rect();find("nav:wifi")!!.getGlobalVisibleRect(network)
            assertTrue(date.right<=network.left)
            val focused=screen.currentFocus!!;val rect=android.graphics.Rect();assertTrue(focused.getGlobalVisibleRect(rect))
            assertTrue(rect.top>=header.bottom);assertTrue(rect.height()>=focused.height)
            assertTrue((find("header:time") as TextView).textSize>26f*screen.resources.displayMetrics.scaledDensity)
        }
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

    @Test fun testDefaultHomeShowsAWholeGridCardBeforeScrolling() {
        seedOrderCatalog((0 until 20).map { "sample.visible.$it" })
        onUi { (find("home:viewport") as android.widget.ScrollView).scrollTo(0,0) }
        instrumentation.waitForIdleSync()
        onUi {
            val card=find("grid:sample.visible.0")!!
            val rect=android.graphics.Rect();val viewport=android.graphics.Rect()
            assertTrue(card.getGlobalVisibleRect(rect));find("home:viewport")!!.getGlobalVisibleRect(viewport)
            assertEquals("O primeiro cartão deve aparecer inteiro sem rolar",card.height,rect.height())
            assertTrue(rect.top>=viewport.top && rect.bottom<=viewport.bottom)
        }
    }

    @Test fun testThemesRecreateTheVisibleInterfaceAndRetainFocusAndSettingsAtLargeText() {
        val prefs=instrumentation.targetContext.getSharedPreferences("launcher",0)
        prefs.edit().putInt("text_scale",120).putInt("safe_horizontal",72).putInt("safe_vertical",24)
            .putInt("wallpaper_mode",5).putBoolean("eco_mode",true)
            .putString("order.grid",AppOrdering.encode(listOf(fixture))).commit()
        scenario.recreate();scenario.onActivity { screen=it }
        await { find("grid:"+fixture)!=null }
        var previous=onUi { (find("home:header")!!.background as android.graphics.drawable.GradientDrawable).colors!!.toList() }
        key(KeyEvent.KEYCODE_MENU);click("action:customize")
        for(index in listOf(1,2,0)) {
            click("pref:theme");click("theme:$index")
            await { scenario.onActivity { screen=it };find("pref:theme")?.hasFocus()==true }
            assertEquals(index,prefs.getInt(LauncherPalettes.PREF,-1))
            val colors=onUi { (find("home:header")!!.background as android.graphics.drawable.GradientDrawable).colors!!.toList() }
            assertNotEquals("O tema precisa recolorir a interface após recriar",previous,colors);previous=colors
            assertEquals(120,prefs.getInt("text_scale",0));assertEquals(72,prefs.getInt("safe_horizontal",0))
            assertEquals(5,prefs.getInt("wallpaper_mode",0));assertTrue(prefs.getBoolean("eco_mode",false))
            assertEquals(listOf(fixture),AppOrdering.decode(prefs.getString("order.grid",null)))
            onUi {
                val focused=screen.currentFocus!!;val rect=android.graphics.Rect()
                assertTrue(focused.getGlobalVisibleRect(rect));assertEquals(focused.height,rect.height())
            }
        }
        key(KeyEvent.KEYCODE_BACK);assertTrue(find("action:customize")!!.hasFocus())
        key(KeyEvent.KEYCODE_MENU);assertNull(find("launcher-panel"))
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
            await { find(lane + ":" + id)?.hasFocus() == true }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertEquals(2, laneOrder(lane).indexOf(id))
            repeat(8) { key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            await { onUi {
                val bindings = MainActivity::class.java.getDeclaredField("artworkBindings")
                    .apply { isAccessible = true }.get(screen) as List<*>
                bindings.size <= samples.size * 2
            } }
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

    @Test fun testPinStillHasPriorityAfterManualOrdering() {
        val prefs = instrumentation.targetContext.getSharedPreferences("launcher", 0)
        prefs.edit().putString("order.top", AppOrdering.encode(samples.reversed())).commit()
        seedOrderCatalog()
        assertEquals(samples.reversed(), laneOrder("top"))
        onUi { find("grid:" + samples.first())!!.performLongClick() }
        click("app:pin")
        await { laneOrder("top").firstOrNull() == samples.first() }
        assertEquals(samples, laneOrder("grid"))
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

    private fun nativeNode(predicate: (android.view.accessibility.AccessibilityNodeInfo)->Boolean): android.view.accessibility.AccessibilityNodeInfo? {
        fun search(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if(node == null) return null
            if(predicate(node)) return node
            for(i in 0 until node.childCount) search(node.getChild(i))?.let { return it }
            return null
        }
        return search(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun enterNativeText(text: String,button: String) {
        await { nativeNode { it.className?.toString()=="android.widget.EditText" } != null }
        val input=nativeNode { it.className?.toString()=="android.widget.EditText" }!!
        assertTrue(input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text)
        }))
        await { nativeNode { it.text?.toString()?.equals(button,true)==true && it.isClickable } != null }
        assertTrue(nativeNode { it.text?.toString()?.equals(button,true)==true && it.isClickable }!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    @Test fun testMultimediaListCanBeCreatedReorderedRemovedAndRestoredAfterRecreation() {
        openMedia("media:list");click("media-list:add")
        enterNativeText("Documentário da Amazônia","Salvar")
        val prefs=instrumentation.targetContext.getSharedPreferences("launcher",0)
        await { MediaLibrary.decode(prefs.getString(MediaLibrary.PREF,null)).size==1 }
        val first=MediaLibrary.decode(prefs.getString(MediaLibrary.PREF,null)).single()
        click("media-list:"+first.id)
        click("media-entry:rename");enterNativeText("Amazônia","Confirmar")
        key(KeyEvent.KEYCODE_BACK)
        val second=MediaEntry(title="Série da semana",kind="series")
        onUi { prefs.edit().putString(MediaLibrary.PREF,MediaLibrary.encode(MediaLibrary.decode(prefs.getString(MediaLibrary.PREF,null))+second)).commit() }
        key(KeyEvent.KEYCODE_MENU)
        openMedia("media:list");click("media-list:"+first.id);click("media-entry:right")
        assertEquals(second.id,MediaLibrary.decode(prefs.getString(MediaLibrary.PREF,null)).first().id)
        scenario.recreate();scenario.onActivity { screen=it }
        await { find("nav:search") != null }
        openMedia("media:list");click("media-list:"+first.id);click("media-entry:remove")
        assertEquals(listOf(second),MediaLibrary.decode(prefs.getString(MediaLibrary.PREF,null)))
    }

    @Test fun testStreamingSearchDeliversTheQueryToAnExternalApp() {
        fixtureSearch();click("nav:search")
        onUi { (find("media-search:query") as android.widget.EditText).setText("Amazônia") }
        click("media-search:apps")
        click("media-search:app:"+fixture)
        await { nativeNode { it.text?.toString()=="Busca: Amazônia" && it.packageName?.toString()==fixture } != null }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 4")).use { it.readBytes() }
        await { onUi { screen.hasWindowFocus() } }
    }

    @Test fun testGuideFallbackAndOptionalPlaybackRemainUsableWithoutSpecialAccess() {
        openMedia("media:guide")
        await { find("media-guide:native") != null && find("media-guide:manual") != null }
        click("media-guide:manual")
        await { find("media-reminders:manual") != null }
        key(KeyEvent.KEYCODE_BACK);key(KeyEvent.KEYCODE_BACK)
        click("media:now")
        assertNotNull(find("media-now:access"));assertNull(find("media-now:toggle"))
        assertFalse(instrumentation.targetContext.getSharedPreferences("launcher",0).getBoolean("media.playback",false))
        key(KeyEvent.KEYCODE_BACK)
        await { find("media:now")?.hasFocus()==true }
    }

    @Test fun testMultimediaHomeCanBeDisabledWithoutBreakingRemoteNavigation() {
        val entry=MediaEntry(title="Filme da minha lista")
        onUi { instrumentation.targetContext.getSharedPreferences("launcher",0).edit().putString(MediaLibrary.PREF,MediaLibrary.encode(listOf(entry))).commit() }
        await { find("media-home:Minha lista:"+entry.id)!=null }
        key(KeyEvent.KEYCODE_MENU);click("action:media");click("media:settings")
        click("media-settings:media.home")
        assertNull(find("media-home:Minha lista:"+entry.id))
        key(KeyEvent.KEYCODE_MENU)
        onUi { find("top:"+fixture)!!.requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await { find("grid:"+fixture)?.hasFocus()==true }
        key(KeyEvent.KEYCODE_DPAD_UP)
        await { find("top:"+fixture)?.hasFocus()==true }
    }

    @Test fun testContinueWatchingKeepsItsContentCardsAndMultimediaUsesCoverCards() {
        val entry=MediaEntry(title="Documentário de teste")
        await { onUi {
            val hub=MainActivity::class.java.getDeclaredField("mediaHub").apply { isAccessible=true }.get(screen)
            !MediaHub::class.java.getDeclaredField("busyWatch").apply { isAccessible=true }.getBoolean(hub)
        } }
        onUi {
            val hub=MainActivity::class.java.getDeclaredField("mediaHub").apply { isAccessible=true }.get(screen) as MediaHub
            MediaHub::class.java.getDeclaredField("watch").apply { isAccessible=true }.set(hub,WatchCatalog(listOf(WatchItem(entry,600,1200,1))))
            hub.renderHome()
        }
        await { find("media-home:Continuar assistindo:"+entry.id)!=null }
        assertTrue(find("media-home:Continuar assistindo:"+entry.id)!!.contentDescription.toString().contains("50% assistido"))
        assertNull(find("media-home:search"));assertNull(find("shortcut:tv"))
        openMedia("media:settings");key(KeyEvent.KEYCODE_BACK)
        onUi {
            val card=find("media:search") as ViewGroup;val cover=card.getChildAt(0)
            assertEquals(16f/9f,cover.width.toFloat()/cover.height,.02f)
        }
    }

    @Test fun testReminderDetailsCanOpenFromHomeAndBeCancelledByRemote() {
        val reminder=MediaReminders.add(instrumentation.targetContext,MediaEntry(title="Jornal da noite"),System.currentTimeMillis()+3600000L)
        try {
            key(KeyEvent.KEYCODE_MENU);click("action:media");click("media:reminders")
            click("media-reminder:"+reminder.id)
            assertNotNull(find("media-reminder:open"))
            click("media-reminder:remove")
            assertFalse(MediaReminders.read(instrumentation.targetContext).any { it.id==reminder.id })
            assertNull(find("media-reminder:"+reminder.id))
        } finally { MediaReminders.remove(instrumentation.targetContext,reminder.id) }
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
        click("action:wifi");capture("network.png");key(KeyEvent.KEYCODE_BACK)
        click("action:shortcuts");capture("shortcuts.png");key(KeyEvent.KEYCODE_BACK)
        click("action:backup");capture("backup.png");key(KeyEvent.KEYCODE_BACK)
        click("action:media");capture("media-hub.png")
        click("media:list");capture("media-list.png");key(KeyEvent.KEYCODE_BACK)
        click("media:search");fixtureSearch();capture("media-search.png")
        onUi { (find("media-search:query") as android.widget.EditText).setText("MATRIX") }
        click("media-search:submit");await { find("stream-title:tm1")!=null };capture("search-results.png")
        click("stream-title:tm1");capture("search-offers.png");key(KeyEvent.KEYCODE_BACK);key(KeyEvent.KEYCODE_BACK)
        click("media:guide");capture("media-guide.png");key(KeyEvent.KEYCODE_BACK)
        click("media:watch");capture("media-watch.png");key(KeyEvent.KEYCODE_BACK)
        click("media:reminders");capture("media-reminders.png");key(KeyEvent.KEYCODE_BACK)
        click("media:now");capture("media-now.png");key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_BACK)
        click("action:update")
        capture("updates.png")
        key(KeyEvent.KEYCODE_BACK)
        click("action:customize")
        click("pref:display");capture("display.png");key(KeyEvent.KEYCODE_BACK)
        click("pref:tv-background");capture("background-experimental.png")
        assertNull(find("home:tv-background"));key(KeyEvent.KEYCODE_BACK)
        capture("customization.png")
        key(KeyEvent.KEYCODE_BACK)
        click("action:inputs")
        capture("inputs.png")
        key(KeyEvent.KEYCODE_MENU)
        onUi { find("grid:" + fixture)!!.performLongClick() }
        capture("app-options.png")
        key(KeyEvent.KEYCODE_MENU)
        val many = (0 until 42).map { "sample.scroll.$it" }
        seedOrderCatalog(many)
        onUi { find("grid:" + many.first())!!.requestFocus() }
        repeat(16) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        capture("home-scrolled.png")
        key(KeyEvent.KEYCODE_MENU);click("action:customize");click("pref:theme");capture("themes.png")
        for((index,name) in listOf(1 to "safira",2 to "ambar",0 to "aurora")) {
            click("theme:$index")
            await { scenario.onActivity { screen=it };find("pref:theme")?.hasFocus()==true }
            capture("personalizar-$name.png")
            key(KeyEvent.KEYCODE_MENU)
            onUi { (find("home:viewport") as android.widget.ScrollView).scrollTo(0,0) }
            capture("home-$name.png")
            key(KeyEvent.KEYCODE_MENU);capture("central-$name.png");click("action:customize");click("pref:theme")
        }
    }
}
