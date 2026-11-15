package com.satellite.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.util.concurrent.Executors

class SatelliteWallpaperService : WallpaperService() {

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> UnlockRefreshCoordinator.screenOff(applicationContext)
                Intent.ACTION_SCREEN_ON -> UnlockRefreshCoordinator.screenOn(applicationContext)
                Intent.ACTION_USER_PRESENT -> UnlockRefreshCoordinator.userPresent(applicationContext, "fondo animado")
            }
        }
    }
    private var unlockReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(unlockReceiver, filter)
            }
            unlockReceiverRegistered = true
            WallpaperRepository.note(this, "desbloqueo", "receptor del fondo registrado")
        } catch (t: Throwable) {
            WallpaperRepository.note(this, "desbloqueo", "no se pudo registrar receptor del fondo: ${t.javaClass.simpleName}")
        }
    }

    override fun onDestroy() {
        if (unlockReceiverRegistered) {
            runCatching { unregisterReceiver(unlockReceiver) }
            unlockReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine = SatelliteEngine()

    private inner class SatelliteEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val imageLoader = Executors.newSingleThreadExecutor()
        private var destroyed = false
        private var loadVersion = 0
        private var bitmap: Bitmap? = null
        private var visible = false
        private var launcherOffset = 0.5f
        private var cacheReceiverRegistered = false

        private val visibilityCheckRunnable = Runnable {
            if (!destroyed && visible) {
                UnlockRefreshCoordinator.wallpaperVisible(this@SatelliteWallpaperService)
            }
        }

        private val cacheReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_CACHE_UPDATED) loadAndDraw()
            }
        }

        private fun register(receiver: BroadcastReceiver, filter: IntentFilter): Boolean {
            return runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(receiver, filter)
                }
                true
            }.getOrDefault(false)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(USE_LAUNCHER_PARALLAX)
            cacheReceiverRegistered = register(cacheReceiver, IntentFilter(ACTION_CACHE_UPDATED))
            UnlockService.start(this@SatelliteWallpaperService)
            loadAndDraw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.removeCallbacks(visibilityCheckRunnable)
            if (visible) {
                if (bitmap == null || bitmap?.isRecycled == true) {
                    loadAndDraw()
                } else {
                    draw()
                }
                handler.postDelayed(visibilityCheckRunnable, 1_200L)
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            if (!USE_LAUNCHER_PARALLAX) return
            val nextOffset = xOffset.coerceIn(0f, 1f)
            if (nextOffset != launcherOffset) {
                launcherOffset = nextOffset
                draw()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(visibilityCheckRunnable)
        }

        override fun onDestroy() {
            destroyed = true
            loadVersion++
            handler.removeCallbacksAndMessages(null)
            imageLoader.shutdownNow()
            if (cacheReceiverRegistered) {
                runCatching { unregisterReceiver(cacheReceiver) }
                cacheReceiverRegistered = false
            }
            bitmap?.recycle()
            bitmap = null
            super.onDestroy()
        }

        private fun loadAndDraw() {
            if (destroyed) return
            val version = ++loadVersion
            runCatching {
                imageLoader.execute {
                    val loaded = WallpaperRepository.loadCached(this@SatelliteWallpaperService)
                    handler.post {
                        if (destroyed || version != loadVersion) {
                            loaded?.recycle()
                        } else if (loaded != null) {
                            val previous = bitmap
                            bitmap = loaded
                            draw()
                            if (previous != null && previous !== loaded && !previous.isRecycled) {
                                previous.recycle()
                            }
                        }
                    }
                }
            }
        }

        private fun draw() {
            if (destroyed) return
            val holder = surfaceHolder ?: return
            if (!(visible || holder.surface?.isValid == true)) return
            val canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: return
            try {
                canvas.drawColor(0xFF10212B.toInt())
                bitmap?.takeIf { !it.isRecycled }?.let { bmp ->
                    val ctx = this@SatelliteWallpaperService
                    CropMath.draw(
                        canvas, bmp, canvas.width, canvas.height,
                        WallpaperRepository.loadState(ctx), launcherOffset
                    )
                    CropMath.drawUpdatedAt(
                        canvas, WallpaperRepository.updatedAt(ctx), canvas.width, canvas.height
                    )
                }
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }
    }

    companion object {
        const val ACTION_CACHE_UPDATED = "com.satellite.wallpaper.CACHE_UPDATED"
        const val USE_LAUNCHER_PARALLAX = false
    }
}
