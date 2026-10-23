package com.b5solucoes.tcllight

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** TCL Light: launcher Android TV offline, sem serviços em segundo plano. */
class MainActivity : Activity() {
    private data class TvApp(
        val name: String,
        val packageId: String,
        val component: ComponentName,
        val icon: Drawable?
    )

    private val navy = Color.rgb(8, 17, 29)
    private val panel = Color.rgb(19, 32, 48)
    private val mint = Color.rgb(93, 225, 213)
    private val light = Color.WHITE
    private val muted = Color.rgb(162, 181, 198)
    private val ui = Handler(Looper.getMainLooper())
    private val locale = Locale("pt", "BR")
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private var clockView: TextView? = null
    private var dateView: TextView? = null
    private var body: LinearLayout? = null
    private var apps: List<TvApp> = emptyList()
    private var scanning = false
    private var lastScan = 0L

    private val clockTask = object : Runnable {
        override fun run() {
            clockView?.text = SimpleDateFormat("HH:mm", locale).format(Date())
            val full = SimpleDateFormat("EEEE, d 'de' MMMM", locale).format(Date())
            dateView?.text = full.replaceFirstChar { it.titlecase(locale) }
            ui.postDelayed(this, 60000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        drawShell()
        ui.post(clockTask)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (System.currentTimeMillis() - lastScan > 120000L) refresh()
    }

    override fun onDestroy() {
        ui.removeCallbacks(clockTask)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openSettings()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun label(content: String, size: Float, color: Int = light, bold: Boolean = false) =
        TextView(this).apply {
            text = content
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun shape(fill: Int = panel, border: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(15).toFloat()
            if (border != null) setStroke(dp(2), border)
        }

    private fun lp(w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun drawShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(19, 41, 61), navy, Color.rgb(4, 10, 20))
            )
        }
        setContentView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(header, lp(-1, dp(65), 38, 14, 38, 0))
        header.addView(View(this).apply { background = shape(mint) }, lp(dp(7), dp(29), 0, 0, 15))
        header.addView(label("TCL  LIGHT", 22f, light, true), LinearLayout.LayoutParams(0, -2, 1f))
        val timeStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        clockView = label("", 25f, light, true).apply { gravity = Gravity.END }
        dateView = label("", 12f, muted).apply { gravity = Gravity.END }
        timeStack.addView(clockView)
        timeStack.addView(dateView)
        header.addView(timeStack)

        val scroll = ScrollView(this).apply {
            fillViewportCompat()
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(38), dp(8), dp(38), dp(26))
        }
        body = column
        scroll.addView(column)

        column.addView(label("Sua TV, sem distrações.", 31f, light, true), lp(-1, -2, 0, 4, 0, 8))
        column.addView(label("Acesse seus aplicativos com menos espera.", 14f, muted), lp(-1, -2, 0, 0, 0, 23))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(footer, lp(-1, dp(35), 38, 0, 38, 8))
        footer.addView(label("OK: abrir   •   Segure OK: favorito   •   MENU: configurações", 11f, muted),
            LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(label("OFFLINE  •  SEM ANÚNCIOS", 10f, mint, true))
    }

    private fun ScrollView.fillViewportCompat() { isFillViewport = true }

    private fun refresh() {
        if (scanning) return
        scanning = true
        thread(name = "tcl-app-list") {
            val discovered = try { scanApps() } catch (_: Exception) { emptyList() }
            runOnUiThread {
                scanning = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                apps = discovered
                lastScan = System.currentTimeMillis()
                populate()
            }
        }
    }

    private fun scanApps(): List<TvApp> {
        val pm = packageManager
        val results = LinkedHashMap<String, TvApp>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val request = Intent(Intent.ACTION_MAIN).addCategory(category)
            @Suppress("DEPRECATION")
            val matches: List<ResolveInfo> = pm.queryIntentActivities(request, 0)
            for (match in matches) {
                val act = match.activityInfo ?: continue
                if (act.packageName == packageName || results.containsKey(act.packageName)) continue
                val title = try { match.loadLabel(pm).toString() } catch (_: Exception) { act.packageName }
                val picture = try { match.loadIcon(pm) } catch (_: Exception) { null }
                results[act.packageName] = TvApp(
                    title, act.packageName, ComponentName(act.packageName, act.name), picture
                )
            }
        }
        val collator = Collator.getInstance(locale)
        return results.values.sortedWith(Comparator { a, b -> collator.compare(a.name, b.name) })
    }

    private fun favorites(): MutableList<String> {
        val saved = prefs.getString("favorites", null)
        if (saved != null) return saved.split('|').filter { it.isNotEmpty() }.distinct().toMutableList()
        val selected = mutableListOf<String>()
        for (id in listOf("com.google.android.youtube.tv", "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom", "com.spotify.tv.android")) {
            if (apps.any { it.packageId == id }) selected.add(id)
        }
        for (app in apps) {
            if (selected.size >= 5) break
            if (app.packageId !in selected) selected.add(app.packageId)
        }
        prefs.edit().putString("favorites", selected.joinToString("|")).apply()
        return selected
    }

