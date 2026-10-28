package com.metrolist.music.ui.vibe.scene

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

private const val SHORT_SIDE = 180
private const val FRAME_GAP_MS = 28L

/** Pixel-art scene for [spec], drawn at ~180px on the short side and upscaled without smoothing. */
@Composable
fun VibeScene(
    spec: SceneSpec,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    val short = min(layoutSize.width, layoutSize.height)
    val sw = if (short > 0) (layoutSize.width * SHORT_SIDE.toFloat() / short).roundToInt() else 0
    val sh = if (short > 0) (layoutSize.height * SHORT_SIDE.toFloat() / short).roundToInt() else 0
    val renderer = remember(spec, sw, sh) { if (sw > 0) SceneRenderer(spec, sw, sh) else null }
    val bitmap = remember(renderer) {
        renderer?.let { Bitmap.createBitmap(it.w, it.h, Bitmap.Config.ARGB_8888).apply { setPixels(it.pixels, 0, it.w, 0, 0, it.w, it.h) } }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val frame = remember { mutableIntStateOf(0) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val running = animate && lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    LaunchedEffect(renderer, running) {
        val r = renderer ?: return@LaunchedEffect
        val bmp = bitmap ?: return@LaunchedEffect
        fun publish() {
            r.render()
            bmp.setPixels(r.pixels, 0, r.w, 0, 0, r.w, r.h)
            frame.intValue++
        }
        publish()
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            // Sleeping between frames caps us at ~30 fps without waking on every vsync.
            delay(FRAME_GAP_MS)
            val now = withFrameNanos { it }
            r.time += min((now - last) / 1e9, 0.1)
            last = now
            publish()
        }
    }

    Canvas(modifier.clipToBounds().onSizeChanged { layoutSize = it }) {
        frame.intValue // redraw whenever a new frame was published
        val img = image ?: return@Canvas
        drawImage(
            img,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.None,
        )
    }
}
