package com.b5solucoes.tcllight

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
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
import android.view.ViewGroup
import android.widget.FrameLayout
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
 * Versão 0.3: experiência televisiva nativa e offline.
 * A grade contém APENAS apps de vídeo/áudio instalados; ações de sistema ficam no topo.
 */
class MainActivity : Activity() {
    private data class TvApp(val name: String, val packageName: String,
                             val component: ComponentName, val icon: Drawable?)

    private val dark = Color.rgb(8, 15, 25)
    private val foreground = Color.rgb(247, 249, 251)
    private val muted = Color.rgb(167, 187, 199)
    private val quiet = Color.rgb(121, 151, 167)
    private val aqua = Color.rgb(174, 232, 227)
    private val cardColor = Color.argb(174, 22, 36, 50)
    private val focusColor = Color.argb(246, 35, 62, 77)
    private val handler = Handler(Looper.getMainLooper())
    private val locale = Locale("pt", "BR")
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private var timeLabel: TextView? = null
    private var dateLabel: TextView? = null
    private var greeting: TextView? = null
    private var contents: LinearLayout? = null
    private var listScroll: ScrollView? = null
    private var mediaApps: List<TvApp> = emptyList()
    private var scanning = false
    private var lastScan = 0L
    private var initialRender = true
    private var lastFocus = ""

    private val clockUpdate = object : Runnable {
        override fun run() {
            val now = Date()
            timeLabel?.text = SimpleDateFormat("HH:mm", locale).format(now)
            dateLabel?.text = SimpleDateFormat("EEE, d 'de' MMM", locale).format(now)
                .replaceFirstChar { it.titlecase(locale) }
            greeting?.text = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                in 5..11 -> "Bom dia."
                in 12..17 -> "Boa tarde."
                else -> "Boa noite."
            }
            handler.postDelayed(this, 60000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = dark
        window.navigationBarColor = dark
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        buildInterface()
        handler.post(clockUpdate)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (System.currentTimeMillis() - lastScan > 90000L) refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacks(clockUpdate)
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
    private fun params(w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    private fun words(s: String, size: Float, color: Int = foreground, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    private fun rounded(c: Int, stroke: Int? = null, radius: Int = 20): GradientDrawable =
        GradientDrawable().apply {
            setColor(c)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(2), stroke)
        }

    private fun buildInterface() {
        val frame = FrameLayout(this)
        frame.addView(AtmosphericBackground(this),
            FrameLayout.LayoutParams(-1, -1))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        root.addView(nav, params(-1, dp(65), 50, 10, 50))
        nav.addView(words("Início", 19f, foreground, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        dateLabel = words("", 11f, muted)
        nav.addView(dateLabel, params(-2, -2, 0, 0, 20))
        timeLabel = words("--:--", 22f, foreground, true)
        nav.addView(timeLabel, params(-2, -2, 0, 0, 20))
        nav.addView(navButton("TV", "◉") { openTv() }, params(dp(76), dp(39), 0, 0, 9))
        nav.addView(navButton("Atualizar", "↻") { refresh() }, params(dp(45), dp(39), 0, 0, 9))
        nav.addView(navButton("Configurações", "⚙") { openSettings() }, params(dp(45), dp(39)))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            isFillViewport = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        listScroll = scroll
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(50), dp(6), dp(42), dp(28))
        }
        contents = column
        scroll.addView(column)

        val hero = FrameLayout(this).apply {
            clipChildren = true
            clipToOutline = true
            background = rounded(Color.rgb(11, 30, 43), Color.argb(65, 161, 206, 218), 22)
        }
        column.addView(hero, params(-1, dp(164), 0, 0, 8, 26))
        hero.addView(HeroArtwork(this), FrameLayout.LayoutParams(-1, -1))
        val heroTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(30), dp(14), dp(20), dp(16))
        }
        hero.addView(heroTexts, FrameLayout.LayoutParams(-1, -1))
        greeting = words("", 13f, aqua, true)
        heroTexts.addView(greeting, params(-1, -2, 0, 0, 0, 8))
        heroTexts.addView(words("O que vamos assistir?", 29f, foreground, true),
            params(-1, -2, 0, 0, 0, 9))
        heroTexts.addView(words("Seus streamings, em um só lugar.", 13f, muted))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(words("OK para abrir   ·   Segure OK para organizar favoritos", 10f, muted),
            LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(words("SIMPLES  ·  SEM ANÚNCIOS", 10f, quiet))
        root.addView(footer, params(-1, dp(34), 50, 0, 50, 6))
    }

    private fun navButton(label: String, symbol: String, run: () -> Unit): View {
        val view = TextView(this).apply {
            text = if (label == "TV") "◉  TV" else symbol
            textSize = if (label == "TV") 12.5f else 20f
            gravity = Gravity.CENTER
            setTextColor(foreground)
            contentDescription = label
            setOnClickListener { run() }
        }
        focusStyle(view, "nav:" + label, 12)
        return view
    }

    private fun focusStyle(view: View, key: String, radius: Int = 20) {
        view.tag = key
        view.isFocusable = true
        view.background = rounded(cardColor, null, radius)
        view.setOnFocusChangeListener { v, focused ->
            v.animate().cancel()
            v.background = rounded(if (focused) focusColor else cardColor,
                if (focused) aqua else null, radius)
            v.elevation = if (focused) dp(8).toFloat() else 0f
            v.animate().scaleX(if (focused) 1.035f else 1f)
                .scaleY(if (focused) 1.035f else 1f).setDuration(95).start()
            if (focused) lastFocus = key
        }
    }

    private fun section(title: String, meta: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(words(title, 19.5f, foreground, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(words(meta, 11f, quiet))
        return row
    }

    private fun appTile(app: TvApp, large: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(9), dp(8), dp(9), dp(8))
            contentDescription = app.name
        }
        val side = if (large) 63 else 49
        card.addView(ImageView(this).apply {
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, params(dp(side), dp(side), 0, 0, 0, if (large) 11 else 9))
        card.addView(words(app.name, if (large) 13f else 12f, foreground, large).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }, params(-1, -2))
        card.setOnClickListener { open(app) }
        card.setOnLongClickListener { toggleFavorite(app); true }
        focusStyle(card, "app:" + app.packageName)
        return card
    }

    private fun render() {
        val column = contents ?: return
        val keepFocus = (currentFocus?.tag as? String) ?: lastFocus
        column.removeViews(1, column.childCount - 1)

        column.addView(section("Favoritos", "Segure OK para editar"),
            params(-1, -2, 0, 0, 8, 16))
        val favoriteStrip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
        }
        val favoriteLine = LinearLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(5), dp(4), dp(6), dp(5))
        }
        favoriteStrip.addView(favoriteLine)
        column.addView(favoriteStrip, params(-1, dp(130), 0, 0, 0, 21))

