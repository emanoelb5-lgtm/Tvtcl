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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * TCL Light Premium UI.
 * Android TV puro: sem WebView, animações contínuas, rede ou bibliotecas adicionais.
 * A identidade do aplicativo não aparece na interface principal.
 */
class MainActivity : Activity() {
    private data class TvApp(
        val name: String,
        val packageId: String,
        val component: ComponentName,
        val icon: Drawable?
    )

    private val bg = Color.rgb(9, 13, 20)
    private val surface = Color.rgb(24, 30, 39)
    private val surfaceFocus = Color.rgb(39, 51, 62)
    private val accent = Color.rgb(185, 231, 224)
    private val white = Color.rgb(243, 246, 249)
    private val soft = Color.rgb(155, 167, 182)
    private val subtle = Color.rgb(112, 126, 140)
    private val locale = Locale("pt", "BR")
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }

    private var timeLabel: TextView? = null
    private var dateLabel: TextView? = null
    private var greetingLabel: TextView? = null
    private var listContainer: LinearLayout? = null
    private var scrollContainer: ScrollView? = null
    private var apps: List<TvApp> = emptyList()
    private var scanning = false
    private var lastScan = 0L
    private var firstRender = true

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            timeLabel?.text = SimpleDateFormat("HH:mm", locale).format(now)
            dateLabel?.text = SimpleDateFormat("EEE, dd 'de' MMM", locale)
                .format(now).replaceFirstChar { it.titlecase(locale) }
            greetingLabel?.text = greeting()
            handler.postDelayed(this, 60000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        createLayout()
        handler.post(tick)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (System.currentTimeMillis() - lastScan > 120000L) refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openSettings()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Bom dia."
        in 12..17 -> "Boa tarde."
        else -> "Boa noite."
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun dimensions(
        width: Int, height: Int,
        left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0
    ) = LinearLayout.LayoutParams(width, height).apply {
        setMargins(dp(left), dp(top), dp(right), dp(bottom))
    }

    private fun text(
        value: String,
        size: Float,
        color: Int = white,
        medium: Boolean = false
    ) = TextView(this).apply {
        this.text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isSingleLine = false
    }

    private fun background(
        color: Int = surface,
        radiusDp: Int = 18,
        strokeColor: Int? = null
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(2), strokeColor)
    }

    private fun createLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(19, 28, 40), bg, Color.rgb(7, 10, 16))
            )
            clipChildren = false
            clipToPadding = false
        }
        setContentView(root)

        // Cabeçalho discreto: nenhuma marca ou texto TCL Light na tela.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        root.addView(header, dimensions(-1, dp(94), 48, 14, 48, 0))

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        greetingLabel = text(greeting(), 29f, white, true)
        left.addView(greetingLabel)
        left.addView(
            text("Tudo no seu lugar.", 12.5f, soft),
            dimensions(-1, -2, 0, 7)
        )
        header.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        timeLabel = text("--:--", 34f, white, true).apply {
            gravity = Gravity.END
            letterSpacing = 0.03f
        }
        dateLabel = text("", 11.5f, soft).apply { gravity = Gravity.END }
        right.addView(timeLabel)
        right.addView(dateLabel, dimensions(-1, -2, 0, 4))
        header.addView(right, dimensions(dp(175), -2))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        scrollContainer = scroll
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(48), dp(8), dp(38), dp(26))
        }
        scroll.addView(listContainer)

        val bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        bottom.addView(text("OK  Abrir    •    Segure OK  Favoritar    •    MENU  Ajustes", 10.5f, subtle),
            LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(text("●", 10f, accent), dimensions(-2, -2, 0, 0, 7))
        bottom.addView(text("Modo leve", 10.5f, soft))
        root.addView(bottom, dimensions(-1, dp(38), 48, 0, 48, 4))
    }

    private fun heading(title: String, description: String = ""): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        row.addView(text(title, 19f, white, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        if (description.isNotBlank()) row.addView(text(description, 11.5f, subtle))
        return row
    }

    private fun styleFocus(view: View, focusKey: String? = null) {
        view.background = background()
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.setOnFocusChangeListener { item, focused ->
            item.animate().cancel()
            item.background = if (focused) background(surfaceFocus, 18, accent)
                else background()
            item.elevation = if (focused) dp(8).toFloat() else 0f
            item.animate()
                .scaleX(if (focused) 1.025f else 1f)
                .scaleY(if (focused) 1.025f else 1f)
                .setDuration(105L)
                .start()
            if (focused && focusKey != null) {
                prefs.edit().putString("focusKey", focusKey).apply()
            }
        }
    }

    private fun tile(app: TvApp, featured: Boolean): View {
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(8))
            clipChildren = false
            clipToPadding = false
            contentDescription = app.name
        }
        val iconSize = if (featured) 66 else 42
        val icon = ImageView(this).apply {
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(icon, dimensions(dp(iconSize), dp(iconSize), 0, 0, 0, if (featured) 11 else 8))
        val appName = text(app.name, if (featured) 13f else 11.5f, white, featured).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(appName, dimensions(-1, -2))
        card.setOnClickListener { launch(app) }
        card.setOnLongClickListener { toggleFavorite(app); true }
        styleFocus(card, "app:" + app.packageId)
        return card
    }

    private fun command(title: String, sign: String, action: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            setPadding(dp(17), 0, dp(11), 0)
            contentDescription = title
        }
        val glyph = text(sign, 20f, accent, true).apply { gravity = Gravity.CENTER }
        card.addView(glyph, dimensions(dp(36), -2, 0, 0, 10))
        card.addView(text(title, 13f, white, true))
        card.setOnClickListener { action() }
        styleFocus(card, "action:" + title)
        return card
    }

    private fun render() {
        val container = listContainer ?: return
        val focusBefore = currentFocus?.tag as? String
        val focusSaved = focusBefore ?: prefs.getString("focusKey", null)
        container.removeAllViews()

        container.addView(
            heading("Favoritos"),
            dimensions(-1, -2, 0, 13, 10, 16)
        )

        val strip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(5), dp(5), dp(10), dp(5))
        }
        strip.addView(line)
        container.addView(strip, dimensions(-1, dp(147), 0, 0, 0, 24))

        val chosen = favorites().mapNotNull { id -> apps.firstOrNull { it.packageId == id } }
        var firstTile: View? = null
        val focusable = LinkedHashMap<String, View>()
        for (app in chosen) {
            val view = tile(app, true)
            view.tag = "app:" + app.packageId
            if (firstTile == null) firstTile = view
            focusable[view.tag.toString()] = view
            line.addView(view, dimensions(dp(153), dp(132), 0, 0, 13))
        }
        if (chosen.isEmpty()) {
            line.addView(
                text("Adicione favoritos mantendo OK pressionado sobre um aplicativo.", 13f, soft),
                dimensions(-1, -2, 8, 25)
            )
        }

        val commands = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(5), dp(4), dp(1), dp(4))
        }
        val shortcuts = listOf(
            command("TV e canais", "◉") { openTv() },
            command("Configurações", "⚙") { openSettings() },
            command("Atualizar", "↻") { refresh() }
        )
        for (shortcut in shortcuts) {
            shortcut.tag = "action:" + shortcut.contentDescription
            focusable[shortcut.tag.toString()] = shortcut
            commands.addView(shortcut,
                LinearLayout.LayoutParams(0, dp(62), 1f).apply {
                    rightMargin = dp(13)
                })
        }
        container.addView(commands, dimensions(-1, dp(77), 0, 0, 0, 22))

        container.addView(
            heading("Aplicativos", "${apps.size} instalados"),
            dimensions(-1, -2, 0, 0, 10, 18)
        )

        for (batch in apps.chunked(5)) {
            val gridRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
                setPadding(dp(5), dp(3), 0, dp(7))
            }
            for (app in batch) {
                val view = tile(app, false)
                view.tag = "app:" + app.packageId
                if (firstTile == null) firstTile = view
                focusable.putIfAbsent(view.tag.toString(), view)
                gridRow.addView(view,
                    LinearLayout.LayoutParams(0, dp(96), 1f).apply {
                        rightMargin = dp(13)
                    })
            }
            repeat(5 - batch.size) {
                gridRow.addView(View(this), LinearLayout.LayoutParams(0, dp(96), 1f).apply {
                    rightMargin = dp(13)
                })
            }
            container.addView(gridRow, dimensions(-1, dp(110)))
        }

        if (apps.isEmpty()) {
            container.addView(text("Nenhum aplicativo visível encontrado.", 13f, soft))
        }

        val next = focusable[focusSaved] ?: firstTile ?: shortcuts.first()
        if (firstRender) {
            scrollContainer?.scrollTo(0, 0)
            firstRender = false
        }
        next.post {
            if (!isFinishing && !isDestroyed && next.isAttachedToWindow) next.requestFocus()
        }
    }

    private fun refresh() {
        if (scanning) return
        scanning = true
        thread(name = "tv-app-scan") {
            val found = try { scanApps() } catch (_: Exception) { emptyList() }
            runOnUiThread {
                scanning = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                apps = found
                lastScan = System.currentTimeMillis()
                render()
            }
        }
    }

    private fun scanApps(): List<TvApp> {
        val pm = packageManager
        val found = LinkedHashMap<String, TvApp>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val request = Intent(Intent.ACTION_MAIN).addCategory(category)
            @Suppress("DEPRECATION")
            val matches: List<ResolveInfo> = pm.queryIntentActivities(request, 0)
            for (candidate in matches) {
                val info = candidate.activityInfo ?: continue
                val pkg = info.packageName
                if (pkg == packageName || found.containsKey(pkg)) continue
                val name = try { candidate.loadLabel(pm).toString() } catch (_: Exception) { pkg }
                val icon = try { candidate.loadIcon(pm) } catch (_: Exception) { null }
                found[pkg] = TvApp(name, pkg, ComponentName(pkg, info.name), icon)
            }
        }
        val collator = Collator.getInstance(locale)
        return found.values.sortedWith(Comparator { a, b -> collator.compare(a.name, b.name) })
    }

    private fun favorites(): MutableList<String> {
        val saved = prefs.getString("favorites", null)
        if (saved != null) return saved.split('|').filter { it.isNotBlank() }.distinct().toMutableList()
        val chosen = mutableListOf<String>()
        for (pkg in listOf("com.google.android.youtube.tv", "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom", "com.spotify.tv.android")) {
            if (apps.any { it.packageId == pkg }) chosen.add(pkg)
        }
        for (app in apps) {
            if (chosen.size >= 5) break
            if (app.packageId !in chosen) chosen.add(app.packageId)
        }
        prefs.edit().putString("favorites", chosen.joinToString("|")).apply()
        return chosen
    }

    private fun toggleFavorite(app: TvApp) {
        val chosen = favorites()
        val added = if (chosen.remove(app.packageId)) false else {
            chosen.add(app.packageId)
            true
        }
        prefs.edit().putString("favorites", chosen.joinToString("|")).apply()
        Toast.makeText(this,
            if (added) "Adicionado aos favoritos" else "Removido dos favoritos",
            Toast.LENGTH_SHORT
        ).show()
        render()
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
            Toast.makeText(this, "Não foi possível abrir " + app.name, Toast.LENGTH_LONG).show()
        }
    }

    private fun openTv() {
        val packages = listOf("com.google.android.tv", "com.tcl.tv",
            "com.tcl.live", "com.mediatek.wwtv.tvcenter")
        val tv = packages.firstNotNullOfOrNull { pkg ->
            apps.firstOrNull { it.packageId == pkg }
        } ?: apps.firstOrNull {
            it.name.equals("Live TV", true) ||
                it.name.equals("TV", true) ||
                it.name.equals("Canais", true) ||
                it.name.equals("Televisão", true)
        }
        if (tv != null) launch(tv) else Toast.makeText(
            this, "Use INPUT/SOURCE para acessar TV e HDMI.", Toast.LENGTH_LONG
        ).show()
    }

    private fun openSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "Configurações indisponíveis.", Toast.LENGTH_LONG).show()
        }
    }
}
