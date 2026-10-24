package com.b5solucoes.tcllight

import android.app.Activity
import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.TextUtils
import android.text.format.Formatter
import android.view.*
import android.widget.*
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.io.File

/** Native TV Home. Optional weather and catalog work never block remote-control dispatch. */
class MainActivity : Activity() {
    private data class TvApp(val name: String, val packageName: String, val component: ComponentName)
    private data class ArtworkBinding(val card: View, val image: ImageView, val placeholder: View,
        val component: ComponentName, val large: Boolean, var visible: Boolean = false,
        var loading: Boolean = false, var hasImage: Boolean = false)
    private val artworkBindings = ArrayList<ArtworkBinding>()
    private val visibleRect = Rect()
    private var artworkUpdatePending = false
    private val updateVisibleArtwork = Runnable {
        artworkUpdatePending = false
        if(foreground) refreshVisibleArtwork()
    }
    private val handler = Handler(Looper.getMainLooper())
    private val locale = Locale("pt", "BR")
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private val lowMemory by lazy { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice }
    private fun economy() = prefs.getBoolean("eco_mode", lowMemory)
    private val ui by lazy { TvStyle(this, ::economy, { prefs.getInt("text_scale",100) / 100f }, { safeHorizontal() to safeVertical() }, { prefs.getInt(LauncherPalettes.PREF,0) }) }
    private val artwork by lazy { AppArtwork(applicationContext) }
    private val scanner = Executors.newSingleThreadExecutor { r -> Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() },"tv-app-catalog") }
    private lateinit var frame: FrameLayout
    private lateinit var homeLayer: LinearLayout
    private lateinit var listScroll: ScrollView
    private lateinit var favorites: LinearLayout
    private lateinit var appGrid: LinearLayout
    private lateinit var favoriteStrip: HorizontalScrollView
    private lateinit var appCount: TextView
    private lateinit var networkLabel: TextView
    private lateinit var networkIcon: ImageView
    private lateinit var deviceLabel: TextView
    private lateinit var weatherLabel: TextView
    private lateinit var weatherIcon: ImageView
    private lateinit var orderLabel: TextView
    private lateinit var controlHint: TextView
    private lateinit var timeLabel: TextView
    private lateinit var dateLabel: TextView
    private lateinit var centralButton: View
    private lateinit var tvHeaderButton: View
    private lateinit var updateButton: View
    private lateinit var shortcutArea: LinearLayout
    private lateinit var shortcutStrip: LinearLayout
    private val shortcutViews = ArrayList<View>()
    private var shortcutSignature = ""
    private var networkStatus = NetworkStatus("Offline", "Sem conexão de rede")
    private var reopenPanel = ""
    private var reopenFocus = ""
    private var backupMessage = "Guarde a organização e a aparência para restaurar depois."
    private var importedBackup: BackupData? = null
    private var backupBusy = false
    private var backgroundTv: BackgroundTv? = null
    private var mediaHub: MediaHub? = null
    private var wallpapers: WallpaperDeck? = null
    private var panel: LauncherPanel? = null
    private var panelBack: (() -> Unit)? = null
    private var activePanel = ""
    private val updates by lazy { AppUpdates.get(applicationContext) }
    private var updateStatus: TextView? = null
    private var updateProgressBar: ProgressBar? = null
    private var updatePhase: UpdatePhase? = null
    private var waitingInstallPermission = false
    private val updateObserver: (UpdateState) -> Unit = { state ->
        updateHeader(state)
        if (activePanel == "updates") {
            if (updatePhase != state.phase) showUpdates()
            else applyUpdateProgress(state)
        }
    }
    private val automaticUpdates=object:Runnable {
        override fun run() {
            if(!foreground) return
            updates.checkAutomatically()
            handler.postDelayed(this,15*60*1000L)
        }
    }
    private var videoInputs: List<TvSource> = emptyList()
    private var inputsLoading = false
    private var inputsAgain = false
    private val tvSources by lazy { TvSources(this, handler, ::refreshInputs) }
    private val weather by lazy { WeatherFeed(prefs, handler) { snapshot ->
        setTextIfChanged(weatherLabel, snapshot?.label(System.currentTimeMillis()) ?: "Clima indisponível")
        val icon = when (snapshot?.code) {
            0 -> TvIcon.Kind.SUN
            51, 53, 55, 61, 63, 65, 80, 81, 82, 95, 96, 99 -> TvIcon.Kind.RAIN
            else -> TvIcon.Kind.CLOUD
        }
        weatherIcon.setImageDrawable(TvIcon(icon, ui.accent))
    } }
    private data class MoveSession(val packageName: String, val lane: String, var ids: List<String>)
    private var moving: MoveSession? = null
    private var consumeKeyUp = KeyEvent.KEYCODE_UNKNOWN
    private val homeViews = LinkedHashMap<String,View>()
    private val navViews = ArrayList<View>()
    private val topViews = ArrayList<View>()
    private val gridViews = ArrayList<View>()
    private var topSignature = ""
    private var gridSignature = ""
    private var lastHomeFocus = ""
    private var returnFocus = ""
    private var mediaApps: List<TvApp> = emptyList()
    private var scanning = false
    private var scanAgain = false
    private var appsDirty = true
    private var catalogRevision = 0
    private var usageDirty = false
    private var foreground = false
    private var firstData = true
    private var userNavigated = false
    private var packageReceiverRegistered = false
    private var networkRegistered = false
    private val clockFormat = SimpleDateFormat("HH:mm",locale)
    private val dateFormat = SimpleDateFormat("EEE, d 'de' MMM",locale)

    private val clockUpdate = object : Runnable {
        override fun run() {
            if(!foreground) return
            val now = Date()
            setTextIfChanged(timeLabel,clockFormat.format(now))
            setTextIfChanged(dateLabel,dateFormat.format(now).replaceFirstChar { it.titlecase(locale) })
            handler.postDelayed(this,60000L - System.currentTimeMillis() % 60000L)
        }
    }
    private val packageEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            appsDirty = true
            catalogRevision++
            artwork.invalidate()
            if(foreground) refresh()
        }
    }
    private val networkEvents = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { handler.post { if(foreground) refreshStatus() } }
        override fun onLost(network: Network) { handler.post { if(foreground) refreshStatus() } }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            handler.post { if(foreground) refreshStatus() }
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            handler.post { if(foreground) refreshStatus() }
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        waitingInstallPermission = state?.getBoolean("update_permission_pending") == true
        reopenPanel = state?.getString("feature_panel", "") ?: ""
        reopenFocus = state?.getString("feature_focus", "") ?: ""
        backupMessage = state?.getString("backup_message") ?: backupMessage
        window.statusBarColor = ui.base
        window.navigationBarColor = ui.base
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        buildInterface()
        mediaHub?.query=state?.getString("media_query").orEmpty()
        handler.post {
            when (reopenPanel) {
                "display" -> showDisplaySettings(reopenFocus)
                "backup" -> showBackup()
                "appearance" -> showPreferences(reopenFocus)
            }
            reopenPanel = ""; reopenFocus = ""
            if(intent?.action == MediaReminders.ACTION_OPEN) intent.getStringExtra("reminder_id")?.let { mediaHub?.showReminder(it) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        @Suppress("DEPRECATION")
        registerReceiver(packageEvents,filter)
        packageReceiverRegistered = true
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        handler.post(clockUpdate)
        refreshStatus()
        wallpapers?.onForeground()
        registerStatusListeners()
        tvSources.start()
        refreshInputs()
        weather.start()
        updates.observe(updateObserver)
        handler.postDelayed(automaticUpdates,5000L)
        if(appsDirty) refresh()
        if(usageDirty) { usageDirty = false; renderApps() }
        requestArtworkUpdate()
        refreshBackgroundTv()
        mediaHub?.start()
    }

    override fun onStop() {
        finishMoving(true)
        foreground = false
        backgroundTv?.stop()
        mediaHub?.stop()
        handler.removeCallbacks(clockUpdate)
        wallpapers?.onBackground()
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        if(networkRegistered) { try { cm.unregisterNetworkCallback(networkEvents) } catch(_: Exception) {} }
        tvSources.stop()
        weather.stop()
        updates.removeObserver(updateObserver)
        handler.removeCallbacks(automaticUpdates)
        networkRegistered = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("update_permission_pending", waitingInstallPermission)
        outState.putString("feature_panel", reopenPanel)
        outState.putString("feature_focus", reopenFocus)
        outState.putString("backup_message", backupMessage)
        outState.putString("media_query",mediaHub?.query.orEmpty())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if(packageReceiverRegistered) unregisterReceiver(packageEvents)
        handler.removeCallbacksAndMessages(null)
        scanner.shutdownNow()
        artwork.close()
        wallpapers?.close()
        weather.close()
        backgroundTv?.stop()
        mediaHub?.close()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(level >= TRIM_MEMORY_RUNNING_LOW) artwork.trim()
        if(level >= TRIM_MEMORY_RUNNING_LOW) mediaHub?.trim()
        if(level >= TRIM_MEMORY_RUNNING_LOW && backgroundTv?.state == BackgroundTvState.PLAYING)
            backgroundTv?.fail("A TV ao fundo foi encerrada para liberar memória.")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        finishMoving(true)
        closePanel()
        if(intent.action == MediaReminders.ACTION_OPEN) {
            intent.getStringExtra("reminder_id")?.let { mediaHub?.showReminder(it) }
            return
        }
        listScroll.smoothScrollTo(0,0)
        firstHomeView().requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == consumeKeyUp) {
            consumeKeyUp = KeyEvent.KEYCODE_UNKNOWN; return true
        }
        if (moving != null) {
            val code = event.keyCode
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.action == KeyEvent.ACTION_DOWN) moveSelection(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                return true
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) return true
            if (code in intArrayOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK)) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    finishMoving(code != KeyEvent.KEYCODE_BACK); consumeKeyUp = code
                }
                return true
            }
        }
        if(event.action == KeyEvent.ACTION_DOWN) {
            if(event.keyCode == KeyEvent.KEYCODE_MENU) {
                if(event.repeatCount == 0) { finishMoving(true); if(panel == null) showActionCenter() else closePanel() }
                return true
            }
            if(event.keyCode in intArrayOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)) userNavigated = true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Legacy Android TV back handling")
    override fun onBackPressed() {
        if (moving != null) finishMoving(false)
        else if(panel != null) { val back = panelBack; if(back != null) back() else closePanel() }
        else {
            listScroll.smoothScrollTo(0,0)
            firstHomeView().requestFocus()
        }
    }

    private fun dp(value: Int) = ui.dp(value)
    private fun safeHorizontal() = prefs.getInt("safe_horizontal",42).coerceIn(24,72)
    private fun safeVertical() = prefs.getInt("safe_vertical",8).coerceIn(0,24)
    private fun firstHomeView(): View = topViews.firstOrNull() ?: shortcutViews.firstOrNull() ?: gridViews.firstOrNull() ?: mediaHub?.homeRows?.firstOrNull()?.firstOrNull() ?: centralButton
    private fun lp(w: Int,h: Int,l: Int=0,t: Int=0,r: Int=0,b: Int=0) =
        LinearLayout.LayoutParams(w,h).apply { setMargins(dp(l),dp(t),dp(r),dp(b)) }

    private fun buildInterface() {
        frame = FrameLayout(this).apply { setBackgroundColor(ui.base) }
        wallpapers = WallpaperDeck(this,prefs,::economy)
        frame.addView(wallpapers,FrameLayout.LayoutParams(-1,-1))
        homeLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = true; clipToPadding = true
            setPadding(0,dp(safeVertical()),0,dp(safeVertical()))
        }
        frame.addView(homeLayer,FrameLayout.LayoutParams(-1,-1))
        setContentView(frame)

        val nav = LinearLayout(this).apply {
            tag = "home:header"
            gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(dp(safeHorizontal()), dp(10), dp(safeHorizontal()), dp(10))
            elevation = dp(4).toFloat()
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(ui.mix(ui.base,ui.panelColor,.65f),ui.base))
        }
        homeLayer.addView(nav,lp(-1,ui.height(64)))
        val climate = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        weatherIcon = ui.iconBadge(TvIcon.Kind.CLOUD,34)
        climate.addView(weatherIcon,lp(dp(34),dp(34),0,0,10))
        val climateText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        climateText.addView(ui.text("Garrafão do Norte",10.5f,ui.muted).apply { maxLines = 1 })
        weatherLabel = ui.text("Clima indisponível",13f,medium=true).apply {
            tag = "header:weather"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        climateText.addView(weatherLabel,lp(-1,-2,0,3))
        climate.addView(climateText,LinearLayout.LayoutParams(0,-2,1f))
        nav.addView(climate,lp(dp(205),-2))
        nav.addView(View(this).apply { setBackgroundColor(ui.alpha(ui.accent,42)) },lp(dp(1),dp(27),12,0,18))
        val clock = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        timeLabel = ui.text("--:--",30f,medium=true).apply { tag = "header:time"; maxLines = 1; fontFeatureSettings="tnum";letterSpacing=-.025f }
        dateLabel = ui.text("",10.5f,ui.muted).apply { tag = "header:date"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        clock.addView(timeLabel)
        clock.addView(dateLabel,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(12) })
        nav.addView(clock,LinearLayout.LayoutParams(0,-2,1f))
        tvHeaderButton=navControl("nav:tv",TvIcon.Kind.TV,"TV e canais",ui.text("TV",11f),iconOnly=true) { openTv() }
        tvHeaderButton.setOnLongClickListener { showShortcutOptions(shortcuts().firstOrNull { it.key=="tv" } ?: HomeShortcut("tv"));true }
        nav.addView(tvHeaderButton,lp(dp(34),dp(36),6,0,7));navViews.add(tvHeaderButton)
        val search=navControl("nav:search",TvIcon.Kind.SEARCH,"Pesquisar filmes e séries",ui.text("Buscar",11f),iconOnly=true) { mediaHub?.showSearchFromHeader() }
        nav.addView(search,lp(dp(39),dp(39),0,0,7));navViews.add(search)
        networkLabel = ui.text("Rede",11f)
        networkIcon = ImageView(this)
        val net = navControl("nav:wifi",TvIcon.Kind.WIFI,"Conexão de rede",networkLabel,networkIcon,true) { showNetwork() }
        nav.addView(net,lp(dp(39),dp(39),0,0,7)); navViews.add(net)
        deviceLabel = ui.text("Entradas",11f)
        val devices = navControl("nav:inputs",TvIcon.Kind.CABLE,"Entradas de vídeo",deviceLabel,iconOnly=true) { showInputs() }
        nav.addView(devices,lp(dp(39),dp(39),0,0,7)); navViews.add(devices)
        updateButton=navControl("nav:update",TvIcon.Kind.REFRESH,"Atualização disponível",ui.text("Atualizar",11f),iconOnly=true) { showUpdates() }.apply { visibility=View.GONE;isSelected=true }
        nav.addView(updateButton,lp(dp(39),dp(39),0,0,7));navViews.add(updateButton)
        centralButton = navControl("nav:central",TvIcon.Kind.CONTROLS,"Central de ações",ui.text("Central",11f,medium=true)) { showActionCenter() }
        nav.addView(centralButton,lp(dp(103),dp(39))); navViews.add(centralButton)

        listScroll = ScrollView(this).apply {
            tag = "home:viewport"
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            // Focused/scrolled tiles must never draw into the fixed header or footer.
            clipChildren = true; clipToPadding = true; isFillViewport = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        homeLayer.addView(listScroll,LinearLayout.LayoutParams(-1,0,1f))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(dp(safeHorizontal()),dp(6),dp(safeHorizontal()),dp(14))
        }
        listScroll.addView(column)
        listScroll.viewTreeObserver.addOnScrollChangedListener { requestArtworkUpdate();mediaHub?.refreshVisible() }
        val sceneSpace = ((resources.configuration.screenHeightDp-480)*.12f).toInt().coerceIn(8,28)
        column.addView(View(this),lp(-1,dp(sceneSpace)))
        shortcutArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sourcesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false
        }
        shortcutStrip = LinearLayout(this).apply { setPadding(dp(4),dp(4),dp(4),dp(4)); clipChildren = false }
        sourcesScroll.addView(shortcutStrip)
        shortcutArea.addView(sourcesScroll,lp(-1,-2))
        column.addView(shortcutArea,lp(-1,-2,0,0,0,8))
        renderShortcuts()
        val usedHeader = section("Mais utilizados","Pelo seu uso")
        orderLabel = (usedHeader as LinearLayout).getChildAt(1) as TextView
        column.addView(usedHeader,lp(-1,ui.height(26),0,0,3,5))
        favoriteStrip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false
        }
        favorites = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
            setPadding(dp(4),dp(5),dp(4),dp(5))
        }
        favoriteStrip.addView(favorites)
        column.addView(favoriteStrip,lp(-1,-2,0,0,0,10))
        val mediaArea=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;clipChildren=false;clipToPadding=false }
        column.addView(mediaArea,lp(-1,-2,0,0,0,10))
        val appHeader = section("Seus aplicativos","")
        appCount = (appHeader as LinearLayout).getChildAt(1) as TextView
        column.addView(appHeader,lp(-1,ui.height(26),0,0,3,5))
        appGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(dp(4),dp(4),dp(4),dp(5))
        }
        column.addView(appGrid,lp(-1,-2))
        favorites.addView(ui.text("Carregando aplicativos…",12f,ui.muted),lp(-1,dp(110)))
        val footer = LinearLayout(this).apply { tag = "home:footer"; gravity = Gravity.CENTER_VERTICAL }
        controlHint = ui.text("OK  Abrir    ·    Segure OK  Mover e opções",9.5f,ui.quiet).apply { tag = "home:hint"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        footer.addView(controlHint,
            LinearLayout.LayoutParams(0,-2,1f))
        footer.addView(ui.text("MENU  Central de ações",9.5f,ui.muted))
        homeLayer.addView(footer,lp(-1,ui.height(24),safeHorizontal()+4,0,safeHorizontal()+4,4))
        mediaHub=MediaHub(this,ui,prefs,
            { mediaApps.filterNot { it.packageName in hiddenApps() }.map { MediaApp(it.name,it.packageName) } },
            { title,subtitle,back->newPanel(title,subtitle,back).also { activePanel="media" } },
            { panel != null && activePanel=="media" },{ showActionCenter("action:media") },::openTv,{ view,key->styleHome(view,key) },
            { homeViews.keys.filter { it.startsWith("media-home:") }.toList().forEach { homeViews.remove(it) } },
            { wireNavigation(when(prefs.getInt("icon_scale",1)){0->6;2->4;else->5}) },::closePanel)
        mediaHub?.attachHome(mediaArea)
        centralButton.requestFocus()
    }

    private fun section(title: String,meta: String): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.text(title,17f,medium=true).apply {
            val marker=ui.surface(ui.accent,2).apply { setBounds(0,0,dp(3),dp(14)) }
            setCompoundDrawables(marker,null,null,null);compoundDrawablePadding=dp(9)
        },LinearLayout.LayoutParams(0,-2,1f))
        addView(ui.text(meta,9.5f,ui.muted).apply {
            background=ui.surface(ui.alpha(ui.panelColor,215),6,ui.alpha(ui.accent,24))
            setPadding(dp(8),dp(4),dp(8),dp(4))
        })
    }

    private fun navControl(key: String,icon: TvIcon.Kind,label: String,text: TextView,
                           image: ImageView = ImageView(this),iconOnly: Boolean=false,action: () -> Unit): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER
            image.setImageDrawable(TvIcon(icon,ui.muted))
            addView(image,lp(dp(19),dp(19),0,0,if(iconOnly) 0 else 7))
            text.maxLines = 1; text.ellipsize = TextUtils.TruncateAt.END
            if(!iconOnly) addView(text,lp(-2,-2))
            contentDescription = label
            styleHome(this,key)
            setOnClickListener { action() }
        }

    private fun styleHome(view: View,key: String,transparent: Boolean = false) {
        ui.focus(view,key,14,transparent) { focused ->
            if (focused) {
                lastHomeFocus = key
                if (key.startsWith("grid:") || key.startsWith("top:") || key.startsWith("shortcut:") || key.startsWith("media-home:")) view.post {
                    if (view.hasFocus() && panel == null) {
                        // Include the focus outline in the native scroll request.
                        view.requestRectangleOnScreen(Rect(0, -dp(5), view.width, view.height + dp(5)), economy())
                    }
                }
            }
        }
        homeViews[key] = view
    }

    private fun appTile(app: TvApp,large: Boolean,widthDp: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            clipChildren = false; clipToPadding = false
            setPadding(dp(5),dp(5),dp(5),dp(5))
            contentDescription = app.name
        }
        val artHeight = (widthDp-10)*9/16
        val surface = FrameLayout(this).apply {
            background = ui.cardSurface(10)
            clipToOutline = true
        }
        val fallback = ui.text(app.name.take(1).uppercase(locale),27f,ui.muted,true).apply { gravity = Gravity.CENTER }
        surface.addView(fallback,FrameLayout.LayoutParams(-1,-1))
        val image = ImageView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        surface.addView(image,FrameLayout.LayoutParams(-1,-1))
        artworkBindings.add(ArtworkBinding(card,image,fallback,app.component,large))
        if(app.packageName in pinnedApps()) surface.addView(ImageView(this).apply {
            setImageDrawable(TvIcon(TvIcon.Kind.PIN,ui.accent))
            background = ui.surface(ui.alpha(ui.base,238),6,ui.alpha(ui.accent,45))
            setPadding(dp(4),dp(4),dp(4),dp(4))
        },FrameLayout.LayoutParams(dp(23),dp(23),Gravity.TOP or Gravity.END).apply {
            setMargins(0,dp(5),dp(5),0)
        })
        card.addView(surface,lp(-1,dp(artHeight)))
        val key = (if(large) "top:" else "grid:") + app.packageName
        styleHome(card,key,true)
        if (moving?.packageName == app.packageName && moving?.lane == (if (large) "top" else "grid")) {
            card.background = ui.surface(ui.alpha(ui.accent,65), 14, ui.accent)
        }
        card.setOnClickListener { open(app) }
        card.setOnLongClickListener { card.requestFocus(); showAppMenu(app, if (large) "top" else "grid"); true }
        return card
    }

    /** Rebuild only the row whose order/content changed, keeping the other views alive. */
    private fun renderApps() {
        val keep = (currentFocus?.tag as? String)?.takeIf { panel == null } ?: lastHomeFocus
        val hidden = hiddenApps()
        val visible = ordered(mediaApps.filterNot { it.packageName in hidden }, "grid")
        val selected = ordered(rankedApps(visible), "top").take(prefs.getInt("top_count",7).coerceIn(5,10))
        val scale = prefs.getInt("icon_scale",1)
        val pinKey = pinnedApps().sorted().joinToString()
        val moveKey = moving?.let { it.lane + it.packageName } ?: ""
        val nextTop = selected.joinToString { it.packageName } + "|" + scale + "|" + pinKey + "|" + catalogRevision + "|" + if (moving?.lane == "top") moveKey else ""
        val nextGrid = visible.joinToString { it.packageName + it.name } + "|" + scale + "|" + pinKey + "|" + catalogRevision + "|" + if (moving?.lane == "grid") moveKey else ""
        val changed = firstData || topSignature != nextTop || gridSignature != nextGrid
        val topWidth = when(scale) { 0 -> 134; 2 -> 174; else -> 152 }
        if(firstData || topSignature != nextTop) {
            topSignature = nextTop
            topViews.forEach { homeViews.remove(it.tag.toString()) }; topViews.clear()
            favorites.removeAllViews()
            for(app in selected) {
                val tile = appTile(app,true,topWidth)
                topViews.add(tile)
                favorites.addView(tile,lp(dp(topWidth),-2,0,0,10))
            }
            if(selected.isEmpty()) favorites.addView(ui.text("Seus aplicativos mais usados aparecerão aqui.",12f,ui.muted),
                lp(-1,dp(110),8,14))
        }
        val columns = when(scale) { 0 -> 6; 2 -> 4; else -> 5 }
        if(firstData || gridSignature != nextGrid) {
            gridSignature = nextGrid
            gridViews.forEach { homeViews.remove(it.tag.toString()) }; gridViews.clear()
            appGrid.removeAllViews()
            val tileWidth = (resources.configuration.screenWidthDp-safeHorizontal()*2-8-(columns-1)*12)/columns
            for(group in visible.chunked(columns)) {
                val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
                for((index,app) in group.withIndex()) {
                    val tile = appTile(app,false,tileWidth)
                    gridViews.add(tile)
                    row.addView(tile,LinearLayout.LayoutParams(0,-2,1f).apply {
                        if(index < columns-1) rightMargin = dp(12)
                    })
                }
                repeat(columns-group.size) { index ->
                    row.addView(View(this),LinearLayout.LayoutParams(0,1,1f).apply {
                        if(group.size+index < columns-1) rightMargin = dp(12)
                    })
                }
                appGrid.addView(row,lp(-1,-2,0,0,0,10))
            }
            if(visible.isEmpty()) {
                val empty = ui.text("Instale seus aplicativos de streaming ou use a central para abrir a TV.",13f,ui.muted)
                appGrid.addView(empty,lp(-1,-2,8,8,8,15))
            }
        }
        setTextIfChanged(appCount,visible.size.toString() + if(visible.size == 1) " aplicativo" else " aplicativos")
        setTextIfChanged(orderLabel, if (orderFor("top").isEmpty()) "Pelo seu uso" else "Sua ordem")
        mediaHub?.appsChanged()
        wireNavigation(columns)
        homeLayer.post { requestArtworkUpdate() }
        val initial = firstData
        firstData = false
        if(changed && panel == null) {
            val previousFocus=currentFocus
            val target = if(initial && !userNavigated) firstHomeView()
                else homeViews[keep] ?: firstHomeView()
            homeLayer.post {
                if(!isDestroyed && panel == null && target?.isAttachedToWindow == true &&
                    (currentFocus == null || currentFocus === previousFocus || currentFocus?.isAttachedToWindow == false)) target.requestFocus()
            }
        }
    }

    private fun requestArtworkUpdate() {
        if(!artworkUpdatePending && foreground && !isDestroyed) {
            artworkUpdatePending = true
            handler.postDelayed(updateVisibleArtwork,16L)
        }
    }

    /** Only visible cards own a bitmap; offscreen rows return to the bounded cache. */
    private fun refreshVisibleArtwork() {
        // Grid cards still have a row parent after that whole row is removed.
        // Release those bindings as well, especially while a user rearranges apps.
        artworkBindings.removeAll { it.card.rootView !== frame.rootView }
        for(binding in artworkBindings) {
            binding.visible = binding.image.isShown && binding.image.getGlobalVisibleRect(visibleRect)
            if(!binding.visible) {
                if(binding.hasImage) {
                    binding.image.setImageDrawable(null)
                    binding.placeholder.visibility = View.VISIBLE
                    binding.hasImage = false
                }
            } else if(!binding.loading && !binding.hasImage) {
                binding.loading = true
                artwork.load(binding.component) { art ->
                    binding.loading = false
                    if(art != null && binding.visible && binding.card.isAttachedToWindow && !isDestroyed) {
                        binding.placeholder.visibility = View.GONE
                        binding.image.scaleType = ImageView.ScaleType.FIT_CENTER
                        val padding = if(art.banner) 0 else dp(if(binding.large) 14 else 16)
                        binding.image.setPadding(padding,padding,padding,padding)
                        binding.image.setImageBitmap(art.bitmap)
                        binding.hasImage = true
                    }
                }
            }
        }
    }

    private fun wireNavigation(columns: Int) {
        val rows=mediaHub?.homeRows.orEmpty()
        val firstMedia=rows.firstOrNull().orEmpty()
        val lastMedia=rows.lastOrNull().orEmpty()
        val first = shortcutViews.firstOrNull() ?: topViews.firstOrNull() ?: firstMedia.firstOrNull() ?: gridViews.firstOrNull() ?: centralButton
        val visibleNav=navViews.filter { it.visibility==View.VISIBLE }
        visibleNav.forEachIndexed { i,v ->
            v.nextFocusLeftId = visibleNav[(i-1).coerceAtLeast(0)].id
            v.nextFocusRightId = visibleNav[(i+1).coerceAtMost(visibleNav.lastIndex)].id
            v.nextFocusDownId = first.id
        }
        shortcutViews.forEachIndexed { i,v ->
            v.nextFocusLeftId = shortcutViews[(i-1).coerceAtLeast(0)].id
            v.nextFocusRightId = shortcutViews[(i+1).coerceAtMost(shortcutViews.lastIndex)].id
            v.nextFocusUpId = centralButton.id
            v.nextFocusDownId = topViews.getOrNull(i.coerceAtMost(topViews.lastIndex))?.id ?: firstMedia.firstOrNull()?.id ?: gridViews.firstOrNull()?.id ?: v.id
        }
        topViews.forEachIndexed { i,v ->
            v.nextFocusUpId = shortcutViews.getOrNull(i.coerceAtMost(shortcutViews.lastIndex))?.id ?: centralButton.id
            v.nextFocusLeftId = topViews[(i-1).coerceAtLeast(0)].id
            v.nextFocusRightId = topViews[(i+1).coerceAtMost(topViews.lastIndex)].id
            v.nextFocusDownId = firstMedia.getOrNull(i.coerceAtMost(firstMedia.lastIndex))?.id ?: gridViews.getOrNull(i.coerceAtMost(gridViews.lastIndex))?.id ?: v.id
        }
        rows.forEachIndexed { rowIndex,row->row.forEachIndexed { index,v->
            val before=if(rowIndex>0) rows[rowIndex-1] else topViews.ifEmpty { shortcutViews }
            val after=if(rowIndex<rows.lastIndex) rows[rowIndex+1] else gridViews
            v.nextFocusLeftId=row[(index-1).coerceAtLeast(0)].id
            v.nextFocusRightId=row[(index+1).coerceAtMost(row.lastIndex)].id
            v.nextFocusUpId=before.getOrNull(index.coerceAtMost(before.lastIndex))?.id ?: centralButton.id
            v.nextFocusDownId=after.getOrNull(index.coerceAtMost(after.lastIndex))?.id ?: v.id
        } }
        gridViews.forEachIndexed { i,v ->
            v.nextFocusLeftId = if(i % columns > 0) gridViews[i-1].id else v.id
            v.nextFocusRightId = if(i % columns < columns-1) gridViews.getOrNull(i+1)?.id ?: v.id else v.id
            v.nextFocusUpId = if(i >= columns) gridViews[i-columns].id
                else lastMedia.getOrNull(i.coerceAtMost(lastMedia.lastIndex))?.id ?: topViews.getOrNull(i.coerceAtMost(topViews.lastIndex))?.id ?: shortcutViews.firstOrNull()?.id ?: centralButton.id
            v.nextFocusDownId = gridViews.getOrNull(i+columns)?.id ?: v.id
        }
    }

    private fun refresh() {
        if(scanning) { scanAgain = true; return }
        scanning = true; appsDirty = false
        scanner.execute {
            val installed = try { detectInstalledApps() } catch(_: Exception) { null }
            handler.post {
                scanning = false
                if(isDestroyed || isFinishing) return@post
                if(installed != null) { mediaApps = installed; renderApps() }
                else {
                    appsDirty = true
                    Toast.makeText(this,"Não foi possível atualizar os aplicativos.",Toast.LENGTH_SHORT).show()
                }
                if(scanAgain) { scanAgain = false; refresh() }
            }
        }
    }

    private fun registerStatusListeners() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            if(Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(networkEvents)
            else cm.registerNetworkCallback(NetworkRequest.Builder().build(),networkEvents)
            networkRegistered = true
        } catch(_: Exception) {}
    }

    private fun setTextIfChanged(label: TextView,value: String) { if(label.text.toString() != value) label.text = value }

    private fun networkType(): Int = try {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val active = cm.activeNetworkInfo
        if(active?.isConnected == true) active.type else -1
    } catch(_: Exception) { -1 }

    private fun refreshStatus() {
        if(!::networkLabel.isInitialized) return
        val network = networkType()
        setTextIfChanged(networkLabel,when(network) {
            ConnectivityManager.TYPE_WIFI -> "Wi-Fi"
            ConnectivityManager.TYPE_ETHERNET -> "Cabo"
            -1 -> "Offline"
            else -> "Rede"
        })
        networkIcon.setImageDrawable(TvIcon(if(network == ConnectivityManager.TYPE_ETHERNET)
            TvIcon.Kind.CABLE else TvIcon.Kind.WIFI,if(network == -1) ui.quiet else ui.accent))
        val next = NetworkStatus.read(getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
        if (next != networkStatus) {
            networkStatus = next
            if (activePanel == "network") showNetwork(currentFocus?.tag as? String)
        }
    }

    private fun wifiState() = when(networkType()) {
        ConnectivityManager.TYPE_WIFI -> "Wi-Fi conectado"
        ConnectivityManager.TYPE_ETHERNET -> "Cabo conectado"
        -1 -> "Sem conexão"
        else -> "Rede conectada"
    }

    private fun refreshInputs() {
        if (!foreground || isDestroyed) return
        if (inputsLoading) { inputsAgain = true; return }
        inputsLoading = true
        scanner.execute {
            val entries = tvSources.read()
            handler.post {
                inputsLoading = false
                if (isDestroyed) return@post
                videoInputs = entries
                renderShortcuts()
                refreshBackgroundTv()
                if (foreground && activePanel == "inputs") {
                    val focus = currentFocus?.tag as? String
                    showInputs(focus)
                }
                if (inputsAgain) { inputsAgain = false; refreshInputs() }
            }
        }
    }

    private fun newPanel(title: String,subtitle: String,back: (() -> Unit)? = null): LauncherPanel {
        if(activePanel=="media") mediaHub?.panelClosed()
        activePanel = ""
        updateStatus = null
        updateProgressBar = null
        if(panel == null) returnFocus = (currentFocus?.tag as? String) ?: lastHomeFocus
        panel?.let { frame.removeView(it) }
        homeLayer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        homeLayer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        panelBack = back
        return LauncherPanel(ui,title,subtitle,::closePanel).also {
            panel = it
            frame.addView(it,FrameLayout.LayoutParams(-1,-1))
            if(!economy()) { it.alpha = 0f; it.animate().alpha(1f).setDuration(140L).start() }
        }
    }

    private fun closePanel() {
        val old = panel ?: return
        mediaHub?.panelClosed()
        old.animate().cancel()
        frame.removeView(old); panel = null; panelBack = null; activePanel = ""
        updateStatus = null; updateProgressBar = null
        homeLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        homeLayer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        (homeViews[returnFocus]?.takeIf { it.isShown } ?: firstHomeView()).requestFocus()
    }

    private fun showActionCenter(focus: String? = null) {
        val sheet = newPanel("Central de ações","Conexões e atalhos da sua TV")
        sheet.grid(listOf(
            PanelAction("action:wifi","Rede",wifiState(),TvIcon.Kind.WIFI,networkType() != -1) { showNetwork() },
            PanelAction("action:inputs","Entradas","HDMI, antena e vídeo",TvIcon.Kind.CABLE) { showInputs() },
            PanelAction("action:tv","TV e canais","Abrir televisão",TvIcon.Kind.TV) { openTv() },
            PanelAction("action:eco","Modo leve",if(economy()) "Ativado" else "Desativado",TvIcon.Kind.LEAF,economy()) {
                toggleEconomy(); showActionCenter("action:eco")
            }
        ))
        try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val free = Formatter.formatShortFileSize(this,stat.availableBytes)
            val total = Formatter.formatShortFileSize(this,stat.totalBytes)
            sheet.storage(free + " livres de " + total,1f-stat.availableBytes.toFloat()/stat.totalBytes.coerceAtLeast(1L))
        } catch(_: Exception) {}
        sheet.row(PanelAction("action:update", "Atualizar TCL Light", "Versão ${BuildConfig.VERSION_NAME} · Verificar atualizações", TvIcon.Kind.REFRESH) { showUpdates() })
        sheet.row(PanelAction("action:media","Multimídia e programação","Sua lista, busca e lembretes",TvIcon.Kind.PLAY) { mediaHub?.show() })
        sheet.caption("Mais controles")
        sheet.row(PanelAction("action:shortcuts", "Atalhos de TV e entradas", "Fixar e organizar na tela inicial", TvIcon.Kind.PIN) { showShortcutPicker() })
        sheet.row(PanelAction("action:backup", "Backup local", "Salvar e restaurar sua configuração", TvIcon.Kind.STORAGE) { showBackup() })
        if (mediaApps.any { it.packageName == "com.android.vending" })
            sheet.row(PanelAction("action:store", "Google Play Store", "Instalar e atualizar seus aplicativos", TvIcon.Kind.GRID) { openPlayStore() })
        sheet.row(PanelAction("action:customize","Personalizar aparência","Fundos, cartões e mais utilizados",TvIcon.Kind.PALETTE) { showPreferences() })
        sheet.row(PanelAction("action:settings","Configurações da TV","Imagem, áudio e sistema",TvIcon.Kind.CONTROLS) { openSettings() })
        sheet.row(PanelAction("action:bluetooth","Controles e Bluetooth","Parear acessórios",TvIcon.Kind.DEVICES) { openPeripheralSettings() })
        sheet.row(PanelAction("action:home","Tela inicial padrão",currentHome(),TvIcon.Kind.HOME) { showHomeAssistant() })
        sheet.row(PanelAction("action:storage","Armazenamento","Gerenciar espaço na TV",TvIcon.Kind.STORAGE) { openStorageSettings() })
        sheet.row(PanelAction("action:hidden","Aplicativos ocultos",hiddenApps().size.toString() + " ocultos por você",TvIcon.Kind.EYE) { showHiddenApps() })
        sheet.row(PanelAction("action:refresh","Atualizar aplicativos","Procurar instalações e alterações",TvIcon.Kind.REFRESH) {
            artwork.invalidate(); topSignature = ""; gridSignature = ""; refresh()
            Toast.makeText(this,"Atualizando aplicativos…",Toast.LENGTH_SHORT).show()
        })
        sheet.focusAction(focus)
    }

    private fun updateHeader(state: UpdateState) {
        if(!::updateButton.isInitialized) return
        val available=state.release?.versionCode?.let { it>BuildConfig.VERSION_CODE }==true
        val visible=if(available) View.VISIBLE else View.GONE
        if(!available && updateButton.hasFocus()) homeViews["nav:search"]?.requestFocus()
        updateButton.contentDescription=if(available) "Atualização disponível · ${state.release!!.versionName}" else "Atualizações"
        if(updateButton.visibility!=visible) {
            updateButton.visibility=visible
            wireNavigation(when(prefs.getInt("icon_scale",1)) {0->6;2->4;else->5})
        }
    }

    private fun showUpdates() {
        val state = updates.state
        val sheet = newPanel("Atualizações", "TCL Light ${BuildConfig.VERSION_NAME}") { showActionCenter("action:update") }
        activePanel = "updates"
        updatePhase = state.phase
        state.release?.let { release ->
            sheet.caption("Versão ${release.versionName} · ${Formatter.formatShortFileSize(this, release.size)}")
        }
        updateStatus = sheet.notice(state.message,TvIcon.Kind.REFRESH).apply { tag = "update:status" }
        updateProgressBar = if (state.phase in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) sheet.progress() else null
        applyUpdateProgress(state)
        when (state.phase) {
            UpdatePhase.IDLE, UpdatePhase.CURRENT -> sheet.row(PanelAction("update:check", "Verificar atualizações", "Buscar a versão mais recente no GitHub", TvIcon.Kind.REFRESH) { updates.check() })
            UpdatePhase.AVAILABLE -> sheet.row(PanelAction("update:download", "Baixar versão ${state.release!!.versionName}", "Download direto do GitHub", TvIcon.Kind.REFRESH) { updates.download() })
            UpdatePhase.DOWNLOADING -> sheet.row(PanelAction("update:cancel", "Cancelar download", "Você pode baixar novamente depois", TvIcon.Kind.CLOSE) { updates.cancel() })
            UpdatePhase.READY -> sheet.row(PanelAction("update:install", "Instalar atualização", "Confirmar no instalador da TV", TvIcon.Kind.CHECK) { requestUpdateInstall() })
            UpdatePhase.ERROR -> sheet.row(PanelAction("update:retry", "Tentar novamente", if (state.release == null) "Verificar a atualização" else "Baixar o arquivo novamente", TvIcon.Kind.REFRESH) {
                if (updates.state.release == null) updates.check() else updates.download()
            })
            else -> Unit
        }
        sheet.paragraph("Sua organização e seus ajustes são mantidos. A TV pedirá sua confirmação antes de instalar.")
        sheet.focusAction()
    }

    private fun applyUpdateProgress(state: UpdateState) {
        updateStatus?.let { setTextIfChanged(it, state.message + if (state.phase == UpdatePhase.DOWNLOADING) " ${state.percent}%" else "") }
        updateProgressBar?.apply {
            isIndeterminate = state.phase != UpdatePhase.DOWNLOADING
            progress = state.percent
        }
    }

    @Suppress("DEPRECATION")
    private fun requestUpdateInstall() {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            waitingInstallPermission = true
            try {
                startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")), 801)
            } catch (_: Exception) {
                waitingInstallPermission = false
                updates.installMessage("Abra os ajustes de segurança da TV e permita instalações pelo TCL Light. Depois, escolha Instalar atualização novamente.")
                try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Exception) {}
            }
            return
        }
        updates.prepareInstall {
            if (!foreground || activePanel != "updates") return@prepareInstall
            val intent = UpdateApkProvider.installIntent(this)
            try { startActivityForResult(intent, 802) }
            catch (_: ActivityNotFoundException) {
                // Older vendor installers may only advertise the standard APK VIEW action.
                try { startActivityForResult(Intent(intent).setAction(Intent.ACTION_VIEW), 802) }
                catch (_: Exception) { updates.installMessage("O instalador da TV não está disponível. Tente novamente após reiniciar a TV.") }
            }
            catch (_: Exception) { updates.installMessage("Não foi possível abrir o instalador da TV. Tente novamente.") }
        }
    }

    @Deprecated("Legacy TV installer result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode == 910) { mediaHub?.onVoiceResult(resultCode,data);return }
        if (requestCode == 901 || requestCode == 902) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri?.scheme == "content") {
                if (requestCode == 901) backupWork({
                    val bytes = LauncherBackup.export(prefs, lowMemory)
                    (contentResolver.openOutputStream(uri, "w") ?: throw java.io.IOException()).use { it.write(bytes) }
                }) { backupMessage = "Backup exportado."; if (foreground) showBackup() }
                else backupWork({ (contentResolver.openInputStream(uri) ?: throw java.io.IOException()).use { LauncherBackup.read(it) } }) {
                    importedBackup = it
                    if (foreground) showBackupPreview(it)
                }
            }
            return
        }
        if (requestCode == 801 && waitingInstallPermission) {
            waitingInstallPermission = false
            showUpdates()
            if (Build.VERSION.SDK_INT >= 26 && packageManager.canRequestPackageInstalls()) {
                // onActivityResult precedes onStart; wait for the Activity to be interactive.
                handler.post { if (!isDestroyed) requestUpdateInstall() }
            } else updates.installMessage("Permita instalações pelo TCL Light para continuar. O download já está pronto.")
        } else if (requestCode == 802 && resultCode != RESULT_OK) {
            updates.installMessage("A instalação não foi concluída. Você pode tentar novamente com o arquivo já baixado.")
        }
    }

    private fun openPlayStore() {
        val intent = packageManager.getLeanbackLaunchIntentForPackage("com.android.vending")
            ?: packageManager.getLaunchIntentForPackage("com.android.vending")
        try {
            if (intent == null) throw ActivityNotFoundException()
            startActivity(intent)
        } catch (_: Exception) { Toast.makeText(this, "A Play Store não está disponível nesta TV.", Toast.LENGTH_SHORT).show() }
    }

    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==921) mediaHub?.onTvPermissionResult()
        if(requestCode==920) mediaHub?.onNotificationPermissionResult()
    }

    private fun showInfo(title: String,body: String,icon: TvIcon.Kind = TvIcon.Kind.INFO,
                         action: String? = null,run: (() -> Unit)? = null) {
        val sheet = newPanel(title,"Informações da TV") { showActionCenter() }
        sheet.paragraph(body)
        if(action != null && run != null) sheet.row(PanelAction("info:action",action,"",icon,run=run))
        sheet.focusAction()
    }

    private fun showInputs(focus: String? = null) {
        val sheet = newPanel("Entradas","Fontes de vídeo da sua TV") { showActionCenter("action:inputs") }
        activePanel = "inputs"
        if (videoInputs.isEmpty()) sheet.paragraph(if (inputsLoading) "Consultando as entradas da TV…"
            else "O sistema não disponibilizou a lista de entradas. Use o seletor da TV para escolher a fonte.")
        for (entry in videoInputs) sheet.row(PanelAction("input:" + entry.id, entry.label, entry.detail,
            if (entry.passthrough) TvIcon.Kind.CABLE else TvIcon.Kind.TV,
            entry.state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED) { openInput(entry) })
        sheet.row(PanelAction("inputs:native", "Seletor de entradas da TV", "Abrir menu nativo", TvIcon.Kind.TV) { openInputSelector() })
        sheet.row(PanelAction("inputs:shortcuts", "Atalhos na tela inicial", "Fixar TV e entradas", TvIcon.Kind.PIN) { showShortcutPicker() })
        sheet.focusAction(focus)
    }

    private fun showPreferences(focus: String? = null) {
        val sheet = newPanel("Personalizar","A aparência da sua tela inicial") { showActionCenter("action:customize") }
        val mode = prefs.getInt("wallpaper_mode",-1)
        sheet.caption("Aparência")
        sheet.row(PanelAction("pref:theme","Tema de cores",ui.palette.name+" · "+ui.palette.detail,TvIcon.Kind.PALETTE) { showThemes() })
        sheet.row(PanelAction("pref:display", "Área visível e textos", "Margens da tela e tamanho das letras", TvIcon.Kind.SIZE) { showDisplaySettings() })
        sheet.row(PanelAction("pref:tv-background", "TV ao fundo · Experimental", "Teste opcional com entradas de vídeo", TvIcon.Kind.TV) { showBackgroundSettings() })
        sheet.row(PanelAction("pref:wallpaper","Papel de parede",when(mode) {
            -1 -> "Cinco paisagens em rotação"; 5 -> "Grafite"; else -> "Paisagem " + (mode+1)
        },TvIcon.Kind.IMAGE) {
            choose("Papel de parede",arrayOf("Alternar paisagens","Lago e montanhas","Reflexos no lago",
                "Horizonte natural","Montanhas à noite","Floresta","Fundo grafite"),"wallpaper_mode",
                intArrayOf(-1,0,1,2,3,4,5),"pref:wallpaper")
        })
        sheet.row(PanelAction("pref:dim","Escurecimento",prefs.getInt("wallpaper_dim",50).toString()+"%",TvIcon.Kind.SUN) {
            choose("Escurecimento",arrayOf("20% · Mais claro","35% · Suave","50% · Equilibrado","65% · Mais escuro"),
                "wallpaper_dim",intArrayOf(20,35,50,65),"pref:dim")
        })
        sheet.row(PanelAction("pref:size","Tamanho dos cartões",when(prefs.getInt("icon_scale",1)) {
            0 -> "Compacto"; 2 -> "Grande"; else -> "Normal"
        },TvIcon.Kind.SIZE) {
            choose("Tamanho dos cartões",arrayOf("Compacto","Normal","Grande"),"icon_scale",intArrayOf(0,1,2),"pref:size")
        })
        sheet.row(PanelAction("pref:count","Mais utilizados",prefs.getInt("top_count",7).toString()+" aplicativos",TvIcon.Kind.GRID) {
            choose("Mais utilizados",arrayOf("5 aplicativos","7 aplicativos","10 aplicativos"),
                "top_count",intArrayOf(5,7,10),"pref:count")
        })
        sheet.row(PanelAction("pref:eco","Modo leve",if(economy()) "Ativado" else "Desativado",
            TvIcon.Kind.LEAF,economy()) { toggleEconomy(); showPreferences("pref:eco") })
        sheet.paragraph("O modo leve mantém o fundo fixo e remove as animações de foco.")
        sheet.row(PanelAction("pref:hidden","Aplicativos ocultos","Restaurar na tela inicial",TvIcon.Kind.EYE) { showHiddenApps(true) })
        sheet.row(PanelAction("pref:order","Restaurar organização","Mais utilizados pelo uso; aplicativos em ordem alfabética",TvIcon.Kind.REFRESH) {
            prefs.edit().remove("order.top").remove("order.grid").apply()
            renderApps(); showPreferences("pref:order")
        })
        sheet.paragraph("Clima de Garrafão do Norte: Open-Meteo (CC BY 4.0). Atualização aproximada a cada 45 minutos. A tela inicial continua disponível sem internet.")
        sheet.focusAction(focus)
    }

    private fun showThemes() {
        val sheet=newPanel("Tema de cores","Uma nova atmosfera, com a mesma agilidade") { showPreferences("pref:theme") }
        sheet.notice("As cores mudam os painéis, os ícones e o destaque do foco. Seu papel de parede e a organização são preservados.",TvIcon.Kind.PALETTE)
        LauncherPalettes.values.forEachIndexed { index,palette ->
            sheet.row(PanelAction("theme:$index",palette.name,palette.detail,TvIcon.Kind.PALETTE,
                prefs.getInt(LauncherPalettes.PREF,0)==index,palette.accent) {
                prefs.edit().putInt(LauncherPalettes.PREF,index).apply()
                recreateFeature("appearance","pref:theme")
            })
        }
        sheet.focusAction("theme:"+prefs.getInt(LauncherPalettes.PREF,0).coerceIn(0,2))
    }

    private fun choose(title: String,labels: Array<String>,key: String,values: IntArray,returnKey: String) {
        val sheet = newPanel(title,"Escolha pelo controle e confirme com OK") { showPreferences(returnKey) }
        val current = prefs.getInt(key,when(key) {
            "wallpaper_mode" -> -1; "wallpaper_dim" -> 50; "top_count" -> 7; else -> 1
        })
        for((index,label) in labels.withIndex()) sheet.row(PanelAction("choice:"+index,label,
            if(current == values[index]) "Selecionado" else "",TvIcon.Kind.IMAGE,current == values[index]) {
                prefs.edit().putInt(key,values[index]).apply()
                if(key.startsWith("wallpaper_")) wallpapers?.reload()
                else renderApps()
                showPreferences(returnKey)
            })
        sheet.focusAction("choice:" + values.indexOf(current).coerceAtLeast(0))
    }

    private fun toggleEconomy() {
        prefs.edit().putBoolean("eco_mode",!economy()).apply()
        for(view in homeViews.values) {
            view.animate().cancel(); view.scaleX = 1f; view.scaleY = 1f
        }
        wallpapers?.reload()
        refreshBackgroundTv()
    }

    private fun showNetwork(focus: String? = null) {
        networkStatus = NetworkStatus.read(getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
        val status = networkStatus
        val sheet = newPanel("Conexão de rede", "Estado informado pelo Android") { showActionCenter("action:wifi") }
        activePanel = "network"
        sheet.caption(status.connection)
        sheet.notice(status.internet,TvIcon.Kind.WIFI).tag = "network:status"
        sheet.notice("IP da TV: ${status.address}",TvIcon.Kind.INFO).tag = "network:address"
        if (status.connected && status.ipv4.isEmpty() && status.ipv6.isEmpty())
            sheet.paragraph("A rede ainda não informou um endereço para a TV.")
        if (status.ipv4.isNotEmpty() || status.ipv6.isNotEmpty()) sheet.row(PanelAction("network:copy", "Copiar IP da TV", status.address, TvIcon.Kind.INFO) {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("IP da TV", status.address))
            Toast.makeText(this,"IP copiado.",Toast.LENGTH_SHORT).show()
        })
        sheet.row(PanelAction("network:settings", "Configurar rede", "Abrir ajustes de Wi-Fi e conexão", TvIcon.Kind.WIFI) { openWifiSettings() })
        sheet.row(PanelAction("network:refresh", "Atualizar informações", "Consultar o estado atual", TvIcon.Kind.REFRESH) { showNetwork("network:refresh") })
        sheet.focusAction(focus)
    }

    private fun shortcuts() = HomeShortcuts.decode(prefs.getString(HomeShortcuts.PREF,null))

    private fun saveShortcuts(items: List<HomeShortcut>) {
        if(items.size > HomeShortcuts.MAX) { Toast.makeText(this,"O limite é de ${HomeShortcuts.MAX} atalhos.",Toast.LENGTH_SHORT).show();return }
        prefs.edit().putString(HomeShortcuts.PREF, HomeShortcuts.encode(items)).apply()
        renderShortcuts()
        wireNavigation(when(prefs.getInt("icon_scale",1)) { 0 -> 6; 2 -> 4; else -> 5 })
    }

    private fun renderShortcuts() {
        if (!::shortcutArea.isInitialized) return
        val saved=shortcuts()
        val tv=saved.firstOrNull { it.key=="tv" }
        tvHeaderButton.visibility=if(tv==null) View.GONE else View.VISIBLE
        tvHeaderButton.contentDescription=tv?.label(videoInputs) ?: "TV e canais"
        val entries = saved.filter { it.key != "tv" && it.source(videoInputs) != null }
        val signature = entries.joinToString { it.key + it.label(videoInputs) } + "|" + ui.textScale
        if (signature == shortcutSignature) { wireNavigation(when(prefs.getInt("icon_scale",1)) {0->6;2->4;else->5});return }
        shortcutSignature = signature
        val focused = currentFocus?.tag as? String
        shortcutViews.forEach { homeViews.remove(it.tag.toString()) }; shortcutViews.clear()
        shortcutStrip.removeAllViews()
        shortcutArea.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        for (entry in entries) {
            val card = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(7),dp(12),dp(7))
                contentDescription = entry.label(videoInputs) + ". Segure OK para organizar."
                addView(ImageView(context).apply { setImageDrawable(TvIcon(if(entry.key == "tv") TvIcon.Kind.TV else TvIcon.Kind.CABLE, ui.accent)) },lp(dp(20),dp(20),0,0,10))
                addView(ui.text(entry.label(videoInputs),12f,medium=true).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END },lp(0,-2).apply { weight=1f })
            }
            styleHome(card,"shortcut:" + entry.key)
            card.setOnClickListener { openShortcut(entry) }
            card.setOnLongClickListener { card.requestFocus(); showShortcutOptions(entry); true }
            shortcutViews.add(card); shortcutStrip.addView(card,lp(dp(166),ui.height(40),0,0,10))
        }
        wireNavigation(when(prefs.getInt("icon_scale",1)) { 0 -> 6; 2 -> 4; else -> 5 })
        if (panel == null && focused?.startsWith("shortcut:") == true)
            (homeViews[focused] ?: firstHomeView()).requestFocus()
    }

    private fun openShortcut(entry: HomeShortcut) {
        if (entry.key == "tv") openTv()
        else entry.source(videoInputs)?.let(::openInput) ?: showInputs()
    }

    private fun showShortcutPicker(focus: String? = null) {
        val sheet = newPanel("Atalhos de TV", "Escolha o que aparece na tela inicial") { showActionCenter("action:shortcuts") }
        val selected = shortcuts()
        val choices = listOf(HomeShortcut("tv")) + videoInputs.map { HomeShortcut("input:" + it.id) } +
            selected.filter { it.key != "tv" && it.source(videoInputs) == null }
        for (item in choices.distinctBy { it.key }) {
            val pinned = selected.firstOrNull { it.key == item.key }
            val entry = pinned ?: item
            sheet.row(PanelAction("shortcut-option:" + item.key, entry.label(videoInputs),
                if(pinned != null) "Fixado · Abrir opções" else "Fixar na tela inicial",TvIcon.Kind.PIN,pinned != null) {
                if (pinned == null) { saveShortcuts(selected + entry); showShortcutPicker("shortcut-option:" + entry.key) }
                else showShortcutOptions(entry, true)
            })
        }
        if (videoInputs.isEmpty()) sheet.paragraph("A TV ainda não disponibilizou entradas. O atalho de TV continua disponível.")
        sheet.focusAction(focus)
    }

    private fun showShortcutOptions(entry: HomeShortcut, fromPicker: Boolean = false) {
        val sheet = newPanel(entry.label(videoInputs),"Organizar atalho") {
            if(fromPicker) showShortcutPicker("shortcut-option:" + entry.key) else closePanel()
        }
        sheet.row(PanelAction("shortcut:open", "Abrir", "Ir para a TV ou entrada", TvIcon.Kind.TV) { openShortcut(entry) })
        sheet.row(PanelAction("shortcut:rename", "Renomear", "Escolher um nome para este atalho", TvIcon.Kind.PALETTE) {
            val edit = EditText(this).apply {
                setSingleLine(true); setText(entry.label(videoInputs)); selectAll()
                filters = arrayOf(android.text.InputFilter.LengthFilter(32))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            val dialog=android.app.AlertDialog.Builder(this).setTitle("Nome do atalho").setView(edit)
                .setNegativeButton("Cancelar", null).setPositiveButton("Salvar") { _, _ ->
                    val name = edit.text.toString().filterNot { it.isISOControl() }.trim().take(32)
                    val updated = entry.copy(name=name)
                    saveShortcuts(shortcuts().map { if(it.key == entry.key) updated else it })
                    showShortcutOptions(updated,fromPicker)
                }.show()
            dialog.window?.setLayout(dp(minOf(600,resources.configuration.screenWidthDp-safeHorizontal()*2)),ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        val entries = shortcuts(); val index = entries.indexOfFirst { it.key == entry.key }
        for ((delta,title) in listOf(-1 to "Mover à esquerda", 1 to "Mover à direita")) {
            if (index + delta in entries.indices) sheet.row(PanelAction("shortcut:move:$delta",title,"Alterar a posição na tela inicial",if(delta<0) TvIcon.Kind.LEFT else TvIcon.Kind.RIGHT) {
                val reordered=shortcuts().toMutableList(); val current=reordered.indexOfFirst { it.key == entry.key }
                if(current>=0 && current+delta in reordered.indices) java.util.Collections.swap(reordered,current,current+delta)
                saveShortcuts(reordered); showShortcutOptions(entry,fromPicker)
            })
        }
        sheet.row(PanelAction("shortcut:remove", "Remover da tela inicial", "A entrada continua disponível na Central", TvIcon.Kind.CLOSE) {
            saveShortcuts(shortcuts().filterNot { it.key == entry.key })
            if(fromPicker) showShortcutPicker() else closePanel()
        })
        sheet.focusAction()
    }

    private fun showDisplaySettings(focus: String? = null) {
        val sheet = newPanel("Área visível e textos", "Ajuste a tela para sua TV") { showPreferences("pref:display") }
        sheet.paragraph("Aumente as margens se algum controle ficar cortado nas bordas. O papel de parede continua preenchendo a tela.")
        val choices = listOf(
            Triple("safe_horizontal","Margens laterais",listOf(24,42,56,72)),
            Triple("safe_vertical","Margens superior e inferior",listOf(0,8,16,24)),
            Triple("text_scale","Tamanho dos textos",listOf(100,110,120)))
        for ((key,title,values) in choices) {
            val current=prefs.getInt(key,when(key){"safe_horizontal"->42;"safe_vertical"->8;else->100})
            sheet.row(PanelAction("display:"+key,title,if(key=="text_scale") "$current%" else when(current){values.first()->"Reduzidas";values.last()->"Amplas";else->"Intermediárias"},TvIcon.Kind.SIZE) {
                val options=newPanel(title,"Confirme com OK") { showDisplaySettings("display:"+key) }
                values.forEachIndexed { index,value ->
                    val label=if(key=="text_scale") "$value%" else listOf("Reduzidas","Padrão","Amplas","Máximas")[index]
                    options.row(PanelAction("display-choice:$value",label,if(value==current) "Selecionado" else "",TvIcon.Kind.SIZE,value==current) {
                        prefs.edit().putInt(key,value).apply(); recreateFeature("display","display:"+key)
                    })
                }
                options.focusAction("display-choice:$current")
            })
        }
        sheet.row(PanelAction("display:reset","Restaurar tamanho padrão","Margens e textos originais",TvIcon.Kind.REFRESH) {
            prefs.edit().remove("safe_horizontal").remove("safe_vertical").remove("text_scale").apply()
            recreateFeature("display","display:reset")
        })
        sheet.focusAction(focus)
    }

    private fun recreateFeature(destination: String, focus: String = "") {
        reopenPanel=destination; reopenFocus=focus; recreate()
    }

    private fun localBackup() = android.util.AtomicFile(File(filesDir,"launcher-backup.json"))

    private fun <T> backupWork(work: () -> T, done: (T) -> Unit) {
        if(backupBusy) return
        backupBusy=true
        scanner.execute {
            val result=runCatching(work)
            handler.post {
                backupBusy=false
                if(isDestroyed || isFinishing) return@post
                result.fold(done) { error ->
                    backupMessage=if(error is BackupFailure) error.message ?: "Backup inválido." else "Não foi possível concluir o backup. Verifique o arquivo e o espaço disponível."
                    if(foreground) showBackup()
                }
            }
        }
    }

    private fun showBackup() {
        val sheet=newPanel("Backup local","Organização e aparência do seu launcher") { showActionCenter("action:backup") }
        activePanel="backup"
        sheet.notice(backupMessage,TvIcon.Kind.STORAGE).tag="backup:status"
        importedBackup?.let { data -> sheet.row(PanelAction("backup:review","Revisar backup selecionado",data.summary,TvIcon.Kind.INFO) { showBackupPreview(data) }) }
        sheet.row(PanelAction("backup:save","Salvar cópia nesta TV","Guardar a configuração atual",TvIcon.Kind.STORAGE) {
            backupWork({
                val bytes=LauncherBackup.export(prefs,lowMemory); val file=localBackup(); val output=file.startWrite()
                try { output.write(bytes); file.finishWrite(output) } catch(error:Exception) { file.failWrite(output); throw error }
            }) { backupMessage="Cópia salva nesta TV."; if(foreground) showBackup() }
        })
        if(localBackup().baseFile.isFile) sheet.row(PanelAction("backup:local","Restaurar cópia desta TV","Revisar antes de substituir os ajustes",TvIcon.Kind.REFRESH) {
            backupWork({ localBackup().openRead().use { LauncherBackup.read(it) } }) {
                importedBackup=it; if(foreground) showBackupPreview(it)
            }
        })
        sheet.row(PanelAction("backup:export","Exportar arquivo","Guardar em outro local ou dispositivo",TvIcon.Kind.RIGHT) {
            val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE,"TCL-Light-Backup.json")
            try { startActivityForResult(intent,901) } catch(_:ActivityNotFoundException) { shareBackup() }
        })
        sheet.row(PanelAction("backup:import","Importar arquivo","Escolher um backup do TCL Light",TvIcon.Kind.LEFT) {
            val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            try { startActivityForResult(intent,902) }
            catch(_:ActivityNotFoundException) {
                try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),902) }
                catch(_:ActivityNotFoundException) { backupMessage="Esta TV não oferece um seletor de arquivos. A cópia nesta TV continua disponível."; showBackup() }
            }
        })
        sheet.paragraph("Exporte um arquivo para guardar o backup fora do aplicativo. A restauração é revisada antes de substituir suas preferências.")
        sheet.focusAction()
    }

    private fun shareBackup() = backupWork({
        val file=UpdateApkProvider.backup(this); file.parentFile!!.mkdirs()
        file.writeBytes(LauncherBackup.export(prefs,lowMemory))
    }) {
        val uri=UpdateApkProvider.backupUri(this)
        val intent=Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM,uri).apply {
            clipData=ClipData.newRawUri("Backup TCL Light",uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(intent,"Exportar backup")) }
        catch(_:ActivityNotFoundException) { backupMessage="Nenhum aplicativo disponível para receber o arquivo. Use a cópia nesta TV."; showBackup() }
    }

    private fun showBackupPreview(data: BackupData) {
        val sheet=newPanel("Restaurar backup","Confira antes de aplicar") { importedBackup=null;showBackup() }
        activePanel="backup-preview"
        sheet.paragraph(data.summary)
        sheet.paragraph("A organização e a aparência atuais serão substituídas. Os aplicativos instalados e a escolha de tela inicial são mantidos.")
        sheet.row(PanelAction("backup:confirm","Restaurar configuração","Aplicar este backup",TvIcon.Kind.CHECK) {
            backupWork({ LauncherBackup.restore(prefs,data) }) {
                importedBackup=null; backupMessage="Backup restaurado."; recreateFeature("backup")
            }
        })
        sheet.focusAction()
    }

    private fun showBackgroundSettings() {
        val sheet=newPanel("TV ao fundo","Experimental · Entradas de vídeo") { showPreferences("pref:tv-background") }
        activePanel="background"
        sheet.paragraph(backgroundTv?.message ?: "Desativado").tag="background:status"
        sheet.paragraph("Teste uma entrada conectada, como HDMI, sem áudio. A imagem é encerrada ao abrir outro aplicativo e o papel de parede volta se a TV não permitir a conexão.")
        if(economy()) sheet.paragraph("O modo leve está ativo. Desative-o na Central para testar este recurso.")
        else {
            val eligible=videoInputs.filter { it.passthrough && it.state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED }
            if(eligible.isEmpty()) sheet.paragraph("A TV não disponibilizou uma entrada de vídeo conectada para este teste.")
            for(source in eligible) sheet.row(PanelAction("background:"+source.id,source.label,"Testar ao fundo",TvIcon.Kind.TV) {
                prefs.edit().putString("tv_background_input",source.id).apply(); refreshBackgroundTv()
            })
        }
        sheet.row(PanelAction("background:off","Usar papel de parede","Encerrar o teste",TvIcon.Kind.IMAGE) {
            prefs.edit().remove("tv_background_input").apply(); backgroundTv?.stop(); showBackgroundSettings()
        })
        sheet.focusAction()
    }

    private fun refreshBackgroundTv() {
        if(!foreground || inputsLoading) return
        val id=prefs.getString("tv_background_input",null)
        if(id == null || economy()) { backgroundTv?.stop(); return }
        val source=videoInputs.firstOrNull { it.id == id }
        val current=backgroundTv
        if(current?.inputId == id && current.state in listOf(BackgroundTvState.PLAYING,BackgroundTvState.STARTING) &&
            source?.state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED) return
        if(current == null) backgroundTv=BackgroundTv(frame,handler,{ visible ->
            wallpapers?.visibility=if(visible) View.INVISIBLE else View.VISIBLE
            if(visible) wallpapers?.onBackground() else if(foreground) wallpapers?.onForeground()
        },{ state,_ ->
            if(state == BackgroundTvState.UNAVAILABLE) prefs.edit().remove("tv_background_input").apply()
            if(foreground && activePanel == "background") showBackgroundSettings()
        })
        backgroundTv?.start(source,economy())
    }

    private fun showHiddenApps(fromPreferences: Boolean = false) {
        val sheet = newPanel("Aplicativos ocultos","Selecione um app para mostrar novamente") {
            if(fromPreferences) showPreferences("pref:hidden") else showActionCenter("action:hidden")
        }
        val hidden = hiddenApps()
        val options = mediaApps.filter { it.packageName in hidden }
        if(options.isEmpty()) sheet.paragraph("Nenhum aplicativo oculto por você.")
        for(app in options) sheet.row(PanelAction("hidden:"+app.packageName,app.name,"Mostrar na tela inicial",TvIcon.Kind.EYE) {
            val all = hiddenApps().toMutableSet(); all.remove(app.packageName)
            prefs.edit().putStringSet("hidden_apps",all).apply()
            renderApps(); showHiddenApps(fromPreferences)
        })
        sheet.focusAction()
    }

    private fun showAppMenu(app: TvApp, lane: String) {
        val pin = app.packageName in pinnedApps()
        val sheet = newPanel(app.name,if (lane == "top") "Organizar os mais utilizados" else "Organizar seus aplicativos")
        val ids = laneApps(lane).map { it.packageName }
        val index = ids.indexOf(app.packageName)
        val moves = ArrayList<PanelAction>()
        if (index > 0) moves.add(PanelAction("app:left","Mover à esquerda","Reorganizar pelo controle",TvIcon.Kind.LEFT) { startMoving(app, lane, -1) })
        if (index >= 0 && index < ids.lastIndex) moves.add(PanelAction("app:right","Mover à direita","Reorganizar pelo controle",TvIcon.Kind.RIGHT) { startMoving(app, lane, 1) })
        if (moves.isNotEmpty()) sheet.grid(moves)
        sheet.row(PanelAction("app:pin",if(pin) "Desafixar" else "Fixar nos mais utilizados",
            if(pin) "Remover fixação" else "Dar prioridade nos mais utilizados",TvIcon.Kind.PIN,pin) {
            val all = pinnedApps().toMutableSet()
            if(pin) all.remove(app.packageName) else all.add(app.packageName)
            val edit = prefs.edit().putStringSet("pinned_apps",all)
            val manual = AppOrdering.decode(prefs.getString("order.top", null))
            if (!pin && manual.isNotEmpty()) edit.putString("order.top",
                AppOrdering.encode(listOf(app.packageName) + manual.filterNot { it == app.packageName }))
            edit.apply()
            renderApps(); closePanel()
        })
        sheet.row(PanelAction("app:hide","Ocultar da tela inicial","Pode ser restaurado na central",TvIcon.Kind.EYE) {
            val all = hiddenApps().toMutableSet(); all.add(app.packageName)
            prefs.edit().putStringSet("hidden_apps",all).apply()
            renderApps(); closePanel()
        })
        sheet.row(PanelAction("app:info","Informações do aplicativo","Abrir ajustes do Android",TvIcon.Kind.INFO) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:"+app.packageName))) }
            catch(_: Exception) { openSettings() }
        })
        if (orderFor(lane).isNotEmpty()) sheet.row(PanelAction("app:auto", "Restaurar ordem desta área",
            if (lane == "top") "Organizar pelo uso novamente" else "Organizar por nome novamente",TvIcon.Kind.REFRESH) {
            prefs.edit().remove("order." + lane).apply(); renderApps(); closePanel()
        })
        sheet.focusAction()
    }

    private fun orderFor(lane: String): List<String> = moving?.takeIf { it.lane == lane }?.ids
        ?: AppOrdering.decode(prefs.getString("order." + lane, null))

    private fun ordered(items: List<TvApp>, lane: String) = AppOrdering.apply(items, orderFor(lane)) { it.packageName }

    private fun laneApps(lane: String): List<TvApp> {
        val visible = mediaApps.filterNot { it.packageName in hiddenApps() }
        return if (lane == "top") ordered(rankedApps(visible), "top")
            .take(prefs.getInt("top_count", 7).coerceIn(5, 10)) else ordered(visible, "grid")
    }

    private fun startMoving(app: TvApp, lane: String, delta: Int) {
        closePanel()
        moving = MoveSession(app.packageName, lane, laneApps(lane).map { it.packageName })
        setTextIfChanged(controlHint, "← →  Mover    ·    OK  Salvar    ·    Voltar  Cancelar")
        moveSelection(delta)
    }

    private fun moveSelection(delta: Int) {
        val session = moving ?: return
        session.ids = AppOrdering.move(session.ids, session.packageName, delta)
        lastHomeFocus = session.lane + ":" + session.packageName
        renderApps()
        homeViews[lastHomeFocus]?.requestFocus()
    }

    private fun finishMoving(save: Boolean) {
        val session = moving ?: return
        if (save) prefs.edit().putString("order." + session.lane, AppOrdering.encode(session.ids)).apply()
        moving = null
        setTextIfChanged(controlHint, "OK  Abrir    ·    Segure OK  Mover e opções")
        renderApps()
        homeViews[session.lane + ":" + session.packageName]?.requestFocus()
    }

    private fun openInput(entry: TvSource) {
        try { startActivity(entry.intent()) }
        catch (_: Exception) { openInputSelector() }
    }

    private fun openInputSelector() {
        // Native TV handlers may expose this action; the SDK has no public Intent constant for it.
        try { startActivity(Intent("android.intent.action.TV_INPUT_BUTTON").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { openTv() }
    }

    private fun currentHome(): String = try {
        @Suppress("DEPRECATION")
        val home = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if(home == packageName) "TCL Light está ativo" else "Escolher TCL Light como Home"
    } catch(_: Exception) { "Verificar tela inicial" }

    private fun showHomeAssistant() {
        val sheet = newPanel("Tela inicial padrão",currentHome()) { showActionCenter("action:home") }
        sheet.paragraph("Escolha TCL Light como tela inicial, se esta opção estiver disponível no Android da sua TV. Depois, pressione Home para conferir.")
        sheet.paragraph("Mantenha o launcher original instalado. Alguns firmwares da TCL limitam a escolha de uma tela inicial alternativa.")
        sheet.row(PanelAction("home:choose","Escolher tela inicial","Abrir seletor do Android",TvIcon.Kind.HOME) { setAsDefaultHome() })
        sheet.focusAction()
    }

    private fun openWifiSettings() { try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch(_: Exception) { openSettings() } }
    private fun openPeripheralSettings() { try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch(_: Exception) { openSettings() } }
    private fun openStorageSettings() { try { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) } catch(_: Exception) { openSettings() } }
    private fun openSettings() {
        try { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        catch(_: Exception) { Toast.makeText(this,"Configurações indisponíveis.",Toast.LENGTH_SHORT).show() }
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
                if (isSystemApp && !isVisibleSystemApp(pkg, name,
                    if (Build.VERSION.SDK_INT >= 26) info.category else -1)) continue
                // Home/Configurações mantidos só na central superior.
                if (isInternalLauncherOrSettings(pkg, name)) continue
                found[pkg] = TvApp(name, pkg, ComponentName(pkg, activity.name))
            }
        }
        // Some TV firmware omits the store from the generic launcher query.
        if (!found.containsKey("com.android.vending")) {
            val store = manager.getLeanbackLaunchIntentForPackage("com.android.vending")
                ?: manager.getLaunchIntentForPackage("com.android.vending")
            val component = store?.component
            if (component != null) {
                val label = try { manager.getApplicationLabel(manager.getApplicationInfo(component.packageName, 0)).toString() }
                    catch (_: Exception) { "Google Play Store" }
                found[component.packageName] = TvApp(label, component.packageName, component)
            }
        }
        val comparator = Collator.getInstance(locale)
        return found.values.sortedWith(Comparator { a, b -> comparator.compare(a.name, b.name) })
    }

    private fun isVisibleSystemApp(pkg: String, name: String, category: Int): Boolean =
        pkg == "com.android.vending" || isStreamingApp(pkg, name, category)

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
    private fun hiddenApps(): Set<String> = prefs.getStringSet("hidden_apps", emptySet())
        ?.toSet() ?: emptySet()

    private fun pinnedApps(): Set<String> = prefs.getStringSet("pinned_apps", emptySet())
        ?.toSet() ?: emptySet()

    private fun rankedApps(installed: List<TvApp>): List<TvApp> {
        val pinned = pinnedApps()
        return installed.sortedWith(
            compareByDescending<TvApp> { it.packageName in pinned }
                .thenByDescending { prefs.getInt("usage.count." + it.packageName, 0) }
                .thenByDescending { prefs.getLong("usage.time." + it.packageName, 0L) }
                .thenBy { it.name.lowercase(locale) }
        )
    }

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
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.Channels.CONTENT_URI)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) { }
        val pkgList = listOf("com.google.android.tv", "com.tcl.tv",
            "com.tcl.live", "com.mediatek.wwtv.tvcenter")
        for (pkg in pkgList) try {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
                ?: packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        } catch (_: Exception) { }
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

}
