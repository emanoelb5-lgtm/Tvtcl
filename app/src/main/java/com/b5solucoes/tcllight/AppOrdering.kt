package com.b5solucoes.tcllight

import org.json.JSONArray

/** A separate saved order for each lane. Missing apps disappear; new ones stay reachable. */
internal object AppOrdering {
    fun decode(value: String?): List<String> = try {
        val array = JSONArray(value ?: "[]")
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.distinct()
    } catch (_: Exception) { emptyList() }

    fun encode(ids: List<String>): String = JSONArray(ids.distinct()).toString()

    fun <T> apply(items: List<T>, ids: List<String>, key: (T) -> String): List<T> {
        if (ids.isEmpty()) return items
        val position = ids.withIndex().associate { it.value to it.index }
        return items.withIndex().sortedWith(compareBy<IndexedValue<T>> {
            position[key(it.value)] ?: Int.MAX_VALUE
        }.thenBy { it.index }).map { it.value }
    }

    fun move(ids: List<String>, id: String, delta: Int): List<String> {
        val from = ids.indexOf(id)
        if (from < 0 || delta == 0) return ids
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (from == to) return ids
        return ids.toMutableList().apply { add(to, removeAt(from)) }
    }
}