        val media = mediaApps.associateBy { it.packageName }
        val selected = favorites().mapNotNull { media[it] }
        val available = LinkedHashMap<String, View>()
        var first: View? = null
        for (app in selected) {
            val tile = appTile(app, true)
            if (first == null) first = tile
            available.putIfAbsent(tile.tag.toString(), tile)
            favoriteLine.addView(tile, params(dp(151), dp(113), 0, 0, 12))
        }
        if (selected.isEmpty()) {
            favoriteLine.addView(
                words("Segure OK em um streaming para adicioná-lo aos favoritos.", 13f, muted),
                params(-2, -2, 9, 28))
        }

        column.addView(section("Seus streamings", mediaApps.size.toString() + " instalados"),
            params(-1, -2, 0, 0, 8, 16))

        if (mediaApps.isEmpty()) {
            column.addView(words(
                "Não encontramos aplicativos de streaming na TV. Instale um serviço de mídia e selecione ↻.",
                13f, muted
            ), params(-1, dp(60), 0, 6, 0, 5))
        } else {
            for (group in mediaApps.chunked(5)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                    setPadding(dp(5), dp(3), 0, dp(6))
                }
                for (app in group) {
                    val tile = appTile(app, false)
                    if (first == null) first = tile
                    available.putIfAbsent(tile.tag.toString(), tile)
                    row.addView(tile, LinearLayout.LayoutParams(0, dp(95), 1f).apply {
                        rightMargin = dp(13)
                    })
                }
                repeat(5 - group.size) {
                    row.addView(View(this),
                        LinearLayout.LayoutParams(0, dp(95), 1f).apply {
                            rightMargin = dp(13)
                        })
                }
                column.addView(row, params(-1, dp(109)))
            }
        }

        val target = available[keepFocus] ?: first
        if (initialRender) {
            listScroll?.scrollTo(0, 0)
            initialRender = false
        }
        (target ?: findViewById<View>(android.R.id.content)).post {
            if (!isDestroyed && !isFinishing) {
                if (target != null && target.isAttachedToWindow) target.requestFocus()
                else if (currentFocus == null) {
                    // Atalho TV, quando ainda não há streaming instalado.
                    val buttons = (findViewById<ViewGroup>(android.R.id.content)
                        .getChildAt(0) as? ViewGroup)
                    buttons?.requestFocus()
                }
            }
        }
    }

    private fun refresh() {
        if (scanning) return
        scanning = true
        thread(name = "installed-tv-media") {
            val installed = try { detectStreamingApps() } catch (_: Exception) { emptyList() }
            runOnUiThread {
                scanning = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                mediaApps = installed
                lastScan = System.currentTimeMillis()
                render()
            }
        }
    }

    /**
     * Estratégia de mínimo privilégio:
     * - consulta apenas Activities lançáveis que já estão instaladas nesta TV
     * - remove launchers, Ajustes e ferramentas técnicas
     * - aceita apps de mídia conhecidos ou apps cuja categoria Android seja vídeo/áudio
     * Não cria cards fictícios nem depende de API de terceiros.
     */
    private fun detectStreamingApps(): List<TvApp> {
        val manager = packageManager
        val found = LinkedHashMap<String, TvApp>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val request = Intent(Intent.ACTION_MAIN).addCategory(cat)
            @Suppress("DEPRECATION")
            val candidates: List<ResolveInfo> = manager.queryIntentActivities(request, 0)
            for (entry in candidates) {
                val activity = entry.activityInfo ?: continue
                val pkg = activity.packageName
                if (pkg == packageName || found.containsKey(pkg)) continue
                val name = try { entry.loadLabel(manager).toString() }
                    catch (_: Exception) { pkg }
                val category = try {
                    @Suppress("DEPRECATION")
                    val info = manager.getApplicationInfo(pkg, 0)
                    info.category
                } catch (_: Exception) { ApplicationInfo.CATEGORY_UNDEFINED }
                if (!isStreamingApp(pkg, name, category)) continue
                val icon = try { entry.loadIcon(manager) } catch (_: Exception) { null }
                found[pkg] = TvApp(name, pkg, ComponentName(pkg, activity.name), icon)
            }
        }
        val comparator = Collator.getInstance(locale)
        return found.values.sortedWith(Comparator { a, b -> comparator.compare(a.name, b.name) })
    }

    private fun isStreamingApp(pkgRaw: String, labelRaw: String, category: Int): Boolean {
        val pkg = pkgRaw.lowercase(Locale.ROOT)
        val label = labelRaw.lowercase(locale).trim()
        // Exclusão tem prioridade até sobre nomes de marca: apps técnicos não são mídia.
        val forbiddenPackages = listOf(
            "com.android.settings", "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcher", "com.google.android.leanbacklauncher",
            "com.android.launcher", "com.tcl.launcher", "com.android.packageinstaller",
            "com.google.android.packageinstaller", "com.android.vending",
            "com.google.android.apps.nbu.files", "com.android.documentsui",
            "com.android.inputmethod", "com.google.android.inputmethod"
        )
        val forbiddenWords = listOf(
            "settings", "configurações", "configuracoes", "ajustes", "launcher",
            "file manager", "gerenciador de arquivos", "explorador de arquivos",
            "package installer", "instalador", "play store", "app store",
            "calculator", "calculadora", "browser", "navegador", "teclado",
            "keyboard", "feedback", "diagnostic", "diagnóstico", "screensaver",
            "protetor de tela", "wallpaper", "papel de parede", "updater",
            "atualizador", "download manager", "downloads"
        )
        if (forbiddenPackages.any { pkg == it || pkg.startsWith(it + ".") }) return false
        if (forbiddenWords.any { label.contains(it) }) return false

        val mediaPackages = listOf(
            "com.netflix.", "com.google.android.youtube", "com.amazon.amazonvideo",
            "com.disney.", "com.disneyplus.", "com.hbo.", "com.wbd.", "com.max.",
            "com.globo.", "com.globoplay.", "com.pluto.", "tv.pluto.",
            "com.paramount.", "com.cbs.", "com.apple.atve.", "com.apple.tv",
            "com.spotify.", "com.deezer.", "com.plexapp.", "org.xbmc.",
            "org.videolan.", "com.mxtech.", "com.bamtech.", "com.crunchyroll.",
            "com.stremio.", "com.twitch.", "com.dazn.", "com.mubi.",
            "com.rakuten.", "com.telecine.", "com.claro.", "br.com.claro.",
            "com.sky.", "com.directv.", "com.vivo.", "com.tubitv.",
            "com.roku.", "com.sling.", "com.tivimate.", "com.iptv.",
            "com.google.android.videos", "com.google.android.tv",
            "com.tcl.live", "com.tcl.tv", "com.mediatek.wwtv.tvcenter",
            "com.storm.", "com.b5solucoes.storm"
        )
        if (mediaPackages.any { pkg == it.removeSuffix(".") || pkg.startsWith(it) }) return true

        val mediaWords = listOf(
            "netflix", "youtube", "prime video", "amazon video", "disney+",
            "disney plus", "globoplay", "max", "hbo", "pluto tv", "paramount",
            "apple tv", "spotify", "deezer", "plex", "kodi", "vlc", "mx player",
            "crunchyroll", "stremio", "twitch", "dazn", "mubi", "rakuten",
            "telecine", "claro tv", "sky+", "directv", "vivo play",
            "iptv", "tivimate", "smart tv", "stream", "storm", "cine",
            "filmes", "séries", "series", "live tv", "tv ao vivo",
            "canais", "plataforma de vídeo", "player de vídeo", "video player",
            "youtube music", "music", "música", "tubi", "viki", "netmovies",
            "playplus", "play plus", "sbt vídeos", "sbt videos", "record plus"
        )
        if (mediaWords.any { label.contains(it) }) return true
        return category == ApplicationInfo.CATEGORY_VIDEO ||
            category == ApplicationInfo.CATEGORY_AUDIO
    }

    private fun favorites(): MutableList<String> {
        val old = prefs.getString("favorites", null)
        if (old != null) return old.split('|').filter { it.isNotBlank() }.distinct().toMutableList()
        val chosen = mutableListOf<String>()
        val order = listOf("netflix", "youtube", "prime video", "globoplay", "disney", "max")
        for (word in order) {
            mediaApps.firstOrNull {
                it.name.lowercase(locale).contains(word) && it.packageName !in chosen
            }?.let { chosen.add(it.packageName) }
            if (chosen.size >= 5) break
        }
        for (app in mediaApps) {
            if (chosen.size >= 5) break
            if (app.packageName !in chosen) chosen.add(app.packageName)
        }
        prefs.edit().putString("favorites", chosen.joinToString("|")).apply()
        return chosen
    }

    private fun toggleFavorite(app: TvApp) {
        val chosen = favorites()
        val added = if (chosen.remove(app.packageName)) false else {
            chosen.add(app.packageName); true
        }
        prefs.edit().putString("favorites", chosen.joinToString("|")).apply()
        Toast.makeText(this,
            if (added) "Adicionado aos favoritos" else "Removido dos favoritos",
            Toast.LENGTH_SHORT
        ).show()
        render()
    }

    private fun open(app: TvApp) {
        try {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
                ?: packageManager.getLaunchIntentForPackage(app.packageName)
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
        val pkgList = listOf("com.google.android.tv", "com.tcl.tv",
            "com.tcl.live", "com.mediatek.wwtv.tvcenter")
        val tv = pkgList.firstNotNullOfOrNull { pkg ->
            mediaApps.firstOrNull { it.packageName == pkg }
        } ?: mediaApps.firstOrNull {
            it.name.equals("TV", true) || it.name.equals("Live TV", true) ||
                it.name.equals("Canais", true) || it.name.equals("Televisão", true)
        }
        if (tv != null) open(tv) else Toast.makeText(
            this, "Para alternar HDMI ou TV, use INPUT/SOURCE no controle.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "Configurações indisponíveis.", Toast.LENGTH_LONG).show()
        }
    }
}

