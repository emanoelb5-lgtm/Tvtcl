package com.b5solucoes.tcllight

import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Remote-first keyboard. Results scroll independently, so typing never recreates or moves the keyboard. */
internal class TvSearchWorkspace(private val ui: TvStyle,private val sheet: LauncherPanel,initial: String,
    changed:(String)->Unit,submit:()->Unit,voice:()->Unit,source:()->Unit,scrolled:()->Unit) {
    val view=LinearLayout(ui.context).apply { clipChildren=false }
    val results=LinearLayout(ui.context).apply { orientation=LinearLayout.VERTICAL;setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(8));clipChildren=false }
    private val keys=ArrayList<View>()
    private val targets=ArrayList<View>()
    private val input=EditText(ui.context).apply {
        tag="media-search:query";id=View.generateViewId();setSingleLine();setText(initial);setSelection(text.length)
        textSize=16f*ui.textScale;setTextColor(ui.ink);setHintTextColor(ui.quiet);hint="Filmes e séries"
        inputType=InputType.TYPE_CLASS_TEXT;imeOptions=EditorInfo.IME_ACTION_SEARCH;showSoftInputOnFocus=false
        filters=arrayOf(InputFilter.LengthFilter(128));setPadding(ui.dp(11),0,ui.dp(11),0)
        background=ui.surface(ui.panelColor,10,ui.alpha(ui.accent,65))
        setOnEditorActionListener { _,action,event->
            if(action==EditorInfo.IME_ACTION_SEARCH || event?.keyCode==KeyEvent.KEYCODE_ENTER && event.action==KeyEvent.ACTION_UP) { submit();true } else false
        }
        addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { changed(s.toString()) }
            override fun afterTextChanged(s:Editable?) {}
        })
    }
    private val searchButton:View
    init {
        val left=LinearLayout(ui.context).apply { orientation=LinearLayout.VERTICAL }
        left.addView(input,LinearLayout.LayoutParams(-1,ui.height(42)).apply { bottomMargin=ui.dp(8) })
        val rows=LinearLayout(ui.context).apply { orientation=LinearLayout.VERTICAL;clipChildren=false }
        val alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        alphabet.chunked(6).forEachIndexed { rowIndex,row ->
            val line=LinearLayout(ui.context).apply { clipChildren=false }
            row.forEachIndexed { column,ch ->
                val key=ui.text(ch.toString(),13f,ui.ink,true).apply { gravity=Gravity.CENTER;contentDescription=ch.toString() }
                sheet.register(key,"search-key:$ch")
                key.setOnClickListener { edit(ch.toString()) }
                key.setOnKeyListener { _,code,event->
                    if(code==KeyEvent.KEYCODE_DPAD_RIGHT && column==5 && event.action==KeyEvent.ACTION_DOWN && targets.isNotEmpty()) {
                        targets.first().requestFocus();true
                    } else false
                }
                line.addView(key,LinearLayout.LayoutParams(0,-1,1f).apply { if(column<5) rightMargin=ui.dp(4) });keys.add(key)
            }
            rows.addView(line,LinearLayout.LayoutParams(-1,0,1f).apply { if(rowIndex<5) bottomMargin=ui.dp(4) })
        }
        left.addView(rows,LinearLayout.LayoutParams(-1,0,1f))
        val editing=LinearLayout(ui.context).apply { clipChildren=false }
        val editKeys=listOf("space" to "Espaço","backspace" to "Apagar","clear" to "Limpar").mapIndexed { i,(key,label)->
            ui.text(label,10.5f,ui.muted,true).apply {
                gravity=Gravity.CENTER;contentDescription=label;sheet.register(this,"search-key:$key")
                setOnClickListener { when(key) { "space"->edit(" ");"clear"->input.setText("");else->edit(null) } }
                editing.addView(this,LinearLayout.LayoutParams(0,-1,1f).apply { if(i<2) rightMargin=ui.dp(4) })
            }
        }
        left.addView(editing,LinearLayout.LayoutParams(-1,ui.height(32)).apply { topMargin=ui.dp(6);bottomMargin=ui.dp(6) })
        val controls=LinearLayout(ui.context).apply { clipChildren=false }
        searchButton=ui.text("Buscar",13f,ui.accent,true).apply {
            gravity=Gravity.CENTER;sheet.register(this,"media-search:submit");isSelected=true;setOnClickListener { submit() }
            setOnKeyListener { _,code,event->if(code==KeyEvent.KEYCODE_DPAD_RIGHT && event.action==KeyEvent.ACTION_DOWN && targets.isNotEmpty()) { targets.first().requestFocus();true } else false }
        }
        controls.addView(searchButton,LinearLayout.LayoutParams(0,-1,1f))
        val mic=ImageView(ui.context).apply {
            setImageDrawable(TvIcon(TvIcon.Kind.MIC,ui.muted));setPadding(ui.dp(9),ui.dp(9),ui.dp(9),ui.dp(9))
            contentDescription="Pesquisar por voz";sheet.register(this,"media-search:voice");setOnClickListener { voice() }
        }
        controls.addView(mic,LinearLayout.LayoutParams(ui.dp(38),-1).apply { leftMargin=ui.dp(6) })
        left.addView(controls,LinearLayout.LayoutParams(-1,ui.height(36)))
        view.addView(left,LinearLayout.LayoutParams(minOf(ui.dp(280),(ui.context.resources.displayMetrics.widthPixels*.31f).toInt()),-1).apply { rightMargin=ui.dp(20) })
        val right=LinearLayout(ui.context).apply { orientation=LinearLayout.VERTICAL;clipChildren=false }
        val scroll=ScrollView(ui.context).apply { isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipChildren=true;clipToPadding=true;addView(results);setOnScrollChangeListener { _,_,_,_,_->scrolled() } }
        right.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val attribution=ui.text("JustWatch  ·  Brasil  ↗",10.5f,ui.muted,true).apply {
            gravity=Gravity.CENTER_VERTICAL;setPadding(ui.dp(8),0,ui.dp(8),0);contentDescription="Ver resultados no JustWatch Brasil"
            sheet.register(this,"media-search:source",true);setOnClickListener { source() }
        }
        right.addView(attribution,LinearLayout.LayoutParams(-1,ui.dp(30)).apply { topMargin=ui.dp(5) })
        view.addView(right,LinearLayout.LayoutParams(0,-1,1f))
        keys.forEachIndexed { index,key ->
            key.nextFocusLeftId=keys[(index-1).coerceAtLeast(index/6*6)].id
            key.nextFocusRightId=keys[(index+1).coerceAtMost(index/6*6+5)].id
            key.nextFocusUpId=if(index>=6) keys[index-6].id else input.id
            key.nextFocusDownId=if(index<30) keys[index+6].id else editKeys[((index%6)/2).coerceAtMost(2)].id
        }
        editKeys.forEachIndexed { i,v->v.nextFocusUpId=keys[30+i*2].id;v.nextFocusDownId=searchButton.id }
        searchButton.nextFocusUpId=editKeys.first().id
        input.nextFocusDownId=keys.first().id
    }
    private fun edit(value: String?) {
        val start=input.selectionStart.coerceAtLeast(0);val end=input.selectionEnd.coerceAtLeast(start)
        if(value==null) { if(end>start) input.text.delete(start,end) else if(start>0) input.text.delete(start-1,start) }
        else input.text.replace(start,end,value)
    }
    fun setTargets(views: List<View>) {
        targets.clear();targets.addAll(views)
        views.forEachIndexed { index,v->if(index%2==0) v.setOnKeyListener { _,code,event->
            if(code==KeyEvent.KEYCODE_DPAD_LEFT && event.action==KeyEvent.ACTION_DOWN) { searchButton.requestFocus();true } else false
        } }
    }
    fun focusKeyboard() { sheet.focusAction("search-key:A") }
}
