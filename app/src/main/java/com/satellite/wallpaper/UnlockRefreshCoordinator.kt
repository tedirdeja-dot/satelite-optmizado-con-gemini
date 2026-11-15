package com.satellite.wallpaper

import android.app.KeyguardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Unifica los avisos de desbloqueo del servicio de fondo y del servicio persistente.
 * - Cancela tareas diferidas pendientes cuando se apaga la pantalla o se completa el desbloqueo.
 * - Verifica conexión a internet antes de arrancar WebView/Chromium (ahorro crítico de batería).
 */
internal object UnlockRefreshCoordinator {
    private const val PREFS = "satellite_unlock_events"
    private const val SCREEN_OFF_AT = "screen_off_at"
    private const val LAST_DISPATCH_AT = "last_dispatch_at"
    private const val DUPLICATE_MS = 2_500L
    private const val MAX_SCREEN_OFF_AGE_MS = 24L * 60 * 60 * 1000

    private val handler = Handler(Looper.getMainLooper())
    private var pendingScreenOnChecks: List<Runnable> = emptyList()

    private fun cancelPendingScreenOnChecks() {
        pendingScreenOnChecks.forEach { handler.removeCallbacks(it) }
        pendingScreenOnChecks = emptyList()
    }

    @Synchronized
    fun screenOff(context: Context) {
        cancelPendingScreenOnChecks()
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, 0).edit()
            .putLong(SCREEN_OFF_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Alternativa breve para teléfonos que omiten USER_PRESENT. Nunca descarga
     * mientras el dispositivo sigue bloqueado; se cancela al apagarse la pantalla o al desbloquearse.
     */
    @Synchronized
    fun screenOn(context: Context) {
        cancelPendingScreenOnChecks()
        val app = context.applicationContext
        val r1 = Runnable { wallpaperVisible(app) }
        val r2 = Runnable { wallpaperVisible(app) }
        pendingScreenOnChecks = listOf(r1, r2)
        handler.postDelayed(r1, 1_600L)
        handler.postDelayed(r2, 4_000L)
    }

    /** Evento real emitido por Android después de quitar el bloqueo. */
    @Synchronized
    fun userPresent(context: Context, origin: String) {
        cancelPendingScreenOnChecks()
        dispatch(context.applicationContext, origin, requireScreenOff = false)
    }

    /** Alternativa cuando el fondo vuelve a ser visible tras apagar la pantalla. */
    @Synchronized
    fun wallpaperVisible(context: Context) {
        dispatch(context.applicationContext, "fondo visible tras desbloqueo", requireScreenOff = true)
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return if (Build.VERSION.SDK_INT >= 23) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    private fun dispatch(app: Context, origin: String, requireScreenOff: Boolean) {
        if (!WallpaperRepository.hasSelection(app)) return
        val now = System.currentTimeMillis()
        val prefs = app.getSharedPreferences(PREFS, 0)
        val offAt = prefs.getLong(SCREEN_OFF_AT, 0L)
        val armed = offAt > 0L && now >= offAt && now - offAt < MAX_SCREEN_OFF_AGE_MS
        if (requireScreenOff && !armed) return

        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!pm.isInteractive || km.isKeyguardLocked) return

        val last = prefs.getLong(LAST_DISPATCH_AT, 0L)
        if (last > 0 && now >= last && now - last < DUPLICATE_MS) return

        cancelPendingScreenOnChecks()
        prefs.edit().putLong(LAST_DISPATCH_AT, now).remove(SCREEN_OFF_AT).apply()

        if (!isNetworkAvailable(app)) {
            WallpaperRepository.note(app, "desbloqueo", "omitido: sin conexión a internet ($origin)")
            return
        }

        WallpaperRepository.note(app, "desbloqueo", "detectado por $origin; comprobando intervalo")
        WallpaperRepository.refreshOnUnlock(app)
    }
}
