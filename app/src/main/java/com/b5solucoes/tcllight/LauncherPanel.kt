package com.b5solucoes.tcllight

import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ProgressBar
import android.content.res.ColorStateList

internal data class PanelAction(val key: String, val title: String, val detail: String,
    val icon: TvIcon.Kind, val selected: Boolean = false, val run: () -> Unit)

/** A focus-contained native sheet, shared by quick actions and customization. */
internal class LauncherPanel(private val ui: TvStyle, title: String, subtitle: String,
                             close: () -> Unit) : FrameLayout(ui.context) {
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(10))
    }
    private val actions = LinkedHashMap<String, View>()
    private val sheet = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(24),ui.dp(24),ui.dp(24),ui.dp(18))
        background = ui.surface(Color.rgb(13,22,33), 24, Color.argb(32,210,231,246))
        clipChildren = true
        clipToPadding = true
        clipToOutline = true
    }

    init {
        tag = "launcher-panel"
        isClickable = true
        val scrim = View(context).apply {
            setBackgroundColor(Color.argb(153,1,5,12))
            setOnClickListener { close() }
        }
        addView(scrim, LayoutParams(-1,-1))
        val width = minOf(ui.dp(432),(resources.displayMetrics.widthPixels * .56f).toInt())
        addView(sheet, LayoutParams(width,-1,Gravity.END).apply {
            setMargins(0,ui.dp(maxOf(12,ui.verticalInset-20)),ui.dp(maxOf(12,ui.horizontalInset-20)),ui.dp(maxOf(12,ui.verticalInset-20)))
        })
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(ui.text(title,24f,medium=true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        titles.addView(ui.text(subtitle,11f,ui.muted),LinearLayout.LayoutParams(-1,-2).apply {
            topMargin = ui.dp(7)
        })
        header.addView(titles,LinearLayout.LayoutParams(0,-2,1f))
        val exit = ImageView(context).apply {
            setImageDrawable(TvIcon(TvIcon.Kind.CLOSE,ui.muted))
            setPadding(ui.dp(11),ui.dp(11),ui.dp(11),ui.dp(11))
            contentDescription = "Fechar painel"
            setOnClickListener { close() }
        }
        ui.focus(exit,"panel:close",12,true)
        actions["panel:close"] = exit
        header.addView(exit,LinearLayout.LayoutParams(ui.dp(40),ui.dp(40)))
        sheet.addView(header,LinearLayout.LayoutParams(-1,ui.height(64)))
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = true
            clipToPadding = true
            setPadding(0,ui.dp(2),0,ui.dp(4))
            isFillViewport = false
        }
        scroll.addView(content)
        sheet.addView(scroll,LinearLayout.LayoutParams(-1,0,1f).apply { topMargin = ui.dp(10) })
        sheet.addView(ui.text("MENU fecha a central  ·  Voltar retorna",10f,ui.quiet),
            LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(12) })
    }

    fun caption(text: String) {
        content.addView(ui.text(text.uppercase(java.util.Locale("pt","BR")),10f,ui.quiet,true).apply {
            letterSpacing = .12f
        },LinearLayout.LayoutParams(-1,-2).apply {
            setMargins(ui.dp(2),ui.dp(17),0,ui.dp(10))
        })
    }

    fun paragraph(text: String): TextView {
        val label = ui.text(text,13f,ui.muted).apply { setLineSpacing(ui.dp(4).toFloat(),1f) }
        content.addView(label,
            LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = ui.dp(16) })
        return label
    }

    fun progress(): ProgressBar {
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ui.accent)
            indeterminateTintList = ColorStateList.valueOf(ui.accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(bar, LinearLayout.LayoutParams(-1, ui.dp(6)).apply { bottomMargin = ui.dp(18) })
        return bar
    }

    fun grid(items: List<PanelAction>) {
        for (group in items.chunked(2)) {
            val row = LinearLayout(context).apply { clipChildren = false }
            for ((index,item) in group.withIndex()) {
                val tile = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(14),ui.dp(12),ui.dp(14),ui.dp(12))
                    contentDescription = item.title + ". " + item.detail
                }
                tile.addView(ImageView(context).apply {
                    setImageDrawable(TvIcon(item.icon,if(item.selected) ui.accent else ui.ink))
                },LinearLayout.LayoutParams(ui.dp(22),ui.dp(22)))
                tile.addView(ui.text(item.title,13f,medium=true),
                    LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(9) })
                tile.addView(ui.text(item.detail,10.5f,if(item.selected) ui.accent else ui.muted).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                },LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(4) })
                ui.focus(tile,item.key,16)
                tile.setOnClickListener { item.run() }
                actions[item.key] = tile
                row.addView(tile,LinearLayout.LayoutParams(0,ui.height(99),1f).apply {
                    if(index == 0) rightMargin = ui.dp(10)
                })
            }
            if(group.size == 1) row.addView(View(context),LinearLayout.LayoutParams(0,1,1f))
            content.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = ui.dp(10) })
        }
    }

    fun row(item: PanelAction) {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10))
            contentDescription = item.title + ". " + item.detail
        }
        row.addView(ImageView(context).apply { setImageDrawable(TvIcon(item.icon,ui.muted)) },
            LinearLayout.LayoutParams(ui.dp(21),ui.dp(21)).apply { rightMargin = ui.dp(13) })
        val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(ui.text(item.title,12.5f,medium=true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if(item.detail.isNotEmpty()) labels.addView(ui.text(item.detail,10.5f,ui.muted).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(4) })
        row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(ImageView(context).apply {
            setImageDrawable(TvIcon(if(item.selected) TvIcon.Kind.CHECK else TvIcon.Kind.CHEVRON,
                if(item.selected) ui.accent else ui.quiet))
        },LinearLayout.LayoutParams(ui.dp(18),ui.dp(18)).apply { leftMargin = ui.dp(7) })
        ui.focus(row,item.key,12,true)
        row.setOnClickListener { item.run() }
        actions[item.key] = row
        content.addView(row,LinearLayout.LayoutParams(-1,ui.height(57)).apply {
            bottomMargin = ui.dp(3)
        })
    }

    fun storage(text: String, used: Float) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14),ui.dp(13),ui.dp(14),ui.dp(13))
            background = ui.surface(Color.argb(160,23,35,49),14)
        }
        box.addView(ui.text(text,10.5f,ui.muted))
        val track = FrameLayout(context).apply { background = ui.surface(Color.rgb(39,51,65),3) }
        track.addView(View(context).apply { background = ui.surface(ui.accent,3) },
            LayoutParams(0,-1))
        track.addOnLayoutChangeListener { v,l,_,r,_,_,_,_,_ ->
            val bar = track.getChildAt(0)
            val desired = ((r-l) * used.coerceIn(0f,1f)).toInt()
            if(bar.layoutParams.width != desired) bar.layoutParams = bar.layoutParams.apply { width = desired }
        }
        box.addView(track,LinearLayout.LayoutParams(-1,ui.dp(3)).apply { topMargin = ui.dp(10) })
        content.addView(box,LinearLayout.LayoutParams(-1,-2).apply { topMargin = ui.dp(4) })
    }

    fun focusAction(key: String? = null) {
        val target = actions[key] ?: actions.entries.firstOrNull { it.key != "panel:close" }?.value
            ?: actions["panel:close"]
        post { if (isAttachedToWindow) target?.requestFocus() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(event.action == KeyEvent.ACTION_DOWN) {
            val direction = when(event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                else -> 0
            }
            if(direction != 0) {
                val next = findFocus()?.focusSearch(direction)
                if(next == null || !contains(next)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun contains(view: View): Boolean {
        var current: android.view.ViewParent? = view.parent
        while(current != null) { if(current === this) return true; current = current.parent }
        return false
    }
}
