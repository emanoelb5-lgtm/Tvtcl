package com.b5solucoes.tcllight

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

internal class BackupFailure : Exception("O arquivo não é um backup válido do TCL Light ou contém configurações incompatíveis.")
internal data class BackupData(val values: Map<String, Any>) {
    val summary: String get() {
        val hidden = (values["hidden_apps"] as? Set<*>)?.size ?: 0
        val shortcuts = HomeShortcuts.decode(values[HomeShortcuts.PREF] as? String).size
        return "Organização dos aplicativos, $hidden ocultos, $shortcuts atalhos e ajustes de aparência."
    }
}

/** Strict whitelist and full validation before a single atomic SharedPreferences edit. */
internal object LauncherBackup {
    const val MAX_BYTES = 256 * 1024
    private val ints = linkedMapOf(
        "wallpaper_mode" to setOf(-1,0,1,2,3,4,5), "wallpaper_dim" to setOf(20,35,50,65),
        "icon_scale" to setOf(0,1,2), "top_count" to setOf(5,7,10),
        "safe_horizontal" to setOf(24,42,56,72), "safe_vertical" to setOf(0,8,16,24),
        "text_scale" to setOf(100,110,120))
    private val defaults = mapOf("wallpaper_mode" to -1, "wallpaper_dim" to 50, "icon_scale" to 1,
        "top_count" to 7, "safe_horizontal" to 42, "safe_vertical" to 8, "text_scale" to 100)
    private val sets = setOf("hidden_apps", "pinned_apps")
    private val orders = setOf("order.top", "order.grid")
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private fun validPackage(value: String) = value.length <= 255 && value.matches(packagePattern)
    private fun owned(key: String) = key in ints || key in sets || key in orders || key == "eco_mode" ||
        key == HomeShortcuts.PREF || key.startsWith("usage.count.") || key.startsWith("usage.time.")

    fun export(prefs: SharedPreferences, lowMemory: Boolean): ByteArray {
        val values = JSONObject()
        ints.forEach { (key, _) -> values.put(key, prefs.getInt(key, defaults.getValue(key))) }
        values.put("eco_mode", prefs.getBoolean("eco_mode", lowMemory))
        sets.forEach { key -> values.put(key, JSONArray(prefs.getStringSet(key, emptySet())!!.sorted())) }
        orders.forEach { key -> values.put(key, JSONArray(AppOrdering.decode(prefs.getString(key, null)))) }
        values.put(HomeShortcuts.PREF, JSONArray(HomeShortcuts.encode(HomeShortcuts.decode(prefs.getString(HomeShortcuts.PREF, null)))))
        prefs.all.filterKeys { it.startsWith("usage.count.") }.entries.take(500).forEach { (key, value) ->
            if (value is Int && validPackage(key.removePrefix("usage.count."))) values.put(key, value.coerceAtLeast(0))
        }
        prefs.all.filterKeys { it.startsWith("usage.time.") }.entries.take(500).forEach { (key, value) ->
            if (value is Long && validPackage(key.removePrefix("usage.time."))) values.put(key, value.coerceAtLeast(0))
        }
        val bytes = JSONObject().put("format", "tcl-light-settings").put("schema", 1)
            .put("package", UpdatePolicy.PACKAGE).put("version", BuildConfig.VERSION_NAME)
            .put("created_at", System.currentTimeMillis()).put("preferences", values).toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        parse(bytes) // Never write an export that cannot be read back safely.
        return bytes
    }

    fun read(input: InputStream): BackupData {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_BYTES) throw BackupFailure()
            output.write(buffer,0,count)
        }
        return parse(output.toByteArray())
    }

    fun parse(bytes: ByteArray): BackupData = try {
        require(bytes.size <= MAX_BYTES)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getString("format") == "tcl-light-settings" && root.get("schema") == 1 && root.getString("package") == UpdatePolicy.PACKAGE)
        val source = root.getJSONObject("preferences")
        require(source.length() <= 1030)
        val result = LinkedHashMap<String,Any>()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next(); val value = source.get(key)
            require(owned(key))
            when {
                key in ints -> { require(value is Int && value in ints.getValue(key)); result[key] = value }
                key == "eco_mode" -> { require(value is Boolean); result[key] = value }
                key in sets || key in orders -> {
                    require(value is JSONArray && value.length() <= 500)
                    val ids = (0 until value.length()).map { index ->
                        val item = value.get(index); require(item is String && validPackage(item)); item
                    }
                    require(ids.distinct().size == ids.size)
                    result[key] = if (key in sets) ids.toSet() else AppOrdering.encode(ids)
                }
                key == HomeShortcuts.PREF -> { require(value is JSONArray); result[key] = HomeShortcuts.encode(HomeShortcuts.parse(value)) }
                key.startsWith("usage.count.") -> { require(validPackage(key.removePrefix("usage.count.")) && value is Int && value >= 0); result[key] = value }
                else -> { require(validPackage(key.removePrefix("usage.time.")) && (value is Long || value is Int) && (value as Number).toLong() >= 0); result[key] = (value as Number).toLong() }
            }
        }
        require(ints.keys.all { it in result } && "eco_mode" in result && HomeShortcuts.PREF in result && sets.all { it in result } && orders.all { it in result })
        BackupData(result)
    } catch (_: Exception) { throw BackupFailure() }

    fun restore(prefs: SharedPreferences, data: BackupData) {
        val edit = prefs.edit()
        prefs.all.keys.filter(::owned).forEach { edit.remove(it) }
        for ((key, value) in data.values) when (value) {
            is Int -> edit.putInt(key,value)
            is Long -> edit.putLong(key,value)
            is Boolean -> edit.putBoolean(key,value)
            is String -> edit.putString(key,value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key,value as Set<String>) }
        }
        // A backup never resumes an untested video input on another TV.
        edit.remove("tv_background_input")
        if (!edit.commit()) throw java.io.IOException("Não foi possível restaurar as preferências")
    }
}
