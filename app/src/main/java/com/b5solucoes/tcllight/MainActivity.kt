package com.b5solucoes.tcllight

import android.app.Activity
import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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

/** Native, offline TV Home. Background work never blocks remote-control dispatch. */
class MainActivity : Activity() {
    private data class TvApp(val name: String, val packageName: String, val component: ComponentName)
    private val handler = Handler(Looper.getMainLooper())
    private val locale = Locale("pt", "BR")
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private val lowMemory by lazy { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice }
    private fun economy() = prefs.getBoolean("eco_mode", lowMemory)
    private val ui by lazy { TvStyle(this, ::economy) }
    private val artwork by lazy { AppArtwork(applicationContext) }
    private val scanner = Executors.newSingleThreadExecutor { r -> Thread(r,"tv-app-catalog") }
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
    private lateinit var timeLabel: TextView
    private lateinit var dateLabel: TextView
    private lateinit var centralButton: View
    private var wallpapers: WallpaperDeck? = null
    private var panel: LauncherPanel? = null
    private var panelBack: (() -> Unit)? = null
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
    private var inputRegistered = false
    private val clockFormat = SimpleDateFormat("HH:mm",locale)
    private val dateFormat = SimpleDateFormat("EEE, d MMM",locale)

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
    }
    private val inputEvents = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) { refreshStatus() }
        override fun onInputDeviceRemoved(id: Int) { refreshStatus() }
        override fun onInputDeviceChanged(id: Int) { refreshStatus() }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = ui.base
        window.navigationBarColor = ui.base
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        buildInterface()
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
        if(appsDirty) refresh()
        if(usageDirty) { usageDirty = false; renderApps() }
    }

    override fun onStop() {
        foreground = false
        handler.removeCallbacks(clockUpdate)
        wallpapers?.onBackground()
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        if(networkRegistered) { try { cm.unregisterNetworkCallback(networkEvents) } catch(_: Exception) {} }
        if(inputRegistered) (getSystemService(INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(inputEvents)
        networkRegistered = false; inputRegistered = false
        super.onStop()
    }

    override fun onDestroy() {
        if(packageReceiverRegistered) unregisterReceiver(packageEvents)
        handler.removeCallbacksAndMessages(null)
        scanner.shutdownNow()
        artwork.close()
        wallpapers?.close()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(level >= TRIM_MEMORY_RUNNING_LOW) artwork.trim()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        closePanel()
        listScroll.smoothScrollTo(0,0)
        (topViews.firstOrNull() ?: centralButton).requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(event.action == KeyEvent.ACTION_DOWN) {
            if(event.keyCode == KeyEvent.KEYCODE_MENU) {
                if(event.repeatCount == 0) { if(panel == null) showActionCenter() else closePanel() }
                return true
            }
            if(event.keyCode in intArrayOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)) userNavigated = true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Legacy Android TV back handling")
    override fun onBackPressed() {
        if(panel != null) { val back = panelBack; if(back != null) back() else closePanel() }
        else {
            listScroll.smoothScrollTo(0,0)
            (topViews.firstOrNull() ?: centralButton).requestFocus()
        }
    }

    private fun dp(value: Int) = ui.dp(value)
    private fun lp(w: Int,h: Int,l: Int=0,t: Int=0,r: Int=0,b: Int=0) =
        LinearLayout.LayoutParams(w,h).apply { setMargins(dp(l),dp(t),dp(r),dp(b)) }

    private fun buildInterface() {
        frame = FrameLayout(this).apply { setBackgroundColor(ui.base) }
        wallpapers = WallpaperDeck(this,prefs,::economy)
        frame.addView(wallpapers,FrameLayout.LayoutParams(-1,-1))
        homeLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
        }
        frame.addView(homeLayer,FrameLayout.LayoutParams(-1,-1))
        setContentView(frame)

        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false
        }
        homeLayer.addView(nav,lp(-1,dp(68),42,8,42))
        val brand = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5),dp(4),dp(12),dp(4))
        }
        brand.addView(ImageView(this).apply {
            background = ui.surface(ui.accent,10)
            setImageDrawable(TvIcon(TvIcon.Kind.HOME,Color.rgb(17,46,44)))
            setPadding(dp(8),dp(8),dp(8),dp(8))
        },lp(dp(34),dp(34),0,0,10))
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandText.addView(ui.text("TCL Light",17.5f,medium=true))
        brandText.addView(ui.text("TELA INICIAL",8f,ui.muted).apply { letterSpacing = .16f },
            lp(-2,-2,0,4))
        brand.addView(brandText)
        styleHome(brand,"nav:home",true)
        brand.setOnClickListener {
            listScroll.smoothScrollTo(0,0); (topViews.firstOrNull() ?: centralButton).requestFocus()
        }
        nav.addView(brand,lp(-2,dp(48)))
        navViews.add(brand)
        nav.addView(View(this),LinearLayout.LayoutParams(0,1,1f))

        val clock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        timeLabel = ui.text("--:--",23f,medium=true)
        dateLabel = ui.text("",9.5f,ui.muted)
        clock.addView(timeLabel)
        clock.addView(dateLabel,lp(-2,-2,0,3))
        nav.addView(clock,lp(dp(104),-2,0,0,19))
        networkLabel = ui.text("Rede",11f)
        networkIcon = ImageView(this)
        val net = navControl("nav:wifi",TvIcon.Kind.WIFI,"Conexão de rede",networkLabel,networkIcon) { openWifiSettings() }
        nav.addView(net,lp(dp(88),dp(39),0,0,9)); navViews.add(net)
        deviceLabel = ui.text("",11f,ui.muted)
        val devices = navControl("nav:devices",TvIcon.Kind.DEVICES,"Controles e dispositivos",deviceLabel) { showDevices() }
        nav.addView(devices,lp(dp(60),dp(39),0,0,9)); navViews.add(devices)
        centralButton = navControl("nav:central",TvIcon.Kind.CONTROLS,"Central de ações",ui.text("Central",11f,medium=true)) { showActionCenter() }
        nav.addView(centralButton,lp(dp(103),dp(39))); navViews.add(centralButton)

        listScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false; isFillViewport = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        homeLayer.addView(listScroll,LinearLayout.LayoutParams(-1,0,1f))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(dp(42),dp(6),dp(42),dp(14))
        }
        listScroll.addView(column)
        val sceneSpace = ((resources.configuration.screenHeightDp-440)*.38f).toInt().coerceIn(32,110)
        column.addView(View(this),lp(-1,dp(sceneSpace)))
        column.addView(section("Mais utilizados","Pelo seu uso"),lp(-1,dp(28),0,0,3,6))
        favoriteStrip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false
        }
        favorites = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
            setPadding(dp(4),dp(5),dp(4),dp(5))
        }
        favoriteStrip.addView(favorites)
        column.addView(favoriteStrip,lp(-1,-2,0,0,0,16))
        val appHeader = section("Seus aplicativos","")
        appCount = (appHeader as LinearLayout).getChildAt(1) as TextView
        column.addView(appHeader,lp(-1,dp(27),0,0,3,6))
        appGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(dp(4),dp(4),dp(4),dp(5))
        }
        column.addView(appGrid,lp(-1,-2))
        favorites.addView(ui.text("Carregando aplicativos…",12f,ui.muted),lp(-1,dp(110)))
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(ui.text("OK  Abrir    ·    Segure OK  Opções",9.5f,ui.quiet),
            LinearLayout.LayoutParams(0,-2,1f))
        footer.addView(ui.text("MENU  Central de ações",9.5f,ui.muted))
        homeLayer.addView(footer,lp(-1,dp(27),46,0,46,5))
        centralButton.requestFocus()
    }

    private fun section(title: String,meta: String): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.text(title,18f,medium=true),LinearLayout.LayoutParams(0,-2,1f))
        addView(ui.text(meta,10.5f,ui.quiet))
    }

    private fun navControl(key: String,icon: TvIcon.Kind,label: String,text: TextView,
                           image: ImageView = ImageView(this),action: () -> Unit): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER
            image.setImageDrawable(TvIcon(icon,ui.muted))
            addView(image,lp(dp(19),dp(19),0,0,7))
            text.maxLines = 1; text.ellipsize = TextUtils.TruncateAt.END
            addView(text,lp(-2,-2))
            contentDescription = label
            styleHome(this,key)
            setOnClickListener { action() }
        }

    private fun styleHome(view: View,key: String,transparent: Boolean = false) {
        ui.focus(view,key,14,transparent) { if(it) lastHomeFocus = key }
        homeViews[key] = view
    }

    private fun appTile(app: TvApp,large: Boolean,widthDp: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            clipChildren = false; clipToPadding = false
            setPadding(dp(5),dp(5),dp(5),dp(7))
            contentDescription = app.name
        }
        val artHeight = (widthDp-10)*9/16
        val surface = FrameLayout(this).apply {
            val shade = (app.packageName.hashCode().ushr(1) % 20)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(29+shade,42+shade,57+shade),Color.rgb(15,24,36))).apply {
                cornerRadius = dp(10).toFloat()
            }
            clipToOutline = true
        }
        val fallback = ui.text(app.name.take(1).uppercase(locale),27f,ui.muted,true).apply { gravity = Gravity.CENTER }
        surface.addView(fallback,FrameLayout.LayoutParams(-1,-1))
        val image = ImageView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        surface.addView(image,FrameLayout.LayoutParams(-1,-1))
        val componentKey = app.component.flattenToString()
        image.tag = componentKey
        artwork.load(app.component) { art ->
            if(art != null && image.tag == componentKey && !isDestroyed) {
                fallback.visibility = View.GONE
                image.scaleType = ImageView.ScaleType.FIT_CENTER
                val padding = if(art.banner) 0 else dp(if(large) 14 else 16)
                image.setPadding(padding,padding,padding,padding)
                image.setImageBitmap(art.bitmap)
            }
        }
        if(app.packageName in pinnedApps()) surface.addView(ImageView(this).apply {
            setImageDrawable(TvIcon(TvIcon.Kind.PIN,ui.accent))
            background = ui.surface(Color.argb(222,7,19,28),6)
            setPadding(dp(4),dp(4),dp(4),dp(4))
        },FrameLayout.LayoutParams(dp(23),dp(23),Gravity.TOP or Gravity.END).apply {
            setMargins(0,dp(5),dp(5),0)
        })
        card.addView(surface,lp(-1,dp(artHeight)))
        card.addView(ui.text(app.name,if(large)12f else 11.5f,ui.ink,large).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        },lp(-1,dp(17),0,8))
        val key = (if(large) "top:" else "grid:") + app.packageName
        styleHome(card,key,true)
        card.setOnClickListener { open(app) }
        card.setOnLongClickListener { showAppMenu(app); true }
        return card
    }

    /** Rebuild only the row whose order/content changed, keeping the other views alive. */
    private fun renderApps() {
        val keep = (currentFocus?.tag as? String)?.takeIf { panel == null } ?: lastHomeFocus
        val hidden = hiddenApps()
        val visible = mediaApps.filterNot { it.packageName in hidden }
        val selected = rankedApps(visible).take(prefs.getInt("top_count",7).coerceIn(5,10))
        val scale = prefs.getInt("icon_scale",1)
        val pinKey = pinnedApps().sorted().joinToString()
        val nextTop = selected.joinToString { it.packageName } + "|" + scale + "|" + pinKey + "|" + catalogRevision
        val nextGrid = visible.joinToString { it.packageName + it.name } + "|" + scale + "|" + pinKey + "|" + catalogRevision
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
            val tileWidth = (resources.configuration.screenWidthDp-92-(columns-1)*12)/columns
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
        wireNavigation(columns)
        val initial = firstData
        firstData = false
        if(changed && panel == null) {
            val target = if(initial && !userNavigated) topViews.firstOrNull()
                else homeViews[keep] ?: topViews.firstOrNull()
            homeLayer.post {
                if(!isDestroyed && panel == null && target?.isAttachedToWindow == true) target.requestFocus()
            }
        }
    }

    private fun wireNavigation(columns: Int) {
        val first = topViews.firstOrNull() ?: gridViews.firstOrNull() ?: centralButton
        navViews.forEachIndexed { i,v ->
            v.nextFocusLeftId = navViews[(i-1).coerceAtLeast(0)].id
            v.nextFocusRightId = navViews[(i+1).coerceAtMost(navViews.lastIndex)].id
            v.nextFocusDownId = first.id
        }
        topViews.forEachIndexed { i,v ->
            v.nextFocusUpId = centralButton.id
            v.nextFocusLeftId = topViews[(i-1).coerceAtLeast(0)].id
            v.nextFocusRightId = topViews[(i+1).coerceAtMost(topViews.lastIndex)].id
            v.nextFocusDownId = gridViews.getOrNull(i.coerceAtMost(gridViews.lastIndex))?.id ?: v.id
        }
        gridViews.forEachIndexed { i,v ->
            v.nextFocusLeftId = if(i % columns > 0) gridViews[i-1].id else v.id
            v.nextFocusRightId = if(i % columns < columns-1) gridViews.getOrNull(i+1)?.id ?: v.id else v.id
            v.nextFocusUpId = if(i >= columns) gridViews[i-columns].id
                else topViews.getOrNull(i.coerceAtMost(topViews.lastIndex))?.id ?: centralButton.id
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
        try {
            (getSystemService(INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputEvents,handler)
            inputRegistered = true
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
        val count = controllers().size
        setTextIfChanged(deviceLabel,if(count > 0) count.toString() else "0")
        (deviceLabel.parent as? View)?.contentDescription = count.toString() + " dispositivos de entrada detectados"
    }

    private fun wifiState() = when(networkType()) {
        ConnectivityManager.TYPE_WIFI -> "Wi-Fi conectado"
        ConnectivityManager.TYPE_ETHERNET -> "Cabo conectado"
        -1 -> "Sem conexão"
        else -> "Rede conectada"
    }

    private fun controllers(): List<String> = try {
        InputDevice.getDeviceIds().mapNotNull { InputDevice.getDevice(it) }.filter { device ->
            !device.isVirtual && (device.supportsSource(InputDevice.SOURCE_DPAD) ||
                device.supportsSource(InputDevice.SOURCE_GAMEPAD) ||
                device.supportsSource(InputDevice.SOURCE_KEYBOARD) ||
                device.supportsSource(InputDevice.SOURCE_MOUSE))
        }.map { it.name }.distinct()
    } catch(_: Exception) { emptyList() }

    private fun newPanel(title: String,subtitle: String,back: (() -> Unit)? = null): LauncherPanel {
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
        old.animate().cancel()
        frame.removeView(old); panel = null; panelBack = null
        homeLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        homeLayer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        (homeViews[returnFocus] ?: topViews.firstOrNull() ?: centralButton).requestFocus()
    }

    private fun showActionCenter(focus: String? = null) {
        val sheet = newPanel("Central de ações","Conexões e atalhos da sua TV")
        val inputs = controllers()
        sheet.grid(listOf(
            PanelAction("action:wifi","Rede",wifiState(),TvIcon.Kind.WIFI,networkType() != -1) { openWifiSettings() },
            PanelAction("action:devices","Dispositivos",inputs.size.toString() + " entradas detectadas",TvIcon.Kind.DEVICES) { showDevices() },
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
        sheet.caption("Mais controles")
        sheet.row(PanelAction("action:customize","Personalizar aparência","Fundos, cartões e mais utilizados",TvIcon.Kind.PALETTE) { showPreferences() })
        sheet.row(PanelAction("action:settings","Configurações da TV","Imagem, áudio e sistema",TvIcon.Kind.CONTROLS) { openSettings() })
        sheet.row(PanelAction("action:home","Tela inicial padrão",currentHome(),TvIcon.Kind.HOME) { showHomeAssistant() })
        sheet.row(PanelAction("action:storage","Armazenamento","Gerenciar espaço na TV",TvIcon.Kind.STORAGE) { openStorageSettings() })
        sheet.row(PanelAction("action:hidden","Aplicativos ocultos",hiddenApps().size.toString() + " ocultos por você",TvIcon.Kind.EYE) { showHiddenApps() })
        sheet.row(PanelAction("action:refresh","Atualizar aplicativos","Procurar instalações e alterações",TvIcon.Kind.REFRESH) {
            artwork.invalidate(); topSignature = ""; gridSignature = ""; refresh()
            Toast.makeText(this,"Atualizando aplicativos…",Toast.LENGTH_SHORT).show()
        })
        sheet.row(PanelAction("action:hdmi","Entradas HDMI","Use INPUT / SOURCE no controle",TvIcon.Kind.CABLE) {
            showInfo("Entradas HDMI","Use o botão INPUT ou SOURCE do controle da TCL para escolher TV, HDMI 1 ou HDMI 2.",TvIcon.Kind.TV,"Abrir TV",::openTv)
        })
        sheet.focusAction(focus)
    }

    private fun showInfo(title: String,body: String,icon: TvIcon.Kind = TvIcon.Kind.INFO,
                         action: String? = null,run: (() -> Unit)? = null) {
        val sheet = newPanel(title,"Informações da TV") { showActionCenter() }
        sheet.paragraph(body)
        if(action != null && run != null) sheet.row(PanelAction("info:action",action,"",icon,run=run))
        sheet.focusAction()
    }

    private fun showDevices() {
        val entries = controllers()
        val sheet = newPanel("Dispositivos","Controles, teclados e outros periféricos") { showActionCenter("action:devices") }
        sheet.caption("Entradas detectadas")
        sheet.paragraph(if(entries.isEmpty()) "Nenhum dispositivo de entrada identificado."
            else entries.joinToString("\n\n"))
        sheet.paragraph("Esta lista mostra entradas reconhecidas pelo Android. Para consultar acessórios Bluetooth pareados ou conectar outro controle, abra os ajustes.")
        sheet.row(PanelAction("devices:bluetooth","Ajustes Bluetooth","Parear e gerenciar acessórios",TvIcon.Kind.DEVICES) { openPeripheralSettings() })
        sheet.focusAction()
    }

    private fun showPreferences(focus: String? = null) {
        val sheet = newPanel("Personalizar","A aparência da sua tela inicial") { showActionCenter("action:customize") }
        val mode = prefs.getInt("wallpaper_mode",-1)
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
        sheet.focusAction(focus)
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

    private fun showAppMenu(app: TvApp) {
        val pin = app.packageName in pinnedApps()
        val sheet = newPanel(app.name,"Opções do aplicativo")
        sheet.row(PanelAction("app:pin",if(pin) "Desafixar" else "Fixar nos mais utilizados",
            if(pin) "Ordenar novamente pelo uso" else "Manter no início da lista",TvIcon.Kind.PIN,pin) {
            val all = pinnedApps().toMutableSet()
            if(pin) all.remove(app.packageName) else all.add(app.packageName)
            prefs.edit().putStringSet("pinned_apps",all).apply()
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
        sheet.focusAction()
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
                if (isSystemApp && !isStreamingApp(pkg, name,
                    if (Build.VERSION.SDK_INT >= 26) info.category else -1)) continue
                // Home/Configurações mantidos só na central superior.
                if (isInternalLauncherOrSettings(pkg, name)) continue
                found[pkg] = TvApp(name, pkg, ComponentName(pkg, activity.name))
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

}
