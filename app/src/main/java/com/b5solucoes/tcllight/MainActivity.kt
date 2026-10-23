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
    private val ui by lazy { TvStyle(this, ::economy) }
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
    private var wallpapers: WallpaperDeck? = null
    private var panel: LauncherPanel? = null
    private var panelBack: (() -> Unit)? = null
    private var activePanel = ""
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
        tvSources.start()
        refreshInputs()
        weather.start()
        if(appsDirty) refresh()
        if(usageDirty) { usageDirty = false; renderApps() }
        requestArtworkUpdate()
    }

    override fun onStop() {
        finishMoving(true)
        foreground = false
        handler.removeCallbacks(clockUpdate)
        wallpapers?.onBackground()
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        if(networkRegistered) { try { cm.unregisterNetworkCallback(networkEvents) } catch(_: Exception) {} }
        tvSources.stop()
        weather.stop()
        networkRegistered = false
        super.onStop()
    }

    override fun onDestroy() {
        if(packageReceiverRegistered) unregisterReceiver(packageEvents)
        handler.removeCallbacksAndMessages(null)
        scanner.shutdownNow()
        artwork.close()
        wallpapers?.close()
        weather.close()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(level >= TRIM_MEMORY_RUNNING_LOW) artwork.trim()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        finishMoving(true)
        closePanel()
        listScroll.smoothScrollTo(0,0)
        (topViews.firstOrNull() ?: centralButton).requestFocus()
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
            setPadding(dp(42), dp(10), dp(42), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(225, 8, 17, 27), Color.argb(110, 8, 17, 27)))
        }
        homeLayer.addView(nav,lp(-1,dp(76)))
        val climate = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        weatherIcon = ImageView(this).apply { setImageDrawable(TvIcon(TvIcon.Kind.CLOUD, ui.accent)) }
        climate.addView(weatherIcon,lp(dp(30),dp(30),0,0,12))
        val climateText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        climateText.addView(ui.text("Garrafão do Norte",12f,ui.muted).apply { maxLines = 1 })
        weatherLabel = ui.text("Clima indisponível",14f,medium=true).apply {
            tag = "header:weather"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        climateText.addView(weatherLabel,lp(-1,-2,0,3))
        climate.addView(climateText,lp(dp(184),-2))
        nav.addView(climate,lp(dp(230),-2))
        nav.addView(View(this).apply { setBackgroundColor(Color.argb(55,190,215,227)) },lp(dp(1),dp(30),14,0,20))
        val clock = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        timeLabel = ui.text("--:--",26f,medium=true).apply { tag = "header:time"; maxLines = 1 }
        dateLabel = ui.text("",11.5f,ui.muted).apply { tag = "header:date"; maxLines = 1 }
        clock.addView(timeLabel)
        clock.addView(dateLabel,lp(-2,-2,12))
        nav.addView(clock,LinearLayout.LayoutParams(0,-2,1f))
        networkLabel = ui.text("Rede",11f)
        networkIcon = ImageView(this)
        val net = navControl("nav:wifi",TvIcon.Kind.WIFI,"Conexão de rede",networkLabel,networkIcon) { openWifiSettings() }
        nav.addView(net,lp(dp(80),dp(39),8,0,8)); navViews.add(net)
        deviceLabel = ui.text("Entradas",11f)
        val devices = navControl("nav:inputs",TvIcon.Kind.CABLE,"Entradas de vídeo",deviceLabel) { showInputs() }
        nav.addView(devices,lp(dp(92),dp(39),0,0,8)); navViews.add(devices)
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
        listScroll.viewTreeObserver.addOnScrollChangedListener { requestArtworkUpdate() }
        val sceneSpace = ((resources.configuration.screenHeightDp-440)*.38f).toInt().coerceIn(32,110)
        column.addView(View(this),lp(-1,dp(sceneSpace)))
        val usedHeader = section("Mais utilizados","Pelo seu uso")
        orderLabel = (usedHeader as LinearLayout).getChildAt(1) as TextView
        column.addView(usedHeader,lp(-1,dp(28),0,0,3,6))
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
        controlHint = ui.text("OK  Abrir    ·    Segure OK  Mover e opções",9.5f,ui.quiet).apply { tag = "home:hint" }
        footer.addView(controlHint,
            LinearLayout.LayoutParams(0,-2,1f))
        footer.addView(ui.text("MENU  Central de ações",9.5f,ui.muted))
        homeLayer.addView(footer,lp(-1,dp(27),46,0,46,5))
        centralButton.requestFocus()
    }

    private fun section(title: String,meta: String): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.text(title,18f,medium=true),LinearLayout.LayoutParams(0,-2,1f))
        addView(ui.text(meta,10.5f,ui.muted))
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
            setPadding(dp(5),dp(5),dp(5),dp(5))
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
        artworkBindings.add(ArtworkBinding(card,image,fallback,app.component,large))
        if(app.packageName in pinnedApps()) surface.addView(ImageView(this).apply {
            setImageDrawable(TvIcon(TvIcon.Kind.PIN,ui.accent))
            background = ui.surface(Color.argb(222,7,19,28),6)
            setPadding(dp(4),dp(4),dp(4),dp(4))
        },FrameLayout.LayoutParams(dp(23),dp(23),Gravity.TOP or Gravity.END).apply {
            setMargins(0,dp(5),dp(5),0)
        })
        card.addView(surface,lp(-1,dp(artHeight)))
        val key = (if(large) "top:" else "grid:") + app.packageName
        styleHome(card,key,true)
        if (moving?.packageName == app.packageName && moving?.lane == (if (large) "top" else "grid")) {
            card.background = ui.surface(Color.argb(65, 135, 225, 207), 14, ui.accent)
        }
        card.setOnClickListener { open(app) }
        card.setOnLongClickListener { showAppMenu(app, if (large) "top" else "grid"); true }
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
        setTextIfChanged(orderLabel, if (orderFor("top").isEmpty()) "Pelo seu uso" else "Sua ordem")
        wireNavigation(columns)
        homeLayer.post { requestArtworkUpdate() }
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

    private fun requestArtworkUpdate() {
        if(!artworkUpdatePending && foreground && !isDestroyed) {
            artworkUpdatePending = true
            handler.postDelayed(updateVisibleArtwork,16L)
        }
    }

    /** Only visible cards own a bitmap; offscreen rows return to the bounded cache. */
    private fun refreshVisibleArtwork() {
        artworkBindings.removeAll { it.card.parent == null }
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
                if (foreground && activePanel == "inputs") {
                    val focus = currentFocus?.tag as? String
                    showInputs(focus)
                }
                if (inputsAgain) { inputsAgain = false; refreshInputs() }
            }
        }
    }

    private fun newPanel(title: String,subtitle: String,back: (() -> Unit)? = null): LauncherPanel {
        activePanel = ""
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
        frame.removeView(old); panel = null; panelBack = null; activePanel = ""
        homeLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        homeLayer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        (homeViews[returnFocus] ?: topViews.firstOrNull() ?: centralButton).requestFocus()
    }

    private fun showActionCenter(focus: String? = null) {
        val sheet = newPanel("Central de ações","Conexões e atalhos da sua TV")
        sheet.grid(listOf(
            PanelAction("action:wifi","Rede",wifiState(),TvIcon.Kind.WIFI,networkType() != -1) { openWifiSettings() },
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
        sheet.caption("Mais controles")
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
        sheet.focusAction(focus)
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
        sheet.row(PanelAction("pref:order","Restaurar organização","Mais utilizados pelo uso; aplicativos em ordem alfabética",TvIcon.Kind.REFRESH) {
            prefs.edit().remove("order.top").remove("order.grid").apply()
            renderApps(); showPreferences("pref:order")
        })
        sheet.paragraph("Clima de Garrafão do Norte: Open-Meteo (CC BY 4.0). Atualização aproximada a cada 45 minutos. A tela inicial continua disponível sem internet.")
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
        try { startActivity(Intent(Intent.ACTION_TV_INPUT_BUTTON).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
