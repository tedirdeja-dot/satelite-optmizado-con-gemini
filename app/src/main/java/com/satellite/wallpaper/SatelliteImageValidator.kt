package com.satellite.wallpaper

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Filtro conservador para impedir que la página de error blanca de WebView
 * se convierta en el fondo. Usa lectura por filas para minimizar consumo de CPU.
 */
internal object SatelliteImageValidator {
    fun isUsable(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width < 100 || bitmap.height < 100) return false

        val w = bitmap.width
        val h = bitmap.height
        val rowPixels = IntArray(w)
        var minLuminance = 255
        var maxLuminance = 0
        var nearWhite = 0
        var colorful = 0
        var samples = 0

        for (row in 1..24) {
            val y = h * row / 25
            bitmap.getPixels(rowPixels, 0, w, 0, y, w, 1)
            for (col in 1..24) {
                val pixel = rowPixels[w * col / 25]
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                val lum = (red * 3 + green * 6 + blue) / 10
                if (lum < minLuminance) minLuminance = lum
                if (lum > maxLuminance) maxLuminance = lum
                if (red >= 242 && green >= 242 && blue >= 242) nearWhite++
                if (maxOf(red, green, blue) - minOf(red, green, blue) > 25) colorful++
                samples++
            }
        }
        if (maxLuminance - minLuminance < 8) return false
        if (nearWhite * 100 >= samples * 88 && colorful * 100 < samples * 6) return false
        return true
    }
}
