package com.b5solucoes.tcllight

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/** Native 16:9 cover cards shared by the multimedia gallery and search results. */
internal object TvContentCards {
    fun create(ui: TvStyle,title: String,detail: String,subtitle: String,icon: TvIcon.Kind,coverUrl: String,art: MediaArt): LinearLayout {
        val context=ui.context
        val card=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL;setPadding(ui.dp(7),ui.dp(7),ui.dp(7),ui.dp(9))
            contentDescription=listOf(title,detail,subtitle).filter { it.isNotBlank() }.joinToString(". ")
        }
        val cover=object:FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                val width=View.MeasureSpec.getSize(widthMeasureSpec)
                super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(width*9/16,View.MeasureSpec.EXACTLY))
            }
        }.apply { background=ui.cardSurface(10);clipToOutline=true;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        val placeholder=ImageView(context).apply {
            setImageDrawable(TvIcon(icon,ui.accent));setPadding(ui.dp(34),ui.dp(24),ui.dp(34),ui.dp(24));scaleType=ImageView.ScaleType.FIT_CENTER
        }
        cover.addView(placeholder,FrameLayout.LayoutParams(-1,-1))
        val image=ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
        cover.addView(image,FrameLayout.LayoutParams(-1,-1));art.bind(image,placeholder,coverUrl)
        cover.addView(View(context).apply {
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,ui.alpha(ui.base,95)))
        },FrameLayout.LayoutParams(-1,ui.dp(40),Gravity.BOTTOM))
        card.addView(cover,LinearLayout.LayoutParams(-1,-2))
        card.addView(ui.text(title,13f,medium=true).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(-1,ui.height(33)).apply { topMargin=ui.dp(8) })
        card.addView(ui.text(detail,10.5f,ui.accent).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END },LinearLayout.LayoutParams(-1,ui.height(15)))
        if(subtitle.isNotBlank()) card.addView(ui.text(subtitle,10f,ui.muted).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(-1,ui.height(28)).apply { topMargin=ui.dp(4) })
        return card
    }
}
