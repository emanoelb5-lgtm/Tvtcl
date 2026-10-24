package com.b5solucoes.tcllight

import org.json.JSONArray
import org.json.JSONObject

internal data class HomeShortcut(val key: String, val name: String = "") {
    fun source(inputs: List<TvSource>) = inputs.firstOrNull { key == "input:" + it.id }
    fun label(inputs: List<TvSource>) = name.ifBlank { if (key == "tv") "TV e canais" else source(inputs)?.label ?: "Entrada indisponível" }
}

/** Only native TV or currently exposed TV inputs can be opened; no persisted arbitrary intents. */
internal object HomeShortcuts {
    const val PREF = "home.shortcuts"
    const val MAX = 20
    fun decode(raw: String?): List<HomeShortcut> = if (raw == null) listOf(HomeShortcut("tv")) else
        try { parse(JSONArray(raw)) } catch (_: Exception) { listOf(HomeShortcut("tv")) }

    fun parse(array: JSONArray): List<HomeShortcut> {
        require(array.length() <= MAX)
        val result = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val key = item.get("key"); val rawName = if(item.has("name")) item.get("name") else ""
            require(key is String && rawName is String)
            val name = rawName
            require(key == "tv" || (key.startsWith("input:") && key.length in 7..518))
            require(key.none { it.isISOControl() } && name.length <= 32 && name.none { it.isISOControl() })
            HomeShortcut(key, name.trim())
        }
        require(result.map { it.key }.distinct().size == result.size)
        return result
    }

    fun encode(items: List<HomeShortcut>): String = JSONArray().apply {
        items.forEach { put(JSONObject().put("key", it.key).put("name", it.name)) }
    }.toString()
}
