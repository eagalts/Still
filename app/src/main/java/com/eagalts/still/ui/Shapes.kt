package com.eagalts.still.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Шкала скруглений (из Plume): вместо десятка значений от 5 до 36 dp — пять ступеней.
 * xs — плитки ленты, s — мелочь, m — кнопки,
 * l — крупные блоки, xl — шторки.
 */
object Radius {
    val xs = 8.dp
    val s = 12.dp
    val m = 20.dp
    val l = 26.dp
    val xl = 36.dp
}

/** Сглаживание углов (как «corner smoothing» в Figma): 0 — обычная дуга, 0.6 — как у иконок iOS. */
const val Smoothing = 0.6f

/**
 * Суперэллипс: угол не начинается резко дугой, а плавно «натекает» из прямой — кривизна растёт
 * постепенно. На Android < 13 обрезка по произвольному контуру у RenderNode не поддерживается
 * (Compose обрезал бы холст программно) — там обычное скругление.
 */
@Immutable
class SquircleShape(
    private val topStart: Dp,
    private val topEnd: Dp = topStart,
    private val bottomEnd: Dp = topStart,
    private val bottomStart: Dp = topStart,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val ltr = layoutDirection == LayoutDirection.Ltr
        with(density) {
            // пружины проскакивают ноль — отрицательный радиус не пускаем
            val tl = (if (ltr) topStart else topEnd).toPx().coerceAtLeast(0f)
            val tr = (if (ltr) topEnd else topStart).toPx().coerceAtLeast(0f)
            val br = (if (ltr) bottomEnd else bottomStart).toPx().coerceAtLeast(0f)
            val bl = (if (ltr) bottomStart else bottomEnd).toPx().coerceAtLeast(0f)
            if (!SquircleSupported || (tl == 0f && tr == 0f && br == 0f && bl == 0f)) {
                return Outline.Rounded(
                    RoundRect(0f, 0f, size.width, size.height, CornerRadius(tl), CornerRadius(tr), CornerRadius(br), CornerRadius(bl))
                )
            }
            return Outline.Generic(Path().apply { addSquircle(0f, 0f, size.width, size.height, tl, tr, br, bl) })
        }
    }

    override fun equals(other: Any?) = other is SquircleShape && other.topStart == topStart &&
        other.topEnd == topEnd && other.bottomEnd == bottomEnd && other.bottomStart == bottomStart
    override fun hashCode() = ((topStart.hashCode() * 31 + topEnd.hashCode()) * 31 + bottomEnd.hashCode()) * 31 + bottomStart.hashCode()
}

val SquircleSupported = android.os.Build.VERSION.SDK_INT >= 33

/** Сквиркл с радиусом в пикселях — для контуров, которые считаются на каждом кадре. */
fun squircleOutline(left: Float, top: Float, right: Float, bottom: Float, r: Float): Outline {
    val rr = r.coerceAtLeast(0f)
    return if (!SquircleSupported || rr == 0f) Outline.Rounded(RoundRect(left, top, right, bottom, CornerRadius(rr)))
    else Outline.Generic(Path().apply { addSquircle(left, top, right, bottom, rr, rr, rr, rr) })
}

/**
 * Контур со сглаженными углами — по алгоритму Figma: прямая → кубическая кривая →
 * короткая дуга → кривая → прямая. Углы обходятся по часовой стрелке.
 */
fun Path.addSquircle(
    left: Float, top: Float, right: Float, bottom: Float,
    rTL: Float, rTR: Float, rBR: Float, rBL: Float,
    smoothing: Float = Smoothing,
) {
    val w = right - left; val h = bottom - top
    if (w <= 0f || h <= 0f) return
    val half = minOf(w, h) / 2f
    // угол: вершина, направление «входа» (u) и «выхода» (v)
    val corners = arrayOf(
        floatArrayOf(right, top, 1f, 0f, 0f, 1f, rTR),
        floatArrayOf(right, bottom, 0f, 1f, -1f, 0f, rBR),
        floatArrayOf(left, bottom, -1f, 0f, 0f, -1f, rBL),
        floatArrayOf(left, top, 0f, -1f, 1f, 0f, rTL),
    )
    corners.forEachIndexed { i, c ->
        val vx = c[0]; val vy = c[1]; val ux = c[2]; val uy = c[3]; val wx = c[4]; val wy = c[5]
        val r = c[6].coerceIn(0f, half)
        // сглаживанию нужно место вдоль стороны: p = (1 + s)·r не больше половины стороны
        val s = if (r > 0f) smoothing.coerceAtMost(half / r - 1f).coerceAtLeast(0f) else 0f
        val p = (1f + s) * r
        val sx = vx - p * ux; val sy = vy - p * uy
        fun px(a: Float, b: Float) = sx + a * ux + b * wx
        fun py(a: Float, b: Float) = sy + a * uy + b * wy
        if (i == 0) moveTo(sx, sy) else lineTo(sx, sy)
        if (r <= 0f) { lineTo(vx, vy); return@forEachIndexed }
        val arcDeg = 90f * (1f - s)
        val arcLen = sin(rad(arcDeg / 2f)) * r * sqrt(2f)
        val alpha = (90f - arcDeg) / 2f
        val p3p4 = r * tan(rad(alpha / 2f))
        val beta = 45f * s
        val cc = p3p4 * cos(rad(beta))
        val d = cc * tan(rad(beta))
        val b = ((p - arcLen - cc - d) / 3f).coerceAtLeast(0f)
        val a = 2f * b
        // кривая из прямой в дугу
        val e1a = a + b + cc; val e1b = d
        cubicTo(px(a, 0f), py(a, 0f), px(a + b, 0f), py(a + b, 0f), px(e1a, e1b), py(e1a, e1b))
        // дуга — кубической кривой (дуга ≤ 90°, ошибка незаметна)
        val e2a = e1a + arcLen; val e2b = e1b + arcLen
        val k = 4f / 3f * tan(rad(arcDeg) / 4f) * r
        // касательные дуги — из угла, а не из отрезков cc/d: без сглаживания (s = 0) они нулевые,
        // и дуга вырождалась в прямую
        val ta = cos(rad(alpha)); val tb = sin(rad(alpha))
        val c1a = e1a + k * ta; val c1b = e1b + k * tb
        val c2a = e2a - k * tb; val c2b = e2b - k * ta
        cubicTo(px(c1a, c1b), py(c1a, c1b), px(c2a, c2b), py(c2a, c2b), px(e2a, e2b), py(e2a, e2b))
        // кривая из дуги в следующую сторону
        cubicTo(
            px(e2a + d, e2b + cc), py(e2a + d, e2b + cc),
            px(e2a + d, e2b + b + cc), py(e2a + d, e2b + b + cc),
            px(e2a + d, e2b + a + b + cc), py(e2a + d, e2b + a + b + cc),
        )
    }
    close()
}

private fun rad(deg: Float) = (deg * PI / 180.0).toFloat()
