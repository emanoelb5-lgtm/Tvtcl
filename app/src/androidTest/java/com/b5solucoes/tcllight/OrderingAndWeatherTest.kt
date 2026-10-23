package com.b5solucoes.tcllight

import org.junit.Assert.*
import org.junit.Test

class OrderingAndWeatherTest {
    @Test fun savedOrderHandlesMissingNewAndDuplicateApps() {
        val saved = AppOrdering.decode("[\"b\",\"removed\",\"b\",\"a\"]")
        assertEquals(listOf("b", "a", "new"), AppOrdering.apply(listOf("a", "b", "new"), saved) { it })
        assertEquals(emptyList<String>(), AppOrdering.decode("invalid"))
        assertEquals(listOf("b", "a"), AppOrdering.decode(AppOrdering.encode(listOf("b", "a", "b"))))
    }

    @Test fun movingPreservesMembershipAndStopsAtEachEdge() {
        val ids = listOf("a", "b", "c")
        assertEquals(listOf("b", "c", "a"), AppOrdering.move(ids, "a", 2))
        assertEquals(ids, AppOrdering.move(ids, "a", -1))
        assertEquals(ids, AppOrdering.move(ids, "missing", 1))
        assertEquals(ids.toSet(), AppOrdering.move(ids, "b", -1).toSet())
    }

    @Test fun weatherRejectsBadDataAndDoesNotPresentExpiredValuesAsCurrent() {
        val now = System.currentTimeMillis()
        val value = WeatherSnapshot.parse("{\"current\":{\"temperature_2m\":28.4,\"weather_code\":63}}", now)
        assertEquals("28°C · Chuva", value.label(now))
        assertTrue(value.label(now + 2 * 60 * 60 * 1000L).contains("anterior"))
        assertEquals("Clima indisponível", value.label(now + 7 * 60 * 60 * 1000L))
        for (body in listOf("{}", "{\"current\":{\"temperature_2m\":null,\"weather_code\":0}}",
            "{\"current\":{\"temperature_2m\":900,\"weather_code\":0}}")) {
            try { WeatherSnapshot.parse(body, now); fail("Dados inválidos foram aceitos") }
            catch (_: Exception) { }
        }
    }

    @Test fun videoSourceCreatesNativePassthroughIntent() {
        val input = TvSource("vendor/.Input/HDMI1", "HDMI 1", true, 0, "vendor")
        assertEquals("content", input.intent().data?.scheme)
        assertEquals("android.media.tv", input.intent().data?.authority)
        assertTrue(input.intent().data.toString().contains("passthrough"))
        assertEquals("Conectada", input.detail)
    }
}