/** Arte estática em Canvas, sem imagens de internet, vídeos, processamento constante ou loops. */
private class AtmosphericBackground(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        paint.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.rgb(9, 19, 33), Color.rgb(7, 12, 22), Color.rgb(15, 29, 42)),
            null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(w * 0.79f, h * 0.31f, w * 0.64f,
            intArrayOf(Color.argb(105, 10, 109, 135), Color.argb(30, 8, 69, 98),
                Color.TRANSPARENT),
            floatArrayOf(0f, .58f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(w * 0.16f, h * 0.93f, w * 0.64f,
            intArrayOf(Color.argb(80, 29, 78, 118), Color.argb(21, 34, 70, 106),
                Color.TRANSPARENT),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        val hills = Path().apply {
            moveTo(w * .44f, h)
            cubicTo(w * .56f, h * .66f, w * .74f, h * .61f, w, h * .78f)
            lineTo(w, h); close()
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(23, 75, 151, 167)
        c.drawPath(hills, paint)
    }
}

/** Orbes suaves à direita do destaque principal: papel de parede cinematográfico desenhado uma vez. */
private class HeroArtwork(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        p.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.rgb(12, 33, 49), Color.rgb(15, 47, 64), Color.rgb(7, 22, 39)),
            null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w * .83f, h * .43f, h * 1.5f,
            intArrayOf(Color.argb(185, 53, 174, 164),
                Color.argb(85, 39, 99, 144), Color.TRANSPARENT),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = h * .024f
        p.color = Color.argb(75, 171, 238, 226)
        val path = Path().apply {
            moveTo(w * .63f, h * 1.15f)
            cubicTo(w * .78f, h * -.50f, w * .92f, h * -.28f, w * 1.16f, h * .65f)
        }
        c.drawPath(path, p)
        p.strokeWidth = h * .010f
        p.color = Color.argb(50, 234, 252, 238)
        val other = Path().apply {
            moveTo(w * .73f, h * 1.10f)
            cubicTo(w * .84f, h * -.18f, w * .94f, h * -.05f, w * 1.12f, h * .60f)
        }
        c.drawPath(other, p)
        p.style = Paint.Style.FILL
    }
}
