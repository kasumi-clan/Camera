package com.godly.moonzoom

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

val ARC_HEIGHT: Dp = 132.dp

fun formatZoom(z: Float): String = when {
    z >= 99.95f -> "100x"
    abs(z - z.roundToInt()) < 0.05f -> "${z.roundToInt()}x"
    z < 10f -> String.format(Locale.US, "%.1fx", z)
    else -> String.format(Locale.US, "%.0fx", z)
}

/** Trạng thái hiện/ẩn thước zoom. Chỉ `expanded` là state; thời điểm chạm lưu thường để không recomposition. */
class ZoomUi {
    var expanded by mutableStateOf(false)
    var lastTouch: Long = 0L

    fun touch() {
        lastTouch = SystemClock.elapsedRealtime()
        if (!expanded) expanded = true
    }
}

private const val SPAN_DEG = 260f
private val MAJORS = listOf(1f, 2f, 3f, 5f, 10f, 20f, 30f, 50f, 100f)

private class Tick(val frac: Float, val value: Float, val level: Int)

private fun buildTicks(): List<Tick> {
    val logMax = ln(SmoothZoomController.MAX)
    val out = ArrayList<Tick>()
    fun add(v: Float) {
        val major = MAJORS.any { abs(it - v) < 0.001f }
        val medium = !major && abs(v * 2f - (v * 2f).roundToInt()) < 0.001f
        val exact = MAJORS.firstOrNull { abs(it - v) < 0.001f } ?: v
        out.add(Tick(ln(exact) / logMax, exact, if (major) 2 else if (medium) 1 else 0))
    }
    var v = 1f
    while (v < 3f - 0.001f) { add(v); v += 0.1f }
    v = 3f
    while (v < 10f - 0.001f) { add(v); v += 0.5f }
    v = 10f
    while (v < 20f - 0.001f) { add(v); v += 1f }
    v = 20f
    while (v < 50f - 0.001f) { add(v); v += 2f }
    v = 50f
    while (v < 100f - 0.001f) { add(v); v += 5f }
    add(100f)
    return out
}

private fun nearestPreset(z: Float): Int =
    SmoothZoomController.PRESETS.indexOfFirst { abs(ln(z / it)) < 0.02f }

/**
 * Thước zoom dạng cung (theo ảnh mẫu): dải xám có vạch trắng chạy từ trái tới giá trị hiện tại, vạch cam ở đỉnh
 * và số cam bên dưới. CHỈ hiện khi đang zoom (kéo/chụm/chạm số), tự mờ đi khi dừng. Kéo ngang để chỉnh.
 */
