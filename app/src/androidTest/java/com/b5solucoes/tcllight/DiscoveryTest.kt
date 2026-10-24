package com.b5solucoes.tcllight

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic page data stays in the test APK. The separate live test verifies the actual public contract. */
internal object DiscoveryFixtures {
    fun feed(code:Int=BuildConfig.VERSION_CODE+1)=JSONObject().apply {
        put("schema",1);put("package",UpdatePolicy.PACKAGE);put("version_name","0.99.0");put("version_code",code)
        put("min_sdk",23);put("size_bytes",1);put("sha256","0".repeat(64));put("certificate_sha256",UpdatePolicy.CERTIFICATE)
        put("apk_url",UpdatePolicy.REPOSITORY+"/releases/download/v0.99.0/TCL-Light-0.99.0.apk")
    }.toString()
    fun page(query:String="MATRIX",country:String="BR",badLink:Boolean=false):String {
        val table=JSONArray()
        fun add(value:Any?):Int { val i=table.length();table.put(value ?: JSONObject.NULL);return i }
        fun obj(vararg values:Pair<String,Int>)=add(JSONObject().apply { values.forEach { put(it.first,it.second) } })
        val provider=obj("__typename" to add("Package"),"clearName" to add("Arquivos de teste"))
        val offer=obj("__typename" to add("Offer"),"country" to add("BR"),"monetizationType" to add("FLATRATE"),
            "standardWebURL" to add(if(badLink) "intent://unsafe#Intent;end" else "https://stream.example.org/title/matrix"),
            "package" to obj("__ref" to add("Package:p1")))
        val rent=obj("__typename" to add("Offer"),"country" to add("BR"),"monetizationType" to add("RENT"),
            "standardWebURL" to add("https://stream.example.org/title/matrix"),"retailPrice({\"language\":\"pt\"})" to add("R$ 9,90"),"package" to obj("__ref" to add("Package:p1")))
        val foreign=obj("__typename" to add("Offer"),"country" to add("US"),"monetizationType" to add("BUY"),
            "standardWebURL" to add("https://stream.example.org/title/matrix"),"package" to obj("__ref" to add("Package:p1")))
        val content=obj("title" to add("Matrix"),"originalReleaseYear" to add(1999),"fullPath" to add("/br/filme/matrix"),"backdrops" to add(JSONArray()))
        val offers=add(JSONArray().put(obj("__ref" to add("Offer:o1"))).put(obj("__ref" to add("Offer:o2"))).put(foreign))
        val node=obj("__typename" to add("Movie"),"id" to add("tm1"),"content({\"country\":\"BR\",\"language\":\"pt\"})" to content,
            "offers({\"country\":\"BR\",\"filter\":{\"preAffiliate\":true},\"platform\":\"WEB\"})" to offers)
        val connection=obj("edges" to add(JSONArray().put(obj("node" to obj("__ref" to add("Movie:tm1"))))))
        obj("Movie:tm1" to node,"Offer:o1" to offer,"Offer:o2" to rent,"Package:p1" to provider)
        obj(("searchTitles("+JSONObject().put("country",country).put("filter",JSONObject().put("searchQuery",query)).toString()+")") to connection)
        return "<html><script type=\"application/json\" id=\"__NUXT_DATA__\">$table</script></html>"
    }
}

