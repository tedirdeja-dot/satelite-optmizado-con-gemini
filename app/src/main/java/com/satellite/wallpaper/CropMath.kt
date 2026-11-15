package com.satellite.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object CropMath {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destRect = RectF()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFDF3F.toInt()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(5f, 1f, 1f, 0xCC000000.toInt())
    }
    private val dateFormatLock = Any()
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    fun draw(
        canvas: Canvas,
        bitmap: Bitmap,
        width: Int,
        height: Int,
        state: CropState,
        launcherOffset: Float = 0.5f
    ) {
        if (bitmap.isRecycled || width <= 0 || height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val scale = max(w / bitmap.width, h / bitmap.height) * state.zoom.coerceIn(1f, 4f)
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val maxX = max(0f, (dw - w) / 2f)
        val maxY = max(0f, (dh - h) / 2f)
        val cx0 = w / 2f
        val cx = (state.offsetX.coerceIn(-1f, 1f) * maxX + cx0 +
            (launcherOffset.coerceIn(0f, 1f) - 0.5f) * 2f * min(0.65f * maxX, w * 0.2f))
            .coerceIn(cx0 - maxX, cx0 + maxX)
        val cy = h / 2f + state.offsetY.coerceIn(-1f, 1f) * maxY
        val hw = dw / 2f
        val hh = dh / 2f
        destRect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        canvas.drawBitmap(bitmap, null, destRect, bitmapPaint)
    }

    fun drawUpdatedAt(canvas: Canvas, updatedAt: Long, width: Int, height: Int) {
        if (updatedAt <= 0 || width <= 0 || height <= 0) return
        val formatted = synchronized(dateFormatLock) { dateFormat.format(Date(updatedAt)) }
        val text = "Actualizado $formatted"
        val w = width.toFloat()
        textPaint.textSize = (0.042f * w).coerceIn(18f, 34f)
        canvas.drawText(text, 0.04f * w, height - (w * 0.1f).coerceAtLeast(18f), textPaint)
    }

    fun maxPan(width: Int, height: Int, bitmap: Bitmap, zoom: Float): Pair<Float, Float> {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return Pair(0f, 0f)
        val scale = max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * zoom
        return Pair(
            max(0f, (bitmap.width * scale - width) / 2f),
            max(0f, (bitmap.height * scale - height) / 2f)
        )
    }
}
