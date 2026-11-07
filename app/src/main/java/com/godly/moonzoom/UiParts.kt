package com.godly.moonzoom

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Bảng màu đen – trắng – cam (tạm thời thay Liquid Glass)
val Orange = Color(0xFFE8873A)
val OrangeDeep = Color(0xFFD2692B)
val OrangeSoft = Color(0xFFF4B986)
val Ink = Color(0xFF000000)
val Panel = Color(0xFF1C1C1E)
val PanelLine = Color(0xFF38383B)
val ZoomYellow = Orange

/** Nút/ô nền tối. Đang chọn: viền cam có quầng sáng nhẹ. (Tên giữ nguyên để khỏi sửa chỗ gọi.) */
fun Modifier.liquidGlass(
    corner: Dp = 999.dp,
    tint: Color = Panel,
    strength: Float = 1f,
    selected: Boolean = false
): Modifier = this.drawWithCache {
    val r = min(corner.toPx(), min(size.width, size.height) / 2f)
    val cr = CornerRadius(r, r)
    val sw = 1.2.dp.toPx()
    val gw = 5.dp.toPx()
    onDrawBehind {
        drawRoundRect(color = tint.copy(alpha = if (selected) 0.95f else 0.88f), cornerRadius = cr)
        if (selected) drawRoundRect(color = Orange.copy(alpha = 0.26f), cornerRadius = cr, style = Stroke(gw))
        drawRoundRect(color = if (selected) Orange else PanelLine, cornerRadius = cr, style = Stroke(if (selected) sw * 1.5f else sw))
    }
}

fun Modifier.pressScale(source: InteractionSource, pressedScale: Float = 0.9f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val s = animateFloatAsState(if (pressed) pressedScale else 1f, spring(dampingRatio = 0.55f, stiffness = 650f), label = "press")
    graphicsLayer {
        scaleX = s.value
        scaleY = s.value
    }
}

@Composable
fun GlassButton(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    corner: Dp = 999.dp,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val src = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(src)
            .liquidGlass(corner = corner, selected = selected)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun GlassLabel(text: String, selected: Boolean = false, fontSize: Int = 12) {
    Text(
        text,
        color = if (selected) Orange else Color.White,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
}

/** Nút tròn dùng ảnh icon riêng (AI / Watermark). Bật = viền cam + quầng sáng cam. */
@Composable
fun IconToggle(res: Int, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(size)
            .pressScale(src)
            .drawBehind {
                if (selected) {
                    drawCircle(Orange.copy(alpha = 0.30f), radius = this.size.minDimension / 2f + 2.dp.toPx(), style = Stroke(6.dp.toPx()))
                }
            }
            .border(if (selected) 2.dp else 1.dp, if (selected) Orange else PanelLine, CircleShape)
            .clip(CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
    ) {
        Image(painterResource(res), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

/** Nút chụp theo mẫu: lõi cam, khe đen, vòng ngoài trắng. Nhấn thì lõi co lại kiểu lò xo. */
@Composable
fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val disc = animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.5f, stiffness = 700f), label = "disc")
    val ring = animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f, stiffness = 700f), label = "ringS")
    Box(
        Modifier
            .size(84.dp)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val rs = ring.value
            drawCircle(Color.White, radius = (r - 3.5.dp.toPx()) * rs, style = Stroke(7.dp.toPx()))
            drawCircle(Color.Black, radius = (r - 9.dp.toPx()) * rs, style = Stroke(3.dp.toPx()))
            val dr = (r - 12.dp.toPx()) * disc.value
            drawCircle(
                Brush.verticalGradient(
                    listOf(if (enabled) Orange else Orange.copy(alpha = 0.5f), if (enabled) OrangeDeep else OrangeDeep.copy(alpha = 0.5f)),
                    startY = center.y - dr, endY = center.y + dr
                ),
                radius = dr
            )
        }
    }
}

