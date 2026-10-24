package com.b5solucoes.tcllight

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.MatrixCursor
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaIntegrationTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val fixture get()=instrumentation.context.packageName
    private fun invalid(block:()->Unit) { try { block();fail("Dado multimídia inválido aceito") } catch(_:IllegalArgumentException) { } }
    private fun await(condition:()->Boolean) {
        val until=SystemClock.uptimeMillis()+12000L
        while(SystemClock.uptimeMillis()<until) { instrumentation.waitForIdleSync();if(condition()) return;Thread.sleep(50) }
        fail("A integração multimídia não atingiu o estado esperado")
    }
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test fun listRoundTripReordersAndRejectsDuplicatesOversizedOrUnsafeEntries() {
        val one=MediaEntry(title="Filme",packageName=fixture,link="https://example.com/filme")
        val two=MediaEntry(title="Série",kind="series")
        val entries=MediaLibrary.add(MediaLibrary.add(emptyList(),one),two)
        assertEquals(entries,MediaLibrary.decode(MediaLibrary.encode(entries)))
        assertEquals(2,MediaLibrary.add(entries,one.copy(id=java.util.UUID.randomUUID().toString())).size)
        assertEquals(listOf(two,one),MediaLibrary.move(entries,one.id,1))
        assertEquals(entries,MediaLibrary.move(entries,one.id,-1))
        invalid { MediaLibrary.parse(JSONArray().put(one.json()).put(one.json())) }
        invalid { MediaLibrary.parseEntry(one.json().put("title",4)) }
        invalid { MediaLibrary.parseEntry(one.json().put("kind","command")) }
        invalid { MediaLibrary.parseEntry(one.json().put("link","file:///data/local/tmp/file")) }
        invalid { MediaLibrary.encode((0..80).map { MediaEntry(title="Item $it") }) }
    }

    @Test fun providerIntentsStayInsideOwnerAndCannotGrantFilesOrLaunchSettings() {
        val source=Intent(Intent.ACTION_VIEW,Uri.parse("https://example.com/movie/4")).setPackage(fixture)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .putExtra("contentId","4").putExtra("android.intent.extra.STREAM","file:///private")
        val parsed=MediaLinks.parse(source.toUri(Intent.URI_INTENT_SCHEME),fixture)!!
        assertEquals(fixture,parsed.`package`);assertEquals("4",parsed.getStringExtra("contentId"))
        assertEquals(0,parsed.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        assertFalse(parsed.hasExtra("android.intent.extra.STREAM"))
        assertNull(MediaLinks.parse(Intent("android.settings.SETTINGS").toUri(Intent.URI_INTENT_SCHEME),fixture))
        assertNull(MediaLinks.parse(Intent(Intent.ACTION_VIEW,Uri.parse("https://example.com")).setComponent(ComponentName("other.app","other.app.Main")).toUri(Intent.URI_INTENT_SCHEME),fixture))
        assertNull(MediaLinks.parse("javascript:alert(1)",fixture))
        assertNull(MediaLinks.parse("content://private.provider/secret",fixture))
        assertNotNull(MediaLinks.parse("content://android.media.tv/channel/105",""))
        assertNull(MediaLinks.parse("content://android.media.tv/channel/105?permission=all",""))
        assertNull(MediaLinks.coverUri("file:///data/local/tmp/image.png"))
    }

    @Test fun watchNextReadsSharedContentAndKeepsBoundedProgress() {
        val reader=TvMediaCatalog(context) { _,columns,_,_,_ -> MatrixCursor(columns).apply {
            addRow(arrayOf(1,fixture,"Filme","https://example.com/watch/1","",900000L,1800000L,123L,"",0))
            addRow(arrayOf(2,fixture,"Perigoso","file:///private","",0,0,124L,"",0))
            addRow(arrayOf(3,fixture,"Só lista","https://example.com/watch/3","",0,0,125L,"",3))
        } }
        val result=reader.watch()
        assertEquals(1,result.items.size);assertEquals(50,result.items.single().percent)
        assertEquals(fixture,result.items.single().entry.packageName)
        assertEquals(100,result.items.single().copy(position=Long.MAX_VALUE).percent)
        assertEquals(0,result.items.single().copy(duration=0).percent)
        val denied=TvMediaCatalog(context) { _,_,_,_,_->throw SecurityException() }.watch()
        assertTrue(denied.items.isEmpty());assertTrue(denied.message.contains("restringiu"))
    }

    @Test fun guidePreservesChannelsWhenFirmwareProtectsTheSchedule() {
        val now=System.currentTimeMillis()
        val reader=TvMediaCatalog(context) { uri,columns,_,_,_->
            MatrixCursor(columns).apply {
                if(uri.path=="/channel") {
                    addRow(arrayOf(2,"105","Canal online",1,"TYPE_OTHER"))
                    addRow(arrayOf(1,"5","Canal local",1,"TYPE_DVB_T"))
                    addRow(arrayOf(3,"1","Oculto",0,"TYPE_DVB_T"))
                } else {
                    addRow(arrayOf(10,1,"Jornal",now-60000,now+60000))
                    addRow(arrayOf(11,1,"Filme",now+60000,now+3600000))
                }
            }
        }
        val guide=reader.guide(now)
        assertEquals(listOf("5","105"),guide.channels.map { it.number })
        assertEquals("Jornal",guide.channels[0].now(now)?.title);assertEquals("Filme",guide.channels[0].next(now)?.title)
        assertEquals("content://android.media.tv/channel/2",guide.channels[1].entry().link)
        val partial=TvMediaCatalog(context) { uri,columns,_,_,_->
            if(uri.path=="/program") throw SecurityException()
            MatrixCursor(columns).apply { addRow(arrayOf(2,"105","Canal online",1,"TYPE_OTHER")) }
        }.guide(now)
        assertEquals(1,partial.channels.size);assertTrue(partial.message.contains("restringiu"))
    }

    @Test fun backupIncludesPersonalListAndStillAcceptsPreviousSchemaWithoutMedia() {
        val prefs=context.getSharedPreferences("media-backup-test",0)
        val entry=MediaEntry(title="Documentário",kind="movie")
        prefs.edit().clear().putString(MediaLibrary.PREF,MediaLibrary.encode(listOf(entry))).putBoolean("media.home",false)
            .putBoolean("media.playback",true).commit()
        val bytes=LauncherBackup.export(prefs,false)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("media.playback"))
        val parsed=LauncherBackup.parse(bytes)
        prefs.edit().putString(MediaLibrary.PREF,"[]").commit();LauncherBackup.restore(prefs,parsed)
        assertEquals(entry,MediaLibrary.decode(prefs.getString(MediaLibrary.PREF,null)).single())
        assertFalse(prefs.getBoolean("media.home",true));assertFalse(prefs.contains("media.playback"))
        val old=JSONObject(bytes.toString(Charsets.UTF_8));val values=old.getJSONObject("preferences")
        listOf(MediaLibrary.PREF,"media.home","media.continue","media.list","media.services").forEach { values.remove(it) }
        LauncherBackup.restore(prefs,LauncherBackup.parse(old.toString().toByteArray()))
        assertTrue(MediaLibrary.decode(prefs.getString(MediaLibrary.PREF,null)).isEmpty())
        prefs.edit().clear().commit()
    }

    @Test fun explicitSearchRoutesToTheSelectedApplicationOnly() {
        val request=MediaLinks.search(context,fixture,"Documentário amazônico")!!
        assertEquals(fixture,request.`package`);assertEquals(Intent.ACTION_SEARCH,request.action)
        assertEquals("Documentário amazônico",request.getStringExtra(SearchManager.QUERY))
        assertNull(MediaLinks.search(context,"missing.streaming.app","Filme"))
        assertNull(MediaLinks.search(context,fixture,""))
    }

    @Test fun actualTvProviderSharesOtherAppProgramsAndWatchNextWithGrantedListingsPermission() {
        // Permission is granted only in this disposable test device; production requests it through Android's UI.
        shell("pm grant ${context.packageName} android.permission.READ_TV_LISTINGS")
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,context.checkSelfPermission("android.permission.READ_TV_LISTINGS"))
        val component=ComponentName(fixture,MediaFixtureActivity::class.java.name)
        context.startActivity(Intent().setComponent(component).putExtra("publish_catalog",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val reader=TvMediaCatalog(context)
            await { reader.watch().items.any { it.entry.title=="Documentário compartilhado de teste" && it.entry.packageName==fixture } }
            val watch=reader.watch().items.first { it.entry.packageName==fixture }
            assertEquals(50,watch.percent)
            val guide=reader.guide()
            val channel=guide.channels.firstOrNull { it.name=="Canal multimídia de teste" }
            assertNotNull("A consulta nativa não encontrou o canal compartilhado: ${guide.message}",channel)
            assertEquals("105",channel!!.number)
            assertEquals("Programa agora de teste",channel.now(System.currentTimeMillis())?.title)
            assertEquals("Próximo programa de teste",channel.next(System.currentTimeMillis())?.title)
        } finally {
            context.startActivity(Intent().setComponent(component).putExtra("clean_catalog",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Revoking a runtime permission would kill the instrumentation process; the emulator is discarded.
        }
    }

    @Test fun realScheduledReminderNotifiesOnceAndCanBeCancelled() {
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val reminder=MediaReminders.add(context,MediaEntry(title="Programa de teste"),System.currentTimeMillis()+60000L)
        try {
            await { MediaReminders.read(context).first { it.id==reminder.id }.fired }
            assertTrue(manager.activeNotifications.any { it.tag==reminder.id && it.notification.contentIntent != null })
            assertFalse(MediaReminders.deliver(context,reminder.id))
            assertEquals(1,MediaReminders.read(context).count { it.id==reminder.id })
        } finally { MediaReminders.remove(context,reminder.id) }
        assertFalse(MediaReminders.read(context).any { it.id==reminder.id })
        assertFalse(manager.activeNotifications.any { it.tag==reminder.id })
        invalid { MediaReminders.add(context,MediaEntry(title="Ontem"),System.currentTimeMillis()-1) }
    }

    @Test fun realMediaSessionControlsPauseAndResumeWithoutReadingNotifications() {
        val component=ComponentName(context,MediaAccessService::class.java).flattenToString()
        lateinit var controller:MediaPlayback
        val fixtureActivity=ComponentName(fixture,MediaFixtureActivity::class.java.name)
        instrumentation.runOnMainSync { controller=MediaPlayback(context,Handler(Looper.getMainLooper())) {} }
        shell("cmd notification allow_listener $component")
        context.startActivity(Intent().setComponent(fixtureActivity).putExtra("start_media",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await { controller.enabled() }
            instrumentation.runOnMainSync { controller.start() }
            await { controller.info.owner==fixture && controller.info.playing }
            assertEquals("Filme de teste",controller.info.title)
            instrumentation.runOnMainSync { assertTrue(controller.toggle()) }
            await { !controller.info.playing }
            instrumentation.runOnMainSync { assertTrue(controller.toggle()) }
            await { controller.info.playing }
            instrumentation.runOnMainSync { controller.stop() }
            assertEquals("",controller.info.owner)
        } finally {
            instrumentation.runOnMainSync { controller.stop() }
            context.startActivity(Intent().setComponent(fixtureActivity).putExtra("release_media",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            shell("cmd notification disallow_listener $component")
        }
    }
}