    private fun toggleFavorite(app: TvApp) {
        val selected = favorites()
        val added = if (app.packageId in selected) { selected.remove(app.packageId); false }
            else { selected.add(app.packageId); true }
        prefs.edit().putString("favorites", selected.joinToString("|")).apply()
        Toast.makeText(this, if (added) "Adicionado aos favoritos" else "Removido dos favoritos",
            Toast.LENGTH_SHORT).show()
        populate()
    }

    private fun heading(title: String, info: String): LinearLayout {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(title, 20f, light, true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label(info, 11f, muted))
        return row
    }

    private fun focused(tile: View) {
        tile.setOnFocusChangeListener { view, hasFocus ->
            view.background = if (hasFocus) shape(Color.rgb(32, 65, 79), mint) else shape()
            view.animate().scaleX(if (hasFocus) 1.035f else 1f)
                .scaleY(if (hasFocus) 1.035f else 1f).setDuration(80L).start()
            view.elevation = if (hasFocus) dp(9).toFloat() else 0f
        }
    }

    private fun appTile(app: TvApp, large: Boolean): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = shape()
            isFocusable = true
            clipChildren = false
            setPadding(dp(8), dp(6), dp(8), dp(6))
            contentDescription = app.name
        }
        val icon = ImageView(this).apply {
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val size = if (large) 56 else 41
        tile.addView(icon, lp(dp(size), dp(size), 0, 0, 0, 7))
        tile.addView(label(app.name, if (large) 13f else 12f, light, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp(-1, -2))
        tile.setOnClickListener { launch(app) }
        tile.setOnLongClickListener { toggleFavorite(app); true }
        focused(tile)
        return tile
    }

    private fun action(title: String, mark: String, invoke: () -> Unit): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape()
            setPadding(dp(14), 0, dp(8), 0)
            isFocusable = true
            contentDescription = title
        }
        tile.addView(label(mark, 22f, mint, true), lp(dp(32), -2))
        tile.addView(label(title, 14f, light, true))
        tile.setOnClickListener { invoke() }
        focused(tile)
        return tile
    }

    private fun populate() {
        val column = body ?: return
        while (column.childCount > 2) column.removeViewAt(2)
        column.addView(heading("Favoritos", "Segure OK para editar"), lp(-1, -2, 0, 0, 0, 12))
        val strip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            setPadding(dp(4), dp(3), dp(9), dp(3))
        }
        strip.addView(row)
        column.addView(strip, lp(-1, dp(128), 0, 0, 0, 13))
        val selected = favorites().mapNotNull { id -> apps.firstOrNull { it.packageId == id } }
        for (app in selected) row.addView(appTile(app, true), lp(dp(145), dp(115), 0, 0, 12))
        if (selected.isEmpty()) row.addView(label("Segure OK em algum aplicativo abaixo para favoritar.", 14f, muted))

        val shortcuts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val quick = listOf(
            action("TV / Canais", "◉") { openTv() },
            action("Configurações", "⚙") { openSettings() },
            action("Atualizar apps", "↻") { refresh() }
        )
        for (shortcut in quick) shortcuts.addView(shortcut,
            LinearLayout.LayoutParams(0, dp(61), 1f).apply { rightMargin = dp(12) })
        column.addView(shortcuts, lp(-1, dp(72), 0, 0, 0, 11))
        column.addView(heading("Todos os aplicativos", "${apps.size} encontrados"), lp(-1, -2, 0, 0, 0, 12))

        if (apps.isEmpty()) {
            column.addView(label("Nenhum aplicativo visível encontrado.", 14f, muted))
            shortcuts.post { quick.first().requestFocus() }
            return
        }
        var firstApp: View? = null
        for (group in apps.chunked(4)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            for (app in group) {
                val tile = appTile(app, false)
                if (firstApp == null) firstApp = tile
                line.addView(tile, LinearLayout.LayoutParams(0, dp(92), 1f).apply { rightMargin = dp(12) })
            }
            repeat(4 - group.size) {
                line.addView(View(this), LinearLayout.LayoutParams(0, dp(92), 1f).apply { rightMargin = dp(12) })
            }
            column.addView(line, lp(-1, dp(103)))
        }
        val initial: View? = if (selected.isNotEmpty()) row.getChildAt(0) else firstApp
        initial?.post { if (!isFinishing) initial.requestFocus() }
    }

    private fun launch(app: TvApp) {
        try {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(app.packageId)
                ?: packageManager.getLaunchIntentForPackage(app.packageId)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    component = app.component
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível abrir ${app.name}.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openTv() {
        val preferred = listOf("com.google.android.tv", "com.tcl.tv",
            "com.tcl.live", "com.mediatek.wwtv.tvcenter")
        val target = preferred.firstNotNullOfOrNull { id -> apps.firstOrNull { it.packageId == id } }
            ?: apps.firstOrNull { it.name.equals("Live TV", true) || it.name.equals("TV", true) ||
                it.name.equals("Canais", true) || it.name.equals("Televisão", true) }
        if (target != null) launch(target)
        else Toast.makeText(this, "Use INPUT/SOURCE para alternar TV e HDMI.", Toast.LENGTH_LONG).show()
    }

    private fun openSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "Configurações indisponíveis.", Toast.LENGTH_LONG).show()
        }
    }
}