@RunWith(AndroidJUnit4::class)
class DiscoveryTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun await(condition:()->Boolean) {
        val end=SystemClock.uptimeMillis()+6000
        while(SystemClock.uptimeMillis()<end) { instrumentation.waitForIdleSync();if(condition()) return;Thread.sleep(25) }
        fail("A operação não terminou")
    }
    @Test fun parsesOnlyMatchingBrazilSearchAndSeparatesRentFromSubscription() {
        val result=StreamingPages.parse(DiscoveryFixtures.page(),"MATRIX")
        val title=result.titles.single();assertEquals("Matrix",title.title);assertEquals(1999,title.year)
        assertEquals(listOf("FLATRATE","RENT"),title.offers.map { it.type })
        assertEquals("Assinatura",title.offers.first().label);assertEquals("Aluguel · R$ 9,90",title.offers[1].label)
        assertTrue(title.offers.all { it.url=="https://stream.example.org/title/matrix" })
        assertEquals(listOf("RENT"),StreamingPages.parse(DiscoveryFixtures.page(badLink=true),"MATRIX").titles.single().offers.map { it.type })
        for((html,query) in listOf(DiscoveryFixtures.page(country="US") to "MATRIX",DiscoveryFixtures.page() to "OUTRO","<html>offline</html>" to "MATRIX")) {
            try { StreamingPages.parse(html,query);fail("Dados de busca incompatíveis foram aceitos") } catch(_:IllegalArgumentException){}
        }
        for(url in listOf("file:///sdcard/a","https://localhost/x","https://127.0.0.1/x","https://user:pass@example.org/x","https://example.org:444/x")) assertNull(StreamingPages.web(url))
    }
    @Test fun liveBrazilSearchProvidesTitleLevelStreamingLinks() {
        val transport=PublicCatalogTransport()
        try {
            val result=StreamingPages.parse(transport.read("Interestelar"),"Interestelar")
            val film=result.titles.firstOrNull { it.title.equals("Interestelar",true) && it.year==2014 }
            assertNotNull("A página pública precisa retornar o filme correto",film)
            assertTrue("A consulta real precisa trazer links de streaming no Brasil",film!!.offers.isNotEmpty())
            assertTrue(film.offers.all { StreamingPages.web(it.url)!=null && it.service.isNotBlank() })
            assertTrue(film.page.startsWith("https://www.justwatch.com/br/filme/"))
        } finally { transport.cancel() }
    }
    @Test fun searchUsesFreshMemoryCacheAndDropsCancelledResponses() {
        val calls=AtomicInteger();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val search=StreamingSearch(object:CatalogTransport {
            override fun read(query:String):String { calls.incrementAndGet();if(query=="OLD") { entered.countDown();release.await(3,TimeUnit.SECONDS) };return DiscoveryFixtures.page(query) }
        })
        var received=""
        try {
            instrumentation.runOnMainSync { search.search("OLD") { received="OLD" } }
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            instrumentation.runOnMainSync { search.search("MATRIX") { received=it.query } };release.countDown()
            await { received=="MATRIX" };assertEquals(2,calls.get())
            instrumentation.runOnMainSync { search.search("MATRIX") { received=it.query } }
            assertEquals(2,calls.get());assertEquals("MATRIX",received)
        } finally { release.countDown();instrumentation.runOnMainSync { search.close() } }
    }
    @Test fun automaticChecksThrottleHydrateKnownUpdatesAndClearAfterInstalling() {
        val prefs=context.getSharedPreferences("discovery-auto-updates",Context.MODE_PRIVATE);prefs.edit().clear().commit()
        var time=1_800_000_000_000L;val calls=AtomicInteger()
        val transport=object:UpdateTransport { override fun open(url:String):UpdateStream { calls.incrementAndGet();val b=DiscoveryFixtures.feed().toByteArray();return UpdateStream(ByteArrayInputStream(b),b.size.toLong()) } }
        val identity={InstalledUpdateApp(UpdatePolicy.PACKAGE,BuildConfig.VERSION_CODE.toLong(),UpdatePolicy.CERTIFICATE)}
        val engine=AppUpdates(context,transport,identity,prefs,{time})
        try {
            assertTrue(engine.checkAutomatically());await { engine.state.phase==UpdatePhase.AVAILABLE }
            assertFalse(engine.checkAutomatically());assertEquals(1,calls.get())
            val restored=AppUpdates(context,transport,identity,prefs,{time})
            assertEquals(UpdatePhase.AVAILABLE,restored.state.phase);assertEquals(1,calls.get());restored.close()
            time+=6*60*60*1000L;assertTrue(engine.checkAutomatically());await { calls.get()==2 && engine.state.phase==UpdatePhase.AVAILABLE }
            val upgraded=AppUpdates(context,transport,{identity().copy(versionCode=BuildConfig.VERSION_CODE+1L)},prefs,{time})
            assertNull(upgraded.state.release);assertFalse(prefs.contains("known_release"));upgraded.close()
        } finally { engine.close();prefs.edit().clear().commit() }
    }
    @Test fun failedAutomaticChecksRetryAfterFifteenMinutesAndManualChecksBypassCooldown() {
        val prefs=context.getSharedPreferences("discovery-failed-updates",0);prefs.edit().clear().commit()
        var time=1_800_000_000_000L;val calls=AtomicInteger()
        val engine=AppUpdates(context,object:UpdateTransport { override fun open(url:String):UpdateStream { calls.incrementAndGet();throw IOException("offline") } },
            {InstalledUpdateApp(UpdatePolicy.PACKAGE,12,UpdatePolicy.CERTIFICATE)},prefs,{time})
        try {
            assertTrue(engine.checkAutomatically());await { engine.state.phase==UpdatePhase.ERROR }
            assertFalse(engine.checkAutomatically());assertEquals(1,calls.get())
            assertTrue(engine.check());await { calls.get()==2 && engine.state.phase==UpdatePhase.ERROR }
            time+=15*60*1000L;assertTrue(engine.checkAutomatically());await { calls.get()==3 && engine.state.phase==UpdatePhase.ERROR }
        } finally { engine.close();prefs.edit().clear().commit() }
    }
}