/** Biểu tượng đổi camera (hai mũi tên vòng) vẽ bằng Canvas. */
@Composable
fun FlipIcon(size: Dp = 26.dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f * 0.72f
        val st = Stroke(2.3.dp.toPx(), cap = StrokeCap.Round)
        val tl = Offset(center.x - r, center.y - r)
        val sz = Size(r * 2, r * 2)
        fun head(deg: Float) {
            val a = deg * PI.toFloat() / 180f
            val end = Offset(center.x + r * cos(a), center.y + r * sin(a))
            val tx = -sin(a)
            val ty = cos(a)
            val len = 5.5.dp.toPx()
            for (sgn in listOf(-1f, 1f)) {
                val ang = 0.62f * sgn
                val bx = -tx * cos(ang) + ty * sin(ang)
                val by = -tx * sin(ang) - ty * cos(ang)
                drawLine(Color.White, end, Offset(end.x + bx * len, end.y + by * len), strokeWidth = 2.3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        drawArc(Color.White, 200f, 135f, false, tl, sz, style = st)
        head(335f)
        drawArc(Color.White, 20f, 135f, false, tl, sz, style = st)
        head(155f)
    }
}

/** Ngôi sao 4 cánh lõm kiểu Gemini. */
fun DrawScope.drawGeminiStar(c: Offset, r: Float, brush: Brush, alpha: Float) {
    if (alpha <= 0.01f || r <= 0.5f) return
    val p = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticBezierTo(c.x, c.y, c.x + r, c.y)
        quadraticBezierTo(c.x, c.y, c.x, c.y + r)
        quadraticBezierTo(c.x, c.y, c.x - r, c.y)
        quadraticBezierTo(c.x, c.y, c.x, c.y - r)
        close()
    }
    drawPath(p, brush, alpha = alpha.coerceIn(0f, 1f))
}

private val GeminiColors = listOf(Color(0xFF4285F4), Color(0xFF9B72CB), Color(0xFFD96570))

/**
 * Nút album hình tròn. Đây là nơi DUY NHẤT có hiệu ứng lấp lánh (kiểu Gemini): khi đang xử lý ảnh sẽ có vòng
 * 0–100% và các ngôi sao nhấp nháy. Chạm = mở album; nhấn giữ khi đang xử lý = hủy.
 */
@Composable
fun AlbumButton(thumb: ImageBitmap?, progress: Float?, onTap: () -> Unit, onLongPress: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val inf = rememberInfiniteTransition(label = "albumFx")
    val phase = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "aph")
    val p = animateFloatAsState(progress ?: 0f, tween(220), label = "aprog")
    Box(
        Modifier
            .size(62.dp)
            .pressScale(src)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { off ->
                        val press = PressInteraction.Press(off)
                        src.emit(press)
                        tryAwaitRelease()
                        src.emit(PressInteraction.Release(press))
                    },
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(54.dp)
                .liquidGlass(corner = 999.dp, selected = progress != null)
        ) {
            if (thumb != null) {
                Image(
                    thumb, contentDescription = "Album", contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            }
        }
        if (progress != null) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f - 2.dp.toPx()
                drawCircle(Color.Black.copy(alpha = 0.50f), radius = size.minDimension / 2f - 5.dp.toPx())
                val tl = Offset(center.x - r, center.y - r)
                val sz = Size(r * 2, r * 2)
                drawArc(Color.White.copy(alpha = 0.22f), -90f, 360f, false, tl, sz, style = Stroke(3.dp.toPx()))
                drawArc(Orange, -90f, 360f * p.value, false, tl, sz, style = Stroke(3.2.dp.toPx(), cap = StrokeCap.Round))
                val ph = phase.value
                val gb = Brush.linearGradient(GeminiColors, start = Offset(0f, 0f), end = Offset(size.width, size.height))
                // 3 ngôi sao nhấp nháy ở các góc, to nhỏ lệch pha nhau
                val spots = listOf(
                    Offset(size.width * 0.82f, size.height * 0.16f) to 8.5f,
                    Offset(size.width * 0.14f, size.height * 0.30f) to 5.5f,
                    Offset(size.width * 0.78f, size.height * 0.88f) to 4.5f
                )
                for ((k, sp) in spots.withIndex()) {
                    val t = (ph + k / 3f) % 1f
                    val a = sin(t * PI.toFloat()).let { it * it }
                    drawGeminiStar(sp.first, (sp.second * (0.35f + 0.65f * a)).dp.toPx(), gb, 0.25f + 0.75f * a)
                }
            }
            Text(
                "${(p.value * 100).toInt()}%",
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Thanh chỉnh sáng/tối dạng viên dọc: mặt trời ở trên, núm sáng ở giữa, kéo lên = sáng hơn. */
@Composable
fun ExposureControl(
    index: Float, lower: Int, upper: Int,
    onChange: (Float) -> Unit, onTouch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .size(44.dp, 168.dp)
            .liquidGlass(corner = 22.dp)
            .pointerInput(lower, upper) {
                detectVerticalDragGestures(onDragStart = { onTouch() }) { change, _ ->
                    change.consume()
                    onTouch()
                    val pad = 30.dp.toPx()
                    val f = ((change.position.y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                    onChange(upper - f * (upper - lower))
                }
            }
            .pointerInput(lower, upper) {
                detectTapGestures { off ->
                    onTouch()
                    val pad = 30.dp.toPx()
                    val f = ((off.y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                    onChange(upper - f * (upper - lower))
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = 30.dp.toPx()
            val cx = size.width / 2f
            val f = if (upper > lower) ((upper - index) / (upper - lower)).coerceIn(0f, 1f) else 0.5f
            val ty = pad + f * (size.height - 2 * pad)
            drawLine(Color.White.copy(alpha = 0.25f), Offset(cx, pad), Offset(cx, size.height - pad), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Orange, Offset(cx, size.height / 2f), Offset(cx, ty), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            val sy = 15.dp.toPx()
            drawCircle(Color.White, radius = 3.6.dp.toPx(), center = Offset(cx, sy))
            for (k in 0 until 8) {
                val a = k * (PI.toFloat() / 4f)
                drawLine(
                    Color.White,
                    Offset(cx + 6.dp.toPx() * cos(a), sy + 6.dp.toPx() * sin(a)),
                    Offset(cx + 8.6.dp.toPx() * cos(a), sy + 8.6.dp.toPx() * sin(a)),
                    strokeWidth = 1.3.dp.toPx(), cap = StrokeCap.Round
                )
            }
            drawCircle(Color.White.copy(alpha = 0.85f), radius = 4.dp.toPx(), center = Offset(cx, size.height - 15.dp.toPx()))
            drawCircle(Panel, radius = 3.4.dp.toPx(), center = Offset(cx + 1.6.dp.toPx(), size.height - 16.4.dp.toPx()))
            drawCircle(Orange.copy(alpha = 0.30f), radius = 15.dp.toPx(), center = Offset(cx, ty))
            drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(cx, ty))
            drawCircle(Orange, radius = 9.dp.toPx(), center = Offset(cx, ty), style = Stroke(2.dp.toPx()))
        }
    }
}
