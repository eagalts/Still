package com.eagalts.still.media

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * «Это информация, а не память?» — квитанции, чеки, счётчики, документы и экраны (монитор, слайды на паре),
 * снятые камерой: им место в «Потоке», как скриншотам, а не в ленте.
 * Решает маленькая сеть поверх того же вектора, что и для стопок (шесть сетей 1024 → 64 → 1, среднее их оценок),
 * — без второй модели и без второго прохода по фото. Порог подобран так, чтобы обычные снимки уходили по ошибке
 * примерно 4 раза на тысячу — и это в основном таблички, объявления и меню (проверено на открытом архиве событий). Веса — в приложении (`paperwork.bin`, половинная точность), читаются на время прохода.
 */
class Paperwork private constructor(
    private val n: Int, private val dim: Int, private val hidden: Int, val threshold: Float,
    /** Порог средней оценки стопки: среднее по многим кадрам надёжнее одного кадра. */
    val stackThreshold: Float,
    /** Отпечаток весов: сменились веса — прежние решения «бумага или нет» пересчитываются. */
    val signature: Long,
    private val w1: Array<FloatArray>, private val b1: Array<FloatArray>, private val w2: Array<FloatArray>, private val b2: FloatArray,
) {
    private val h = FloatArray(hidden)
    private val x = FloatArray(dim)

    /** Оценка кадра: больше [threshold] — бумага. Вектор — тот же int8, что у стопок. */
    fun score(v: ByteArray): Float {
        if (v.size != dim) return Float.NEGATIVE_INFINITY
        var nn = 0.0
        for (b in v) nn += b * b
        val k = (1.0 / Math.sqrt(nn.coerceAtLeast(1e-9))).toFloat()
        for (i in 0 until dim) x[i] = v[i] * k
        var sum = 0f
        for (m in 0 until n) {
            val w = w1[m]; val bb = b1[m]
            for (j in 0 until hidden) {
                var a = bb[j]; val off = j * dim
                for (i in 0 until dim) a += w[off + i] * x[i]
                h[j] = if (a > 0f) a else 0f
            }
            var o = b2[m]; val ww = w2[m]
            for (j in 0 until hidden) o += ww[j] * h[j]
            sum += o
        }
        return sum / n
    }

    fun isPaper(v: ByteArray): Boolean = score(v) > threshold

    companion object {
        const val ASSET = "paperwork.bin"

        fun read(input: InputStream): Paperwork? = runCatching {
            val raw = input.readBytes()
            val sig = java.util.zip.CRC32().apply { update(raw) }.value
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4); b.get(magic)
            if (String(magic) != "PPW2") return null
            val n = b.int; val dim = b.int; val hid = b.int; val thr = b.float; val sthr = b.float
            fun half(cnt: Int) = FloatArray(cnt) { halfToFloat(b.short) }
            val w1 = arrayOfNulls<FloatArray>(n); val b1 = arrayOfNulls<FloatArray>(n); val w2 = arrayOfNulls<FloatArray>(n); val b2 = FloatArray(n)
            for (m in 0 until n) { w1[m] = half(hid * dim); b1[m] = half(hid); w2[m] = half(hid); b2[m] = half(1)[0] }
            Paperwork(n, dim, hid, thr, sthr, sig, w1.requireNoNulls(), b1.requireNoNulls(), w2.requireNoNulls(), b2)
        }.getOrNull()

        /** IEEE 754 половинной точности → float (без android.util.Half: работает и в тестах на JVM). */
        internal fun halfToFloat(s: Short): Float {
            val h = s.toInt() and 0xFFFF
            val sign = (h ushr 15) and 1; val exp = (h ushr 10) and 0x1F; val man = h and 0x3FF
            val f = when {
                exp == 0 -> if (man == 0) 0f else (man / 1024f) * Math.pow(2.0, -14.0).toFloat()
                exp == 31 -> if (man == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> (1f + man / 1024f) * Math.pow(2.0, (exp - 15).toDouble()).toFloat()
            }
            return if (sign == 1) -f else f
        }

        /**
         * Что уходит в «Поток» как информация. Одиночный кадр — по своей оценке; стопка — по средней оценке своих кадров
         * (пара серых кадров тумана не утащат прогулку, а лекция с экранами уйдёт целиком, даже если отдельные кадры
         * чуть не дотянули). Оценка ещё не посчитана — кадр в среднем не участвует.
         */
        fun flowIds(entries: List<Entry>, frame: Float, stack: Float, score: (Long) -> Float?, strong: Float = STRONG): Set<Long> {
            val out = HashSet<Long>()
            for (e in entries) {
                if (!e.stacked) { val s = score(e.cover.id); if (s != null && s > frame) out += e.cover.id; continue }
                var sum = 0f; var known = 0
                for (m in e.members) { val s = score(m.id) ?: continue; sum += s; known++ }
                if (known * 2 >= e.members.size && known > 0 && sum / known > stack) e.members.forEach { out += it.id }
                // кадр, в котором распознаватель уверен очень сильно (фото экрана телефона среди снимков клавиатуры
                // в магазине), уходит и из стопки, которая остаётся
                else for (m in e.members) { val s = score(m.id); if (s != null && s > strong) out += m.id }
            }
            return out
        }

        /**
         * «Очень уверен»: выше — уходит один, даже из стопки обычных фото. На открытом архиве событий выше этого —
         * 3 кадра из 5665 (таблички с объявлениями); самый «бумажный» кадр прогулки в тумане — ниже.
         */
        const val STRONG = 4.5f
    }
}
