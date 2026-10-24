package com.b5solucoes.tcllight

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Small vector glyphs; no emoji fonts, bitmap assets or animation loop. */
internal class TvIcon(private val kind: Kind, color: Int) : Drawable() {
    enum class Kind { WIFI, CABLE, DEVICES, TV, HOME, REFRESH, CONTROLS, GRID, CLOSE,
        CHEVRON, PALETTE, LEAF, STORAGE, PIN, EYE, INFO, IMAGE, SUN, CLOUD, RAIN, LEFT, RIGHT, SIZE, CHECK,
        SEARCH, MIC, PLAY, PAUSE, CALENDAR, BELL }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.65f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        path.reset()
        fun line(x: Float, y: Float, x2: Float, y2: Float) = canvas.drawLine(x,y,x2,y2,paint)
        when (kind) {
            Kind.SEARCH -> { canvas.drawCircle(10f,10f,6f,paint);line(14.5f,14.5f,21f,21f) }
            Kind.MIC -> { canvas.drawRoundRect(9f,2f,15f,15f,3f,3f,paint);canvas.drawArc(5f,6f,19f,19f,0f,180f,false,paint);line(12f,19f,12f,23f);line(8f,23f,16f,23f) }
            Kind.PLAY -> { path.moveTo(7f,3f);path.lineTo(21f,12f);path.lineTo(7f,21f);path.close();canvas.drawPath(path,paint) }
            Kind.PAUSE -> { line(8f,4f,8f,20f);line(16f,4f,16f,20f) }
            Kind.CALENDAR -> { canvas.drawRoundRect(3f,5f,21f,22f,2f,2f,paint);line(3f,10f,21f,10f);line(8f,2f,8f,7f);line(16f,2f,16f,7f);line(8f,15f,10f,15f);line(14f,15f,16f,15f) }
            Kind.BELL -> { path.moveTo(4f,18f);path.lineTo(7f,14f);path.lineTo(7f,9f);path.cubicTo(7f,2f,17f,2f,17f,9f);path.lineTo(17f,14f);path.lineTo(20f,18f);path.close();canvas.drawPath(path,paint);canvas.drawArc(9f,18f,15f,23f,0f,180f,false,paint) }
            Kind.WIFI -> {
                canvas.drawArc(2f,5f,22f,23f,222f,96f,false,paint)
                canvas.drawArc(6f,10f,18f,22f,224f,92f,false,paint)
                canvas.drawCircle(12f,18f,.8f,paint)
            }
            Kind.CABLE -> {
                canvas.drawRoundRect(5f,7f,19f,18f,2f,2f,paint)
                line(9f,3f,9f,7f); line(15f,3f,15f,7f); line(12f,18f,12f,22f)
                line(9f,12f,9f,14f); line(12f,12f,12f,14f); line(15f,12f,15f,14f)
            }
            Kind.DEVICES -> {
                canvas.drawRoundRect(7f,2f,17f,22f,3f,3f,paint)
                canvas.drawCircle(12f,9f,2.6f,paint)
                line(10f,16f,14f,16f); line(10f,19f,14f,19f)
            }
            Kind.TV -> {
                canvas.drawRoundRect(2f,4f,22f,18f,2f,2f,paint)
                line(8f,22f,16f,22f); line(12f,18f,12f,22f)
            }
            Kind.HOME -> {
                path.moveTo(3f,11f); path.lineTo(12f,3f); path.lineTo(21f,11f)
                path.moveTo(6f,9f); path.lineTo(6f,21f); path.lineTo(10f,21f)
                path.lineTo(10f,15f); path.lineTo(14f,15f); path.lineTo(14f,21f)
                path.lineTo(18f,21f); path.lineTo(18f,9f); canvas.drawPath(path,paint)
            }
            Kind.REFRESH -> {
                canvas.drawArc(4f,4f,20f,20f,35f,290f,false,paint)
                line(20f,3f,20f,9f); line(14f,9f,20f,9f)
            }
            Kind.CONTROLS -> {
                line(4f,7f,20f,7f); line(4f,17f,20f,17f)
                canvas.drawCircle(9f,7f,2.5f,paint); canvas.drawCircle(15f,17f,2.5f,paint)
            }
            Kind.GRID -> for (x in listOf(4f,14f)) for (y in listOf(4f,14f))
                canvas.drawRoundRect(x,y,x+6f,y+6f,1.5f,1.5f,paint)
            Kind.CLOSE -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
            Kind.CHEVRON -> { line(10f,6f,16f,12f); line(16f,12f,10f,18f) }
            Kind.LEFT -> { line(20f,12f,4f,12f); line(4f,12f,10f,6f); line(4f,12f,10f,18f) }
            Kind.RIGHT -> { line(4f,12f,20f,12f); line(20f,12f,14f,6f); line(20f,12f,14f,18f) }
            Kind.CLOUD, Kind.RAIN -> {
                path.moveTo(7f,17f); path.cubicTo(0f,17f,1f,8f,7f,9f)
                path.cubicTo(8f,1f,19f,3f,19f,10f)
                path.cubicTo(25f,10f,24f,17f,18f,17f); path.close()
                canvas.drawPath(path,paint)
                if(kind == Kind.RAIN) { line(8f,20f,7f,22f); line(13f,20f,12f,22f); line(18f,20f,17f,22f) }
            }
            Kind.PALETTE -> {
                canvas.drawCircle(12f,12f,9f,paint)
                canvas.drawCircle(8f,9f,1f,paint); canvas.drawCircle(13f,6f,1f,paint)
                canvas.drawCircle(17f,10f,1f,paint)
                path.moveTo(4f,16f); path.cubicTo(8f,13f,11f,14f,11f,17f)
                path.cubicTo(11f,20f,16f,20f,19f,17f); canvas.drawPath(path,paint)
            }
            Kind.LEAF -> {
                path.moveTo(5f,18f); path.cubicTo(0f,9f,10f,3f,21f,3f)
                path.cubicTo(21f,15f,16f,24f,5f,18f); canvas.drawPath(path,paint)
                line(4f,21f,15f,10f)
            }
            Kind.STORAGE -> {
                canvas.drawRoundRect(3f,4f,21f,20f,3f,3f,paint)
                line(3f,14f,21f,14f); canvas.drawCircle(17f,17f,.5f,paint)
            }
            Kind.PIN -> {
                path.moveTo(8f,3f); path.lineTo(16f,3f); path.lineTo(15f,10f)
                path.lineTo(19f,14f); path.lineTo(5f,14f); path.lineTo(9f,10f)
                path.close(); canvas.drawPath(path,paint); line(12f,14f,12f,22f)
            }
            Kind.EYE -> {
                path.moveTo(2f,12f); path.cubicTo(7f,3f,17f,3f,22f,12f)
                path.cubicTo(17f,21f,7f,21f,2f,12f); canvas.drawPath(path,paint)
                canvas.drawCircle(12f,12f,3f,paint)
            }
            Kind.INFO -> {
                canvas.drawCircle(12f,12f,9f,paint)
                line(12f,11f,12f,17f); canvas.drawCircle(12f,7f,.5f,paint)
            }
            Kind.IMAGE -> {
                canvas.drawRoundRect(3f,4f,21f,20f,2f,2f,paint)
                path.moveTo(4f,18f); path.lineTo(10f,11f); path.lineTo(14f,15f)
                path.lineTo(17f,12f); path.lineTo(21f,17f); canvas.drawPath(path,paint)
                canvas.drawCircle(16f,8f,1f,paint)
            }
            Kind.SUN -> {
                canvas.drawCircle(12f,12f,4f,paint)
                line(12f,2f,12f,4f); line(12f,20f,12f,22f)
                line(2f,12f,4f,12f); line(20f,12f,22f,12f)
                line(5f,5f,6.5f,6.5f); line(17.5f,17.5f,19f,19f)
                line(5f,19f,6.5f,17.5f); line(17.5f,6.5f,19f,5f)
            }
            Kind.SIZE -> {
                canvas.drawRoundRect(3f,8f,11f,20f,1f,1f,paint)
                canvas.drawRoundRect(15f,3f,21f,20f,1f,1f,paint)
            }
            Kind.CHECK -> { line(5f,12f,10f,17f); line(10f,17f,20f,6f) }
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
