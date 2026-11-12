package com.skynav.presentation

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.skynav.data.session.NavigationHub
import java.util.concurrent.Executors

/** One rear-camera frame every few seconds, scaled down before it leaves the camera thread. */
@Composable
fun StreetCamera(enabled: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(enabled, lifecycleOwner) {
        if (!enabled) return@DisposableEffect onDispose { }
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, FrameGrabber { NavigationHub.offerCameraFrame(it) })
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { if (future.isDone) future.get().unbindAll() }
            executor.shutdown()
        }
    }
}

private class FrameGrabber(private val onFrame: (Bitmap) -> Unit) : ImageAnalysis.Analyzer {
    private var lastMs = 0L

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMs < INTERVAL_MS) {
            image.close()
            return
        }
        lastMs = now
        val bitmap = runCatching { image.toBitmap() }.getOrNull()
        image.close()
        if (bitmap == null) return
        val scaled = Bitmap.createScaledBitmap(bitmap, FRAME_PX, FRAME_PX, true)
        if (scaled !== bitmap) bitmap.recycle()
        onFrame(scaled)
    }

    companion object {
        const val INTERVAL_MS = 4_000L
        const val FRAME_PX = 224
    }
}
