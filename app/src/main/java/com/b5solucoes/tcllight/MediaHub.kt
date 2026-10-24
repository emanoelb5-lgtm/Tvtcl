package com.b5solucoes.tcllight

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

internal data class MediaApp(val name: String,val packageName: String)

/** Optional native multimedia surfaces. Provider queries, art and playback listeners have separate lifecycles. */
internal class MediaHub(private val activity: Activity, private val ui: TvStyle, private val prefs: SharedPreferences,
    private val apps: ()->List<MediaApp>, private val makePanel:(String,String,()->Unit)->LauncherPanel,
    private val isOpen:()->Boolean, private val backToCentral:()->Unit, private val openTv:()->Unit,
    private val styleHome:(View,String)->Unit, private val clearHome:()->Unit, private val homeChanged:()->Unit,
    private val closeSearch:()->Unit) {
    private val handler=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor { r->Thread(r,"tv-media-catalog") }
    private val catalog=TvMediaCatalog(activity.applicationContext)
    private val art=MediaArt(activity.applicationContext,handler)
    private val panelArt=MediaArt(activity.applicationContext,handler)
    private val online=StreamingSearch()
    private var searchResult:CatalogResult?=null
    private var searchLoading=false
    private var searchWorkspace:TvSearchWorkspace?=null
    private var headerSearch=false
    private val searchTyped=Runnable { if(started && isOpen() && screen=="search") renderSearchResults() }
    private val playback=MediaPlayback(activity,handler) { if(started && isOpen() && screen == "now") showNow(focus()) }
    private var home: LinearLayout?=null
    val homeRows=ArrayList<List<View>>()
    private var homeSignature=""
    private var screen="hub"
    private var started=false
    private var closed=false
    private var busyWatch=false
    private var busyGuide=false
    private var watchAgain=false
    private var guideDirty=true
    private var guideAt=0L
    private var registered=false
    private var programBoundary: Runnable?=null
    private val signals=ArrayList<CancellationSignal>()
    private var watch=WatchCatalog()
    private var guide=GuideCatalog()
    var query=""
    private var formOpen=false
    private val observer=object:ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
            guideDirty=true
            handler.removeCallbacks(changed)
            handler.postDelayed(changed,250L)
        }
    }
    private val changed=Runnable { if(started) { if(prefs.getBoolean("media.continue",true) || screen == "watch" && isOpen()) refreshWatch();if(isOpen() && screen == "guide") refreshGuide() } }
    private val prefListener=SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if(key?.startsWith("media.") == true && !closed) renderHome() }
    private fun focus()=activity.currentFocus?.tag as? String
    private fun library()=MediaLibrary.decode(prefs.getString(MediaLibrary.PREF,null))
    private fun selectedApps(): List<MediaApp> {
        val selected=prefs.getStringSet("media.services",null)
        return apps().filter { selected == null || it.packageName in selected }.distinctBy { it.packageName }.take(80)
    }
    private fun label(owner: String)=apps().firstOrNull { it.packageName == owner }?.name ?: "Aplicativo de origem"
    private fun toast(message: String)=Toast.makeText(activity,message,Toast.LENGTH_LONG).show()
    private fun sheet(title: String,subtitle: String,key: String,back:()->Unit): LauncherPanel {
        screen=key
        return makePanel(title,subtitle,back)
    }
    private fun LauncherPanel.action(key: String,title: String,detail: String="",icon: TvIcon.Kind=TvIcon.Kind.CHEVRON,run:()->Unit) =
        row(PanelAction(key,title,detail,icon,run=run))
    private fun save(entries: List<MediaEntry>) { prefs.edit().putString(MediaLibrary.PREF,MediaLibrary.encode(entries)).apply();renderHome() }
    fun attachHome(container: LinearLayout) { home=container;prefs.registerOnSharedPreferenceChangeListener(prefListener);renderHome() }
    fun start() {
        if(started) return
        started=true
        if(!registered) try { activity.contentResolver.registerContentObserver(android.net.Uri.parse("content://android.media.tv"),true,observer);registered=true } catch(_:Exception) { }
        if(prefs.getBoolean("media.playback",false)) playback.start()
        if(prefs.getBoolean("media.continue",true) || isOpen() && screen=="watch") refreshWatch()
        if(isOpen() && screen=="guide") showGuide(focus())
        if(isOpen() && screen == "now") showNow()
        art.start()
        if(isOpen()) panelArt.start()
    }
    fun stop() {
        started=false;watchAgain=false
        handler.removeCallbacks(changed)
        programBoundary?.let { handler.removeCallbacks(it) };programBoundary=null
        signals.toList().forEach { it.cancel() }
        if(registered) runCatching { activity.contentResolver.unregisterContentObserver(observer) }
        registered=false;playback.stop();art.stop();online.cancel();searchLoading=false;panelArt.stop()
    }
    fun close() { closed=true;stop();prefs.unregisterOnSharedPreferenceChangeListener(prefListener);handler.removeCallbacksAndMessages(null);worker.shutdownNow();art.close();panelArt.close();online.close() }
    fun trim() { art.trim();panelArt.trim() }
    fun refreshVisible() { art.refreshVisible();panelArt.refreshVisible() }
    fun appsChanged() { if(isOpen() && screen == "search") renderSearchResults() }
    fun panelClosed() { online.cancel();searchLoading=false;searchWorkspace=null;handler.removeCallbacks(searchTyped);panelArt.stop();panelArt.clearBindings() }

    private fun <T> read(work:(CancellationSignal)->T,done:(T)->Unit) {
        val signal=CancellationSignal();signals.add(signal)
        val timeout=Runnable { signal.cancel() };handler.postDelayed(timeout,4000L)
        worker.execute {
            val result=runCatching { work(signal) }
            handler.post {
                handler.removeCallbacks(timeout);signals.remove(signal)
                if(!closed) result.onSuccess(done)
            }
        }
    }
    private fun refreshWatch() {
        if(closed || !started) return
        if(busyWatch) { watchAgain=true;return };busyWatch=true
        read({ catalog.watch(it) }) {
            busyWatch=false
            if(started) { watch=it;renderHome();if(isOpen() && screen == "watch" && !formOpen) showWatch(focus()) }
            if(watchAgain) { watchAgain=false;refreshWatch() }
        }
    }
    private fun refreshGuide(force: Boolean=false) {
        if(closed || !started || busyGuide || !force && !guideDirty && System.currentTimeMillis()-guideAt < 5*60*1000L) return
        busyGuide=true
        read({ catalog.guide(signal=it) }) {
            busyGuide=false
            if(started) { guide=it;guideDirty=false;guideAt=System.currentTimeMillis();if(isOpen() && screen == "guide" && !formOpen) showGuide(focus()) }
        }
    }

    fun renderHome() {
        val area=home ?: return
        val saved=library().take(8)
        val shown=watch.items.take(8)
        val next=listOf(prefs.getBoolean("media.home",true),prefs.getBoolean("media.continue",true),prefs.getBoolean("media.list",true),
            saved,shown,ui.textScale).toString()
        if(next == homeSignature) return
        homeSignature=next
        val keep=focus()
        clearHome();homeRows.clear();art.clearBindings();area.removeAllViews()
        if(!prefs.getBoolean("media.home",true)) { area.visibility=View.GONE;homeChanged();return }
        if((!prefs.getBoolean("media.continue",true) || shown.isEmpty()) && (!prefs.getBoolean("media.list",true) || saved.isEmpty())) {
            area.visibility=View.GONE;homeChanged();return
        }
        area.visibility=View.VISIBLE
        fun caption(title: String,detail: String) {
            val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
            row.addView(ui.text(title,16f,medium=true),LinearLayout.LayoutParams(0,-2,1f))
            row.addView(ui.text(detail,9.5f,ui.quiet).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END })
            area.addView(row,LinearLayout.LayoutParams(-1,ui.height(22)).apply { bottomMargin=ui.dp(4) })
        }
        fun cards(title: String,entries: List<MediaEntry>,progress:(MediaEntry)->Int?={null},detail:(MediaEntry)->String) {
            if(entries.isEmpty()) return
            caption(title,"Segure OK para ver opções")
            val scroll=HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipChildren=false;clipToPadding=false }
            val row=LinearLayout(activity).apply { setPadding(ui.dp(4),ui.dp(5),ui.dp(4),ui.dp(5));clipChildren=false }
            val views=ArrayList<View>()
            entries.forEach { entry ->
                val card=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(ui.dp(7),ui.dp(7),ui.dp(7),ui.dp(8));contentDescription=entry.title+". "+detail(entry) }
                val cover=FrameLayout(activity).apply { background=ui.cardSurface(10);clipToOutline=true }
                val placeholder=ImageView(activity).apply { setPadding(ui.dp(28),ui.dp(28),ui.dp(28),ui.dp(28));setImageDrawable(TvIcon(if(entry.kind=="channel")TvIcon.Kind.TV else TvIcon.Kind.PLAY,ui.accent)) }
                cover.addView(placeholder,FrameLayout.LayoutParams(-1,-1))
                val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                cover.addView(image,FrameLayout.LayoutParams(-1,-1));art.bind(image,placeholder,entry.cover)
                cover.addView(View(activity).apply {
                    background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,ui.alpha(ui.base,190)))
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },FrameLayout.LayoutParams(-1,ui.dp(42),Gravity.BOTTOM))
                cover.addView(ui.text(entry.kindLabel,8.5f,ui.ink,true).apply {
                    background=ui.surface(ui.alpha(ui.base,225),5,ui.alpha(ui.accent,40));setPadding(ui.dp(6),ui.dp(4),ui.dp(6),ui.dp(4))
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply { leftMargin=ui.dp(7);bottomMargin=ui.dp(7) })
                progress(entry)?.let { percent ->
                    val track=FrameLayout(activity).apply { setBackgroundColor(ui.alpha(ui.ink,45));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                    val fill=View(activity).apply { setBackgroundColor(ui.accent) }
                    track.addView(fill,FrameLayout.LayoutParams(0,-1))
                    track.addOnLayoutChangeListener { _,left,_,right,_,_,_,_,_ ->
                        val desired=((right-left)*percent.coerceIn(0,100)/100f).toInt()
                        if(fill.layoutParams.width!=desired) fill.layoutParams=fill.layoutParams.apply { width=desired }
                    }
                    cover.addView(track,FrameLayout.LayoutParams(-1,ui.dp(3),Gravity.BOTTOM))
                }
                card.addView(cover,LinearLayout.LayoutParams(-1,ui.dp(93)))
                card.addView(ui.text(entry.title,12f,medium=true).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END },LinearLayout.LayoutParams(-1,ui.height(34)).apply { topMargin=ui.dp(8) })
                card.addView(ui.text(detail(entry),10f,ui.muted).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END })
                styleHome(card,"media-home:"+title+":"+entry.id)
                card.setOnClickListener { openEntry(entry) }
                card.setOnLongClickListener { card.requestFocus();showEntry(entry);true }
                row.addView(card,LinearLayout.LayoutParams(ui.dp(180),-2).apply { rightMargin=ui.dp(10) });views.add(card)
            }
            scroll.addView(row);area.addView(scroll);homeRows.add(views)
        }
        if(prefs.getBoolean("media.continue",true)) cards("Continuar assistindo",shown.map { it.entry },
            { entry->shown.firstOrNull { it.entry.id==entry.id && it.duration>0 }?.percent }) { entry->shown.first { it.entry.id==entry.id }.progressLabel }
        if(prefs.getBoolean("media.list",true)) cards("Minha lista",saved) { it.kindLabel }
        homeChanged()
        area.post { if(!closed) { art.refreshVisible();if(!isOpen() && keep?.startsWith("media-home:") == true) homeRows.flatten().firstOrNull { it.tag==keep }?.requestFocus() } }
    }

    fun show(focus: String?=null) {
        val s=sheet("Multimídia","Filmes, séries e programação","hub",backToCentral)
        s.expand()
        val saved=library();val last=watch.items.firstOrNull()?.entry
        val actions=listOf(
            PanelAction("media:search","Buscar conteúdo","Filmes e séries · Onde assistir",TvIcon.Kind.SEARCH) { headerSearch=false;showSearch() },
            PanelAction("media:list","Minha lista","${saved.size} itens · "+(saved.firstOrNull()?.title ?: "Guarde o que deseja assistir"),TvIcon.Kind.PIN) { showList() },
            PanelAction("media:watch","Continuar assistindo",last?.title ?: "Compartilhado pelos aplicativos",TvIcon.Kind.PLAY) { showWatch();refreshWatch() },
            PanelAction("media:guide","Programação","Agora e a seguir na sua TV",TvIcon.Kind.CALENDAR) { showGuide() },
            PanelAction("media:reminders","Lembretes","${MediaReminders.read(activity).size} avisos agendados",TvIcon.Kind.BELL) { showReminders() },
            PanelAction("media:now","Reproduzindo agora",playback.info.title,TvIcon.Kind.PLAY) { showNow() })
        gallery(s,actions,3) { when(it.key) { "media:list"->saved.firstOrNull()?.cover.orEmpty();"media:watch"->last?.cover.orEmpty();else->"" } }
        s.action("media:settings","Personalizar multimídia","Faixas da Home e aplicativos de busca",TvIcon.Kind.CONTROLS) { showSettings() }
        s.focusAction(focus)
    }

    private fun gallery(s:LauncherPanel,actions:List<PanelAction>,columns:Int=2,cover:(PanelAction)->String={""}) {
        s.onScroll { panelArt.refreshVisible() }
        actions.chunked(columns).forEach { group->
            val row=LinearLayout(activity).apply { clipChildren=false }
            group.forEachIndexed { index,item->
                val card=TvContentCards.create(ui,item.title,"",item.detail,item.icon,cover(item),panelArt)
                s.register(card,item.key);card.setOnClickListener { item.run() }
                row.addView(card,LinearLayout.LayoutParams(0,-2,1f).apply { if(index<columns-1) rightMargin=ui.dp(10) })
            }
            repeat(columns-group.size) { row.addView(View(activity),LinearLayout.LayoutParams(0,1,1f)) }
            s.content.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=ui.dp(12) })
        }
        s.post { if(started) { panelArt.start();panelArt.refreshVisible() } }
    }

    fun showList(focus: String?=null) {
        val s=sheet("Minha lista","Filmes, séries, canais e links","list") { show("media:list") }
        s.expand()
        val entries=library()
        val actions=listOf(PanelAction("media-list:add","Adicionar à lista","Escolher título e aplicativo",TvIcon.Kind.PIN) { editNewEntry() })+
            entries.map { entry->PanelAction("media-list:"+entry.id,entry.title,entry.kindLabel,TvIcon.Kind.PLAY) { showEntry(entry) } }
        gallery(s,actions,3) { action->entries.firstOrNull { action.key=="media-list:"+it.id }?.cover.orEmpty() }
        if(entries.isEmpty()) s.notice("Guarde aqui o que você quer assistir. Sua lista é local e pode ser incluída no backup do launcher.",TvIcon.Kind.PIN)
        s.focusAction(focus)
    }
    fun showWatch(focus: String?=null) {
        val s=sheet("Continuar assistindo","Conteúdo compartilhado pelos aplicativos","watch") { show("media:watch") }
        s.notice(watch.message,TvIcon.Kind.PLAY)
        tvPermissionAction(s,"watch:permission")
        watch.items.forEach { item->s.action("watch:"+item.entry.id,item.entry.title,item.progressLabel+" · "+label(item.entry.packageName),TvIcon.Kind.PLAY) { showEntry(item.entry) } }
        s.action("watch:refresh","Atualizar","Consultar os aplicativos novamente",TvIcon.Kind.REFRESH) { refreshWatch() }
        s.action("watch:list","Abrir Minha lista","Sua seleção pessoal",TvIcon.Kind.PIN) { showList() }
        s.focusAction(focus)
    }
    fun showEntry(entry: MediaEntry) {
        val saved=library().firstOrNull { it.id==entry.id || it.title==entry.title && it.link==entry.link && it.packageName==entry.packageName }
        val s=sheet(entry.title,entry.kindLabel,"entry") { if(saved==null) showWatch("watch:"+entry.id) else showList("media-list:"+saved.id) }
        watch.items.firstOrNull { it.entry.id==entry.id }?.let { s.paragraph(it.progressLabel) }
        s.action("media-entry:open","Abrir conteúdo",if(entry.packageName.isBlank()) "Abrir no aplicativo disponível" else label(entry.packageName),TvIcon.Kind.PLAY) { openEntry(entry) }
        s.action("media-entry:search","Pesquisar este título","Escolher um aplicativo",TvIcon.Kind.SEARCH) { query=entry.title;showSearch() }
        s.action("media-entry:reminder","Criar lembrete","Escolher data e horário",TvIcon.Kind.BELL) { pickReminderTime(entry) }
        if(saved == null) s.action("media-entry:save","Salvar na Minha lista","Guardar para assistir depois",TvIcon.Kind.PIN) {
            try { save(MediaLibrary.add(library(),entry));showEntry(entry) } catch(_:Exception) { toast("Não foi possível salvar. Verifique o limite de itens e o link.") }
        } else {
            s.action("media-entry:rename","Renomear","Alterar o nome nesta lista",TvIcon.Kind.INFO) {
                textDialog("Nome do conteúdo",saved.title) { title->save(library().map { if(it.id==saved.id) it.copy(title=title) else it });showEntry(saved.copy(title=title)) }
            }
            val entries=library();val index=entries.indexOfFirst { it.id==saved.id }
            if(index>0) s.action("media-entry:left","Mover para antes","Alterar a ordem da lista",TvIcon.Kind.LEFT) { save(MediaLibrary.move(library(),saved.id,-1));showEntry(saved) }
            if(index<entries.lastIndex) s.action("media-entry:right","Mover para depois","Alterar a ordem da lista",TvIcon.Kind.RIGHT) { save(MediaLibrary.move(library(),saved.id,1));showEntry(saved) }
            s.action("media-entry:remove","Remover da lista","O aplicativo permanece instalado",TvIcon.Kind.CLOSE) { save(library().filterNot { it.id==saved.id });showList() }
        }
        s.focusAction()
    }

    private fun launch(intent: Intent): Boolean = try { activity.startActivity(intent);true } catch(_:Exception) { false }
    private fun launchApp(owner: String): Boolean {
        val intent=activity.packageManager.getLeanbackLaunchIntentForPackage(owner) ?: activity.packageManager.getLaunchIntentForPackage(owner)
        return intent != null && launch(intent)
    }
    private fun openEntry(entry: MediaEntry) {
        val direct=entry.link.takeIf { it.isNotBlank() }?.let { MediaLinks.parse(it,entry.packageName) }
        if(direct != null && launch(direct)) return
        if(entry.kind=="channel") { openTv();return }
        query=entry.title
        if(entry.packageName.isNotEmpty()) searchIn(MediaApp(label(entry.packageName),entry.packageName)) else showSearch()
    }
    private fun textDialog(title: String,value: String="",done:(String)->Unit) {
        val field=EditText(activity).apply { tag="media-form:text";setSingleLine();setText(value);filters=arrayOf(InputFilter.LengthFilter(128));inputType=InputType.TYPE_CLASS_TEXT }
        val box=LinearLayout(activity).apply { setPadding(ui.dp(24),ui.dp(8),ui.dp(24),ui.dp(8));addView(field,LinearLayout.LayoutParams(-1,-2)) }
        formOpen=true
        val dialog=AlertDialog.Builder(activity).setTitle(title).setView(box).setNegativeButton("Cancelar",null).setPositiveButton("Confirmar",null).create()
        dialog.setOnDismissListener { formOpen=false }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text=field.text.toString().trim()
            if(text.isBlank() || !MediaLibrary.validText(text,128)) field.error="Informe um nome válido."
            else try { done(text);dialog.dismiss() } catch(_:Exception) { field.error="Não foi possível salvar este conteúdo." }
        } }
        dialog.show();dialog.window?.setLayout(ui.dp(minOf(600,activity.resources.configuration.screenWidthDp-ui.horizontalInset*2)),-2)
    }
    private fun editNewEntry() {
        val choices=listOf(MediaApp("Escolher depois",""))+apps().take(80)
        val box=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(ui.dp(24),ui.dp(8),ui.dp(24),ui.dp(8)) }
        fun label(text: String) { box.addView(ui.text(text,12f,ui.muted),LinearLayout.LayoutParams(-1,-2).apply { topMargin=ui.dp(8) }) }
        label("Título")
        val title=EditText(activity).apply { tag="media-form:title";setSingleLine();filters=arrayOf(InputFilter.LengthFilter(128)) };box.addView(title)
        label("Tipo de conteúdo")
        val kind=Spinner(activity).apply { tag="media-form:kind";adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,listOf("Filme","Série","Canal","Link")) };box.addView(kind)
        label("Aplicativo")
        val app=Spinner(activity).apply { tag="media-form:app";adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,choices.map { it.name }) };box.addView(app)
        label("Link HTTPS do conteúdo (opcional)")
        val link=EditText(activity).apply { tag="media-form:link";setSingleLine();inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI;filters=arrayOf(InputFilter.LengthFilter(2048)) };box.addView(link)
        formOpen=true
        val scroll=ScrollView(activity).apply { addView(box) }
        val dialog=AlertDialog.Builder(activity).setTitle("Adicionar à Minha lista").setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Salvar",null).create()
        dialog.setOnDismissListener { formOpen=false }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text=title.text.toString().trim();val url=link.text.toString().trim()
            if(text.isBlank() || !MediaLibrary.validText(text,128)) title.error="Informe um título."
            else if(url.isNotEmpty() && (android.net.Uri.parse(url).scheme != "https" || MediaLinks.parse(url,choices[app.selectedItemPosition].packageName)==null)) link.error="Informe um link HTTPS válido."
            else try {
                val entry=MediaEntry(title=text,kind=MediaLibrary.kinds[kind.selectedItemPosition],packageName=choices[app.selectedItemPosition].packageName,link=url)
                save(MediaLibrary.add(library(),entry));dialog.dismiss();showList("media-list:"+entry.id)
            } catch(_:Exception) { title.error="A lista atingiu o limite de itens ou de tamanho." }
        } }
        dialog.show();dialog.window?.setLayout(ui.dp(minOf(600,activity.resources.configuration.screenWidthDp-ui.horizontalInset*2)),-2)
    }

    fun showSearchFromHeader() { headerSearch=true;showSearch() }

    fun showSearch(focus: String?=null) {
        val s=sheet("Buscar conteúdo","Filmes e séries · Onde assistir no Brasil","search") {
            if(headerSearch) closeSearch() else show("media:search")
        }
        s.expand()
        val workspace=TvSearchWorkspace(ui,s,query,{ text->
            query=text.filterNot { Character.isISOControl(it) }.take(128)
            online.cancel();searchLoading=false
            if(searchResult?.query!=query) searchResult=null
            handler.removeCallbacks(searchTyped);handler.postDelayed(searchTyped,160L)
        },::submitSearch,::voiceSearch,{ openWeb(StreamingPages.searchUrl(query)) },{ panelArt.refreshVisible() })
        searchWorkspace=workspace;s.workspace(workspace.view)
        renderSearchResults()
        if(focus==null) workspace.focusKeyboard() else s.focusAction(focus)
    }

    private fun voiceSearch() {
        try { activity.startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"pt-BR").putExtra(RecognizerIntent.EXTRA_PROMPT,"O que você quer assistir?"),910) }
        catch(_:Exception) { toast("A busca por voz não está disponível. Use o teclado na tela.") }
    }

    private fun submitSearch() {
        val text=query.trim()
        if(text.isBlank()) { toast("Digite o nome de um filme ou série.");return }
        if(!MediaLibrary.validText(text,128)) return
        query=text;searchLoading=true;handler.removeCallbacks(searchTyped);renderSearchResults()
        online.search(text) { result->
            if(started && isOpen() && screen=="search" && query.trim()==result.query) {
                searchLoading=false;searchResult=result;renderSearchResults()
            }
        }
    }

    private fun renderSearchResults() {
        val workspace=searchWorkspace ?: return
        val area=workspace.results;val panel=area.rootView.findViewWithTag<LauncherPanel>("launcher-panel") ?: return
        val keep=focus();panelArt.clearBindings();area.removeAllViews();val targets=ArrayList<View>()
        fun label(value:String,color:Int=ui.muted) {
            area.addView(ui.text(value,12f,color).apply { setLineSpacing(ui.dp(3).toFloat(),1f) },
                LinearLayout.LayoutParams(-1,-2).apply { setMargins(ui.dp(6),ui.dp(4),ui.dp(6),ui.dp(12)) })
        }
        fun cardRows(actions: List<Triple<String,View,()->Unit>>) {
            actions.chunked(2).forEach { group->
                val row=LinearLayout(activity).apply { clipChildren=false }
                group.forEachIndexed { index,(key,card,run)->
                    panel.register(card,key);card.setOnClickListener { run() };targets.add(card)
                    row.addView(card,LinearLayout.LayoutParams(0,-2,1f).apply { if(index==0) rightMargin=ui.dp(9) })
                }
                if(group.size==1) row.addView(View(activity),LinearLayout.LayoutParams(0,1,1f))
                area.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=ui.dp(12) })
            }
        }
        if(query.isBlank()) label("O que você quer assistir?\nDigite com o controle e confirme em Buscar.")
        else {
            val local=(library()+watch.items.map { it.entry }).filter { it.title.contains(query.trim(),true) }.distinctBy { it.id }.take(4)
            if(local.isNotEmpty()) {
                label("Na sua lista e em Continuar assistindo",ui.accent)
                cardRows(local.map { entry->Triple("media-search:item:"+entry.id,
                    TvContentCards.create(ui,entry.title,entry.kindLabel,"Sua biblioteca",TvIcon.Kind.PLAY,entry.cover,panelArt)) { showEntry(entry) } })
            }
            val result=searchResult?.takeIf { it.query==query.trim() }
            label(if(searchLoading) "Pesquisando no catálogo…" else result?.message ?: "Confirme em Buscar para consultar os serviços de streaming.")
            if(result!=null && !searchLoading) cardRows(result.titles.map { title->Triple("stream-title:"+title.id,
                TvContentCards.create(ui,title.title,title.detail,title.services,TvIcon.Kind.PLAY,title.cover,panelArt)) { showOffers(title) } })
            val apps=ui.text("Pesquisar dentro de um aplicativo  ›",11.5f,ui.muted,true).apply {
                gravity=Gravity.CENTER_VERTICAL;setPadding(ui.dp(10),0,ui.dp(10),0);panel.register(this,"media-search:apps",true)
                setOnClickListener { showSearchApps() }
            }
            area.addView(apps,LinearLayout.LayoutParams(-1,ui.height(37)));targets.add(apps)
        }
        workspace.setTargets(targets)
        area.post {
            if(started && isOpen() && screen=="search") {
                panelArt.start();panelArt.refreshVisible()
                if(keep?.startsWith("stream-title:")==true || keep?.startsWith("media-search:item:")==true)
                    area.findViewWithTag<View>(keep)?.requestFocus()
            }
        }
    }

    private fun showSearchApps() {
        val s=sheet("Pesquisar nos aplicativos",query,"search-apps") { showSearch("media-search:apps") }
        val installed=selectedApps()
        if(installed.isEmpty()) s.notice("Selecione seus aplicativos em Personalizar multimídia.")
        installed.forEach { app->s.action("media-search:app:"+app.packageName,app.name,
            if(MediaLinks.search(activity,app.packageName,query)!=null) "Pesquisar diretamente no aplicativo" else "Abrir aplicativo e copiar o título",TvIcon.Kind.SEARCH) { searchIn(app) } }
        s.focusAction()
    }

    private fun showOffers(title:StreamTitle) {
        val s=sheet(title.title,title.detail+" · Brasil","offers") { showSearch("stream-title:"+title.id) }
        s.expand()
        s.notice(if(title.offers.isEmpty()) "O catálogo não informou uma oferta para este título no Brasil." else "Escolha onde assistir. Planos, anúncios, aluguel e compra seguem as condições do serviço.",TvIcon.Kind.PLAY)
        s.grid(title.offers.mapIndexed { index,offer->PanelAction("stream-offer:$index",offer.service,offer.label,TvIcon.Kind.PLAY) {
            StreamingPages.intent(activity,offer,apps())?.let { if(!launch(it)) toast("Não foi possível abrir este conteúdo. Consulte o JustWatch.") }
        } })
        s.action("stream:save","Salvar na Minha lista",title.title,TvIcon.Kind.PIN) {
            val first=title.offers.firstOrNull { it.type in setOf("FLATRATE","FREE","ADS") } ?: title.offers.firstOrNull()
            val entry=MediaEntry(id=java.util.UUID.nameUUIDFromBytes(("justwatch:"+title.id).toByteArray(Charsets.UTF_8)).toString(),title=title.title,kind=title.kind,
                link=first?.url ?: title.page,cover=title.cover)
            runCatching { save(MediaLibrary.add(library(),entry)) }.fold({ toast("Adicionado à Minha lista.") },{ toast("A lista atingiu o limite de itens ou de tamanho.") })
        }
        s.action("stream:source","JustWatch","Consultar a disponibilidade no Brasil",TvIcon.Kind.INFO) { openWeb(title.page) }
        s.focusAction()
    }

    private fun openWeb(url:String) {
        StreamingPages.web(url)?.let { if(!launch(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(it)))) toast("Nenhum aplicativo disponível para abrir o link.") }
    }
    fun onVoiceResult(resultCode: Int,data: Intent?) {
        if(resultCode == Activity.RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.take(128)
            ?.takeIf { it.isNotBlank() && MediaLibrary.validText(it,128) }?.let { query=it }
        showSearch();if(query.isNotBlank()) submitSearch()
    }
    private fun searchIn(app: MediaApp) {
        val request=MediaLinks.search(activity,app.packageName,query)
        if(request != null && launch(request)) return
        val s=sheet("Pesquisar em ${app.name}",query,"search-app") { showSearchApps() }
        s.paragraph("Este aplicativo não oferece uma pesquisa direta compatível. Você pode copiar o título e pesquisar dentro dele.")
        s.action("media-search:copy","Copiar título",query,TvIcon.Kind.PIN) {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Título",query));toast("Título copiado.")
        }
        s.action("media-search:open","Abrir ${app.name}","Pesquisar dentro do aplicativo",TvIcon.Kind.PLAY) { if(!launchApp(app.packageName)) toast("O aplicativo não está disponível nesta TV.") }
        s.focusAction()
    }

    fun showGuide(focus: String?=null) {
        val s=sheet("Programação","Agora e a seguir","guide") { show("media:guide") }
        s.notice(guide.message,TvIcon.Kind.CALENDAR)
        tvPermissionAction(s,"media-guide:permission")
        s.action("media-guide:native","Abrir guia da TV","Consultar a programação no aplicativo da TV",TvIcon.Kind.TV) {
            if(!launch(Intent(Intent.ACTION_VIEW,TvContract.Programs.CONTENT_URI))) openTv()
        }
        s.action("media-guide:manual","Criar lembrete manual","Escolher programa, data e horário",TvIcon.Kind.BELL) { showReminderSources() }
        val favorites=library().filter { it.kind=="channel" }.map { it.link }.toSet()
        val now=System.currentTimeMillis()
        guide.channels.sortedBy { if(TvContract.buildChannelUri(it.id).toString() in favorites) 0 else 1 }.forEach { channel ->
            val current=channel.now(now);val next=channel.next(now)
            val text=if(current != null) "Agora: "+current.title+if(next != null) " · "+time(next.start)+" "+next.title else ""
                else if(next != null) time(next.start)+" "+next.title else "Horários não compartilhados pela TV"
            s.action("media-guide:channel:"+channel.id,(channel.number+" "+channel.name).trim(),text,TvIcon.Kind.TV) { showChannel(channel) }
        }
        s.action("media-guide:refresh","Atualizar programação","Consultar os dados da TV",TvIcon.Kind.REFRESH) { guideDirty=true;refreshGuide(true) }
        s.focusAction(focus);refreshGuide()
        programBoundary?.let { handler.removeCallbacks(it) }
        val next=guide.channels.flatMap { it.programs }.flatMap { listOf(it.start,it.end) }.filter { it>now }.minOrNull()
        programBoundary=next?.let { at->Runnable { if(started && isOpen() && screen=="guide") showGuide(focus()) }.also { handler.postDelayed(it,(at-now+500L).coerceAtLeast(1000L)) } }
    }

    private fun tvPermissionAction(panel: LauncherPanel,key: String) {
        if(activity.checkSelfPermission("android.permission.READ_TV_LISTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        panel.action(key,"Permitir acesso à programação","Autorizar os dados compartilhados pela TV",TvIcon.Kind.TV) {
            try {
                val info=activity.packageManager.getPermissionInfo("android.permission.READ_TV_LISTINGS",0)
                if(info.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS)
                    if(prefs.getBoolean("media.tv_permission_requested",false) && !activity.shouldShowRequestPermissionRationale("android.permission.READ_TV_LISTINGS"))
                        launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+activity.packageName)))
                    else { prefs.edit().putBoolean("media.tv_permission_requested",true).apply();activity.requestPermissions(arrayOf("android.permission.READ_TV_LISTINGS"),921) }
                else if(!launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+activity.packageName)))) toast("Esta TV não oferece a autorização de programação.")
            } catch(_:Exception) { toast("Esta TV não oferece a autorização de programação. Use o guia nativo ou os lembretes manuais.") }
        }
    }
    fun onTvPermissionResult() {
        guideDirty=true
        if(started) { refreshWatch();if(isOpen() && screen=="guide") refreshGuide(true) }
    }
    fun onNotificationPermissionResult() { if(isOpen() && screen=="reminders") showReminders(focus()) }
    private fun showChannel(channel: GuideChannel) {
        val entry=channel.entry()
        val s=sheet(entry.title,"Programação compartilhada pela TV","channel") { showGuide("media-guide:channel:"+channel.id) }
        s.action("media-channel:open","Assistir canal","Abrir no aplicativo de TV",TvIcon.Kind.TV) { openEntry(entry) }
        val saved=library().firstOrNull { it.kind=="channel" && it.link==entry.link }
        s.action("media-channel:favorite",if(saved==null) "Salvar canal favorito" else "Remover dos favoritos","Organizar na Minha lista",TvIcon.Kind.PIN) {
            save(if(saved==null) MediaLibrary.add(library(),entry) else library().filterNot { it.id==saved.id });showChannel(channel)
        }
        if(channel.programs.isEmpty()) s.paragraph("A TV não compartilhou horários para este canal. Consulte o guia nativo.")
        val now=System.currentTimeMillis()
        channel.programs.filter { it.end > now }.forEach { program ->
            s.action("media-program:"+program.id,program.title,time(program.start)+"–"+time(program.end)+if(program.start > now) " · Criar lembrete" else " · No ar",TvIcon.Kind.CALENDAR) {
                if(program.start > System.currentTimeMillis()) confirmReminder(entry.copy(title=(program.title+" · "+entry.title).take(128)),program.start)
                else openEntry(entry)
            }
        }
        s.focusAction()
    }

    private fun time(at: Long)=SimpleDateFormat("HH:mm",Locale("pt","BR")).format(Date(at))
    private fun dateTime(at: Long)=SimpleDateFormat("dd/MM 'às' HH:mm",Locale("pt","BR")).format(Date(at))
    fun showReminders(focus: String?=null) {
        val s=sheet("Lembretes","Sua programação pessoal","reminders") { show("media:reminders") }
        s.action("media-reminders:add","Novo lembrete","Escolher programa e horário",TvIcon.Kind.BELL) { showReminderSources() }
        val manager=activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) {
            s.paragraph("Os avisos estão desativados no Android. Os horários continuam disponíveis nesta lista.")
            s.action("media-reminders:permission","Permitir avisos","Abrir ajustes de notificações",TvIcon.Kind.BELL) {
                if(Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    activity.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"),920)
                else {
                    val settings=if(Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.packageName)
                        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+activity.packageName))
                    if(!launch(settings)) toast("Abra os ajustes de notificações da TV para permitir os avisos.")
                }
            }
        }
        val entries=MediaReminders.read(activity).sortedBy { it.startsAt }
        if(entries.isEmpty()) s.notice("Marque os programas que você quer acompanhar. O aviso é agendado para até cinco minutos antes; a TV precisa estar ligada ou permitir avisos em espera.",TvIcon.Kind.BELL)
        entries.forEach { reminder->s.action("media-reminder:"+reminder.id,reminder.entry.title,dateTime(reminder.startsAt)+
            if(reminder.fired || reminder.startsAt<=System.currentTimeMillis()) " · Horário atingido" else " · Agendado",TvIcon.Kind.BELL) { showReminder(reminder.id) } }
        s.focusAction(focus)
    }
    fun showReminder(id: String) {
        val reminder=MediaReminders.read(activity).firstOrNull { it.id==id } ?: return showReminders()
        val s=sheet(reminder.entry.title,dateTime(reminder.startsAt),"reminder") { showReminders("media-reminder:"+id) }
        s.action("media-reminder:open","Abrir conteúdo","Escolher como assistir",TvIcon.Kind.PLAY) { openEntry(reminder.entry) }
        s.action("media-reminder:remove","Remover lembrete","Cancelar o aviso e retirar da lista",TvIcon.Kind.CLOSE) { MediaReminders.remove(activity,id);showReminders() }
        s.focusAction()
    }
    private fun showReminderSources() {
        val s=sheet("Novo lembrete","Escolha o conteúdo","reminder-source") { showReminders() }
        s.action("media-reminders:manual","Digitar programa","Informar um título e depois o horário",TvIcon.Kind.CALENDAR) {
            textDialog("Nome do programa") { title->pickReminderTime(MediaEntry(title=title)) }
        }
        library().forEach { entry->s.action("media-reminders:source:"+entry.id,entry.title,entry.kindLabel) { pickReminderTime(entry) } }
        s.focusAction()
    }
    private fun pickReminderTime(entry: MediaEntry) {
        val selected=Calendar.getInstance().apply { add(Calendar.MINUTE,15) }
        formOpen=true
        val date=DatePickerDialog(activity,{ _,year,month,day ->
            selected.set(Calendar.YEAR,year);selected.set(Calendar.MONTH,month);selected.set(Calendar.DAY_OF_MONTH,day)
            val clock=TimePickerDialog(activity,{ _,hour,minute ->
                selected.set(Calendar.HOUR_OF_DAY,hour);selected.set(Calendar.MINUTE,minute);selected.set(Calendar.SECOND,0);selected.set(Calendar.MILLISECOND,0)
                formOpen=false;confirmReminder(entry,selected.timeInMillis)
            },selected.get(Calendar.HOUR_OF_DAY),selected.get(Calendar.MINUTE),true)
            clock.setOnCancelListener { formOpen=false };clock.show()
        },selected.get(Calendar.YEAR),selected.get(Calendar.MONTH),selected.get(Calendar.DAY_OF_MONTH))
        date.datePicker.minDate=System.currentTimeMillis()-1000L
        date.datePicker.maxDate=System.currentTimeMillis()+365*24*60*60*1000L
        date.setOnCancelListener { formOpen=false };date.show()
    }
    private fun confirmReminder(entry: MediaEntry,at: Long) {
        formOpen=true
        AlertDialog.Builder(activity).setTitle("Criar lembrete?").setMessage(entry.title+"\n"+dateTime(at)+"\nAvisar até cinco minutos antes, sem trocar o canal automaticamente.")
            .setNegativeButton("Cancelar",null).setPositiveButton("Criar") { _,_->
                try { val reminder=MediaReminders.add(activity,entry,at);showReminders("media-reminder:"+reminder.id);toast("Lembrete salvo.") }
                catch(error:Exception) { toast(error.message ?: "Não foi possível agendar. Confira o horário.") }
            }.setOnDismissListener { formOpen=false }.show()
    }

    fun showNow(focus: String?=null) {
        val s=sheet("Reproduzindo agora","Controles do aplicativo de mídia","now") { show("media:now") }
        val info=playback.info
        s.notice(info.title,TvIcon.Kind.PLAY).tag="media-now:title";s.paragraph(info.detail)
        if(info.owner.isNotEmpty()) {
            s.caption(label(info.owner))
            if(info.playing && info.canPause || !info.playing && info.canPlay)
                s.action("media-now:toggle",if(info.playing) "Pausar" else "Continuar","Controlar a reprodução atual",if(info.playing)TvIcon.Kind.PAUSE else TvIcon.Kind.PLAY) { if(!playback.toggle()) toast("Este aplicativo não aceitou o comando.") }
            s.action("media-now:open","Voltar ao aplicativo",label(info.owner),TvIcon.Kind.PLAY) { if(!playback.open() && !launchApp(info.owner)) toast("O aplicativo não está disponível.") }
        }
        if(!prefs.getBoolean("media.playback",false) || !playback.enabled()) s.action("media-now:access","Ativar controles de mídia","Autorizar o acesso nos ajustes do Android",TvIcon.Kind.CONTROLS) {
            AlertDialog.Builder(activity).setTitle("Ativar controles de mídia?")
                .setMessage("O Android pede acesso às notificações para identificar a reprodução de outros aplicativos. O TCL Light usa esse acesso somente para as sessões de mídia e não lê nem armazena suas notificações.")
                .setNegativeButton("Cancelar",null).setPositiveButton("Abrir ajustes") { _,_->
                    prefs.edit().putBoolean("media.playback",true).apply()
                    if(!launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) toast("Esta TV não oferece a tela de autorização. Os outros recursos continuam disponíveis.")
                }.show()
        }
        if(prefs.getBoolean("media.playback",false)) s.action("media-now:disable","Desativar controles no launcher","Encerrar a observação das sessões",TvIcon.Kind.CLOSE) {
            prefs.edit().putBoolean("media.playback",false).apply();playback.stop();showNow()
        }
        s.action("media-now:refresh","Atualizar controles","Consultar a reprodução atual",TvIcon.Kind.REFRESH) {
            if(prefs.getBoolean("media.playback",false)) { playback.stop();playback.start() };showNow()
        }
        s.focusAction(focus)
    }
    private fun showSettings(focus: String?=null) {
        val s=sheet("Personalizar multimídia","Faixas e aplicativos","settings") { show("media:settings") }
        listOf("media.home" to "Atalhos multimídia na Home","media.continue" to "Faixa Continuar assistindo","media.list" to "Faixa Minha lista").forEach { (key,title) ->
            val value=prefs.getBoolean(key,true)
            s.row(PanelAction("media-settings:"+key,title,if(value)"Ativado" else "Desativado",TvIcon.Kind.EYE,value) {
                prefs.edit().putBoolean(key,!value).apply();if(key=="media.continue" && !value) refreshWatch();showSettings("media-settings:"+key)
            })
        }
        s.action("media-settings:services","Aplicativos de busca","Selecionar os serviços que você usa",TvIcon.Kind.GRID) { showServices() }
        s.focusAction(focus)
    }
    private fun showServices(focus: String?=null) {
        val s=sheet("Aplicativos de busca","Escolha onde pesquisar","services") { showSettings("media-settings:services") }
        val selected=prefs.getStringSet("media.services",null) ?: apps().map { it.packageName }.toSet()
        apps().take(80).forEach { app->s.row(PanelAction("media-service:"+app.packageName,app.name,if(app.packageName in selected) "Selecionado" else "Oculto na busca",TvIcon.Kind.CHECK,app.packageName in selected) {
            val next=selected.toMutableSet();if(!next.add(app.packageName)) next.remove(app.packageName)
            prefs.edit().putStringSet("media.services",next).apply();showServices("media-service:"+app.packageName)
        }) }
        if(apps().isEmpty()) s.paragraph("Os aplicativos instalados aparecerão aqui.")
        s.focusAction(focus)
    }
}
