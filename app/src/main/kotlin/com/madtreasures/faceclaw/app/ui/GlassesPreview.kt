package com.madtreasures.faceclaw.app.ui

import androidx.core.graphics.createBitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.madtreasures.faceclaw.core.gfx.GrayBitmap
import com.madtreasures.faceclaw.core.ui.Gesture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Live mirror of the glasses display, drawn green on black like the lenses. The area doubles
 * as a touchpad: tap, double tap, long press and vertical swipes act like the ring.
 */
@Composable
fun GlassesPreview(frame: GrayBitmap?, displayOn: Boolean, onGesture: (Gesture) -> Unit, modifier: Modifier = Modifier) {
    val bitmap = remember { createBitmap(640, 480) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val pixels = remember { IntArray(640 * 480) }
    var version by remember { mutableIntStateOf(0) }
    val stepPx = with(LocalDensity.current) { 36.dp.toPx() }

    LaunchedEffect(frame, displayOn) {
        val f = frame ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val src = f.pixels
            for (i in pixels.indices) {
                val v = if (displayOn) src[i].toInt() and 0xFF else 0
                pixels[i] = (0xFF shl 24) or ((v * 60 / 255) shl 16) or (v shl 8) or (v * 90 / 255)
            }
            bitmap.setPixels(pixels, 0, 640, 0, 0, 640, 480)
        }
        version++
    }

    Box(
        modifier
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onGesture(Gesture.Tap) },
                    onDoubleTap = { onGesture(Gesture.DoubleTap) },
                    onLongPress = { onGesture(Gesture.LongPress) },
                )
            }
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(onDragStart = { acc = 0f }) { change, dy ->
                    change.consume()
                    acc += dy
                    if (abs(acc) >= stepPx) {
                        // dragging the content up moves forward, like scrolling a list
                        onGesture(if (acc < 0) Gesture.ScrollDown else Gesture.ScrollUp)
                        acc = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            version // redraw when a new frame arrived
            drawImage(image, dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
        }
        if (frame == null) Text("Starting…", color = Color.Gray)
        if (!displayOn) Text("Display off — tap to wake", color = Color(0xFF6F8F72), style = MaterialTheme.typography.bodySmall)
    }
}
