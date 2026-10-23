package com.b5solucoes.tcllight

import android.app.Activity
import android.app.role.RoleManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.net.ConnectivityManager
import android.os.Build
import android.view.InputDevice
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
    private var networkLabel: TextView? = null
    private var deviceLabel: TextView? = null
    private var wallpaperMain: AtmosphericBackground? = null
    private var wallpaperNext: AtmosphericBackground? = null
    private var wallpaperIndex = 0
    private var usageDirty = false
    private var navWifi: View? = null
    private var navDevices: View? = null
    private var contents: LinearLayout? = null
    private var listScroll: ScrollView? = null
    private var mediaApps: List<TvApp> = emptyList()
    private var scanning = false
    private var lastScan = 0L
    private var initialRender = true
    private var lastFocus = ""
    private val cycleWallpaper = object : Runnable {
        override fun run() {
            val front = wallpaperNext ?: return
            val back = wallpaperMain ?: return
            val next = (wallpaperIndex + 1) % 3
            front.scene = next
            front.alpha = 0f
            front.animate().alpha(1f).setDuration(1100L)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        back.scene = next
                        wallpaperIndex = next
                        front.alpha = 0f
                        front.animate().setListener(null)
                    }
                }).start()
            handler.postDelayed(this, 45000L)
        }
    }

    private val clockUpdate = object : Runnable {
        override fun run() {
            val now = Date()
            timeLabel?.text = SimpleDateFormat("HH:mm", locale).format(now)
            dateLabel?.text = SimpleDateFormat("EEE, d 'de' MMM", locale).format(now)
                .replaceFirstChar { it.titlecase(locale) }
            refreshStatus()
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
        refreshStatus()
        handler.removeCallbacks(cycleWallpaper)
        handler.postDelayed(cycleWallpaper, 45000L)
        if (System.currentTimeMillis() - lastScan > 90000L) refresh()
        else if (usageDirty) { usageDirty = false; render() }
    }

    override fun onPause() {
        handler.removeCallbacks(cycleWallpaper)
        wallpaperNext?.animate()?.cancel()
        wallpaperNext?.alpha = 0f
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(clockUpdate)
        handler.removeCallbacks(cycleWallpaper)
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
        wallpaperMain = AtmosphericBackground(this)
        wallpaperNext = AtmosphericBackground(this).apply { alpha = 0f }
        frame.addView(wallpaperMain, FrameLayout.LayoutParams(-1, -1))
        frame.addView(wallpaperNext, FrameLayout.LayoutParams(-1, -1))
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
        dateLabel = words("", 10.5f, muted)
        nav.addView(dateLabel, params(-2, -2, 0, 0, 13))
        timeLabel = words("--:--", 21f, foreground, true)
        nav.addView(timeLabel, params(-2, -2, 0, 0, 13))
        networkLabel = words("Rede", 11f, foreground)
        navWifi = statusButton(networkLabel!!, "Wi-Fi") { openWifiSettings() }
        nav.addView(navWifi, params(dp(104), dp(39), 0, 0, 8))
        deviceLabel = words("Controle", 11f, foreground)
        navDevices = statusButton(deviceLabel!!, "Periféricos") { openPeripheralSettings() }
        nav.addView(navDevices, params(dp(112), dp(39), 0, 0, 8))
        nav.addView(navButton("TV", "◉") { openTv() }, params(dp(67), dp(39), 0, 0, 8))
        nav.addView(navButton("Início padrão", "⌂") { setAsDefaultHome() }, params(dp(43), dp(39), 0, 0, 8))
        nav.addView(navButton("Atualizar", "↻") { refresh() }, params(dp(43), dp(39), 0, 0, 8))
        nav.addView(navButton("Configurações", "⚙") { openSettings() }, params(dp(43), dp(39)))

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

        // Respiro cinematográfico: o wallpaper ocupa a tela, sem banner nem slogan.
        column.addView(View(this), params(-1, dp(84)))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(words("Selecione um aplicativo para aumentar sua posição nos mais usados", 10f, muted),
            LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(words("SIMPLES  ·  SEM ANÚNCIOS", 10f, quiet))
        root.addView(footer, params(-1, dp(34), 50, 0, 50, 6))
    }

    private fun navButton(label: String, symbol: String, run: () -> Unit): View {
        val view = TextView(this).apply {
            text = if (label == "TV") "◉  TV" else symbol
            textSize = if (label == "TV") 12.5f else 20f
            gravity = Gravity.CENTER
            setTextColor(this@MainActivity.foreground)
            contentDescription = label
            setOnClickListener { run() }
        }
        focusStyle(view, "nav:" + label, 12)
        return view
    }

    private fun statusButton(status: TextView, accessibility: String, action: () -> Unit): View {
        val holder = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(7), 0, dp(7), 0)
            contentDescription = accessibility
        }
        status.gravity = Gravity.CENTER
        status.maxLines = 1
        status.ellipsize = TextUtils.TruncateAt.END
        holder.addView(status, params(-1, -2))
        holder.setOnClickListener { action() }
        focusStyle(holder, "nav:" + accessibility, 12)
        return holder
    }

    private fun refreshStatus() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val active = cm.activeNetworkInfo
            networkLabel?.text = when {
                active?.isConnected != true -> "◌  Sem rede"
                active.type == ConnectivityManager.TYPE_WIFI -> "◉  Wi-Fi"
                active.type == ConnectivityManager.TYPE_ETHERNET -> "◉  Cabo"
                else -> "◉  Rede"
            }
        } catch (_: Exception) { networkLabel?.text = "◌  Rede" }
        try {
            val inputs = InputDevice.getDeviceIds().toList()
                .mapNotNull { id -> InputDevice.getDevice(id) }
                .count { d ->
                    !d.isVirtual && (
                        d.supportsSource(InputDevice.SOURCE_GAMEPAD) ||
                        d.supportsSource(InputDevice.SOURCE_DPAD) ||
                        d.supportsSource(InputDevice.SOURCE_KEYBOARD) ||
                        d.supportsSource(InputDevice.SOURCE_MOUSE)
                    )
                }
            deviceLabel?.text = if (inputs > 0) "⌘  $inputs entrada" +
                (if (inputs > 1) "s" else "") else "⌘  Controle"
        } catch (_: Exception) { deviceLabel?.text = "⌘  Controle" }
    }

    private fun openWifiSettings() {
        try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        catch (_: Exception) { openSettings() }
    }

    private fun openPeripheralSettings() {
        try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        catch (_: Exception) { openSettings() }
    }

    /** Android TV pode bloquear o seletor padrão. Nunca desativa o launcher original. */
    private fun setAsDefaultHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val roles = getSystemService(RoleManager::class.java)
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                        Toast.makeText(this, "Esta já é sua tela inicial.", Toast.LENGTH_SHORT).show()
                    } else {
                        startActivity(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    }
                    return
                }
            } catch (_: Exception) { /* Usa opção clássica abaixo. */ }
        }
        try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        catch (_: Exception) {
            Toast.makeText(this,
                "Esta versão do Android TV não oferece alteração de launcher nas Configurações.",
                Toast.LENGTH_LONG).show()
        }
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
        focusStyle(card, "app:" + app.packageName)
        return card
    }

    private fun render() {
        val column = contents ?: return
        val keepFocus = (currentFocus?.tag as? String) ?: lastFocus
        column.removeViews(1, column.childCount - 1)

        column.addView(section("Mais utilizados", "Ordenados pelos seus acessos"),
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

        val selected = rankedApps().take(7)
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
                words("Seus aplicativos mais acessados aparecerão aqui.", 13f, muted),
                params(-2, -2, 9, 28))
        }

        column.addView(section("Aplicativos", mediaApps.size.toString() + " disponíveis"),
            params(-1, -2, 0, 0, 8, 16))

        if (mediaApps.isEmpty()) {
            column.addView(words(
                "Nenhum aplicativo de usuário ou streaming detectado. Use ↻ para atualizar.",
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
            val installed = try { detectInstalledApps() } catch (_: Exception) { emptyList() }
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
     * Mostrar todos os aplicativos instalados pelo usuário (incluindo gerenciadores de
     * arquivos), além dos streamings que vieram de fábrica. Ocultar demais apps de sistema.
     * Apenas activities realmente lançáveis entram na grade.
     */
    private fun detectInstalledApps(): List<TvApp> {
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
                val info = try {
                    @Suppress("DEPRECATION")
                    manager.getApplicationInfo(pkg, 0)
                } catch (_: Exception) { continue }
                val isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                // Mesmo um app de sistema só aparece se for um streaming/mídia conhecido.
                if (isSystemApp && !isStreamingApp(pkg, name, info.category)) continue
                // Home/Configurações mantidos só na central superior.
                if (isInternalLauncherOrSettings(pkg, name)) continue
                val icon = try { entry.loadIcon(manager) } catch (_: Exception) { null }
                found[pkg] = TvApp(name, pkg, ComponentName(pkg, activity.name), icon)
            }
        }
        val comparator = Collator.getInstance(locale)
        return found.values.sortedWith(Comparator { a, b -> comparator.compare(a.name, b.name) })
    }

    private fun isInternalLauncherOrSettings(pkgRaw: String, labelRaw: String): Boolean {
        val pkg = pkgRaw.lowercase(Locale.ROOT)
        val name = labelRaw.lowercase(locale)
        return pkg == packageName || pkg == "com.android.settings" ||
            pkg.startsWith("com.android.settings.") ||
            pkg.contains("tvlauncher") || pkg.contains("leanbacklauncher") ||
            pkg.startsWith("com.tcl.launcher") ||
            pkg.startsWith("com.google.android.apps.tv.launcher") ||
            (name.contains("launcher") && pkg.startsWith("com.google.android"))
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

    /**
     * Ranking sem permissão Usage Access: conta apenas lançamentos feitos neste launcher.
     * Reordena ao voltar para Home. Os dados ficam locais no SharedPreferences.
     */
    private fun rankedApps(): List<TvApp> = mediaApps.sortedWith(
        compareByDescending<TvApp> { prefs.getInt("usage.count." + it.packageName, 0) }
            .thenByDescending { prefs.getLong("usage.time." + it.packageName, 0L) }
            .thenBy { it.name.lowercase(locale) }
    )

    private fun recordLaunch(app: TvApp) {
        val id = app.packageName
        val key = "usage.count." + id
        val current = prefs.getInt(key, 0)
        prefs.edit()
            .putInt(key, if (current == Int.MAX_VALUE) current else current + 1)
            .putLong("usage.time." + id, System.currentTimeMillis())
            .apply()
        usageDirty = true
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
            recordLaunch(app)
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

/** Três paisagens ilustradas locais e 16:9, alternadas somente a cada 45 s.
 * Não carrega fotos em rede, não mantém decoder e só redesenha na troca.
 */
private class AtmosphericBackground(context: Context) : View(context) {
    var scene: Int = 0
        set(value) { field = value % 3; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val sky = when (scene) {
            0 -> intArrayOf(Color.rgb(30, 61, 89), Color.rgb(23, 74, 94), Color.rgb(7, 24, 45))
            1 -> intArrayOf(Color.rgb(60, 46, 82), Color.rgb(24, 75, 107), Color.rgb(10, 24, 51))
            else -> intArrayOf(Color.rgb(84, 61, 69), Color.rgb(60, 85, 113), Color.rgb(13, 28, 49))
        }
        p.shader = LinearGradient(0f, 0f, w, h, sky, null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null

        val haloX = when (scene) { 0 -> .74f; 1 -> .64f; else -> .84f }
        val haloY = when (scene) { 0 -> .23f; 1 -> .34f; else -> .20f }
        p.shader = RadialGradient(w * haloX, h * haloY, h * .54f,
            intArrayOf(Color.argb(190, 238, 190, 154),
                Color.argb(100, 152, 163, 191), Color.TRANSPARENT),
            floatArrayOf(0f, .37f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null

        val mountain = Path().apply {
            moveTo(0f, h * .82f)
            cubicTo(w * .12f, h * .66f, w * .25f, h * .84f, w * .41f, h * .61f)
            cubicTo(w * .55f, h * .46f, w * .61f, h * .75f, w * .73f, h * .63f)
            cubicTo(w * .89f, h * .46f, w * .92f, h * .68f, w, h * .61f)
            lineTo(w, h); lineTo(0f, h); close()
        }
        p.color = when (scene) {
            0 -> Color.rgb(28, 66, 82)
            1 -> Color.rgb(40, 65, 93)
            else -> Color.rgb(71, 72, 89)
        }
        c.drawPath(mountain, p)

        val foregroundHills = Path().apply {
            moveTo(0f, h * .88f)
            cubicTo(w * .22f, h * .78f, w * .29f, h * .95f, w * .46f, h * .83f)
            cubicTo(w * .70f, h * .66f, w * .81f, h * .92f, w, h * .79f)
            lineTo(w, h); lineTo(0f, h); close()
        }
        p.color = when(scene) {
            0 -> Color.rgb(12, 40, 56)
            1 -> Color.rgb(13, 44, 68)
            else -> Color.rgb(35, 48, 67)
        }
        c.drawPath(foregroundHills, p)

        // Escurecimento na área inferior, para manter ícones e textos legíveis.
        p.shader = LinearGradient(0f, h * .33f, 0f, h,
            intArrayOf(Color.argb(115, 3, 11, 19), Color.argb(160, 5, 14, 25),
                Color.argb(232, 4, 11, 19)),
            null, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * .33f, w, h, p)
        p.shader = null
    }
}