@Composable
fun ZoomArc(controller: SmoothZoomController, ui: ZoomUi, modifier: Modifier = Modifier) {
    val vis = animateFloatAsState(if (ui.expanded) 1f else 0f, tween(200), label = "arcVis")
    if (!ui.expanded && vis.value < 0.01f) return

    val textMeasurer = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    val ticks = remember { buildTicks() }
    val logMax = remember { ln(SmoothZoomController.MAX) }
    val labelStyle = remember { TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Normal) }
    val valueStyle = remember { TextStyle(color = Orange, fontSize = 20.sp, fontWeight = FontWeight.Medium) }
    val labels = remember(textMeasurer) {
        MAJORS.associateWith { textMeasurer.measure(formatZoom(it).removeSuffix("x"), labelStyle) }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { nearestPreset(controller.value) }
            .distinctUntilChanged()
            .collect { if (it >= 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(ARC_HEIGHT)
            .clipToBounds()
            .pointerInput(Unit) {
                val rr = size.width * 0.60f
                val pxPerFull = rr * (SPAN_DEG * PI / 180.0).toFloat()
                detectHorizontalDragGestures(onDragStart = { ui.touch() }) { change, dx ->
                    change.consume()
                    val f = (ln(controller.target) / logMax - dx / pxPerFull).coerceIn(0f, 1f)
                    controller.setTarget(if (f >= 0.9999f) SmoothZoomController.MAX else exp(f * logMax))
                    ui.touch()
                }
            }
    ) {
        val a = vis.value
        val w = size.width
        val r = w * 0.60f
        val topY = 22.dp.toPx()
        val cx = w / 2f
        val cy = topY + r
        val z = controller.value
        val frac = ln(z.coerceIn(1f, SmoothZoomController.MAX)) / logMax
        val limit = (asin(min(1f, (w / 2f) / r)) * 180f / PI.toFloat()) - 2f

        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f * a)), startY = 0f, endY = size.height)
        )
        drawCircle(Color.White.copy(alpha = 0.16f * a), radius = r, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))

        // dải xám: từ vị trí 1x tới giá trị hiện tại (đỉnh)
        val band = 14.dp.toPx()
        val bandR = r - band / 2f - 1.dp.toPx()
        val startDeg = maxOf(-limit, -frac * SPAN_DEG)
        if (startDeg < -0.1f) {
            drawArc(
                Color.White.copy(alpha = 0.22f * a), -90f + startDeg, -startDeg, false,
                Offset(cx - bandR, cy - bandR), Size(bandR * 2, bandR * 2),
                style = Stroke(band)
            )
        }
        val rim = r - 2.dp.toPx()
        for (t in ticks) {
            val theta = (t.frac - frac) * SPAN_DEG
            if (theta > 0.2f || theta < -limit) continue
            val rad = (theta * PI / 180.0).toFloat()
            val s = sin(rad)
            val c = cos(rad)
            val len = (if (t.level == 2) 12.dp else if (t.level == 1) 9.dp else 6.dp).toPx()
            val fade = (1f - (abs(theta) / limit).pow(2.2f)) * a
            drawLine(
                Color.White.copy(alpha = fade * (if (t.level == 0) 0.7f else 1f)),
                Offset(cx + (rim - 1.dp.toPx()) * s, cy - (rim - 1.dp.toPx()) * c),
                Offset(cx + (rim - 1.dp.toPx() - len) * s, cy - (rim - 1.dp.toPx() - len) * c),
                strokeWidth = (if (t.level == 2) 1.6.dp else 1.1.dp).toPx(),
                cap = StrokeCap.Butt
            )
            if (t.level == 2 && t.value > 1.5f) {
                val lab = labels[t.value]
                if (lab != null) {
                    val lr = rim - band - 12.dp.toPx()
                    val p = Offset(cx + lr * s, cy - lr * c)
                    rotate(theta, pivot = p) {
                        drawText(
                            textLayoutResult = lab,
                            topLeft = Offset(p.x - lab.size.width / 2f, p.y - lab.size.height / 2f),
                            alpha = fade
                        )
                    }
                }
            }
        }
        // vạch cam ở đỉnh + số cam
        drawLine(
            Orange.copy(alpha = a), Offset(cx, topY - 3.dp.toPx()), Offset(cx, topY + band + 1.dp.toPx()),
            strokeWidth = 3.dp.toPx(), cap = StrokeCap.Butt
        )
        val vt = textMeasurer.measure(formatZoom(z).replace("x", " x"), valueStyle)
        drawText(vt, topLeft = Offset(cx - vt.size.width / 2f, topY + band + 6.dp.toPx()), alpha = a)
    }
}

/** Hàng số zoom dạng chữ trơn như ảnh mẫu; số gần nhất sáng cam. Kéo ngang trên hàng này để zoom (thước sẽ hiện). */
@Composable
fun ZoomPresets(controller: SmoothZoomController, ui: ZoomUi, modifier: Modifier = Modifier) {
    val z = controller.value
    val items = ArrayList<Pair<Float, Boolean>>()
    for (p in listOf(1f, 2f, 5f, 10f)) items.add(p to false)
    val near = items.any { abs(z - it.first) / it.first < 0.06f }
    if (!near) {
        items.add(z to true)
        items.sortBy { it.first }
    }
    val logMax = ln(SmoothZoomController.MAX)
    Row(
        modifier
            .pointerInput(Unit) {
                val pxPerFull = size.width.coerceAtLeast(300) * 2.4f
                detectHorizontalDragGestures(onDragStart = { ui.touch() }) { change, dx ->
                    change.consume()
                    ui.touch()
                    val f = (ln(controller.target) / logMax - dx / pxPerFull).coerceIn(0f, 1f)
                    controller.setTarget(if (f >= 0.9999f) SmoothZoomController.MAX else exp(f * logMax))
                }
            },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for ((v, dynamic) in items) {
            val selected = dynamic || abs(z - v) / v < 0.06f
            val src = remember { MutableInteractionSource() }
            Text(
                if (dynamic) formatZoom(v) else formatZoom(v).removeSuffix("x"),
                color = if (selected) Orange else Color.White,
                fontSize = if (selected) 17.sp else 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(interactionSource = src, indication = null) {
                        if (!dynamic) controller.animateTo(v, 280)
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun ZoomAutoCollapse(ui: ZoomUi) {
    LaunchedEffect(ui.expanded) {
        while (ui.expanded) {
            delay(250)
            if (SystemClock.elapsedRealtime() - ui.lastTouch > 2000) ui.expanded = false
        }
    }
}
