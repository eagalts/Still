package com.eagalts.still.media

import android.graphics.Bitmap

/**
 * Дешёвые отпечатки кадра — из миниатюры, которая и так уже декодирована для ленты (лишнего декодирования нет).
 * Считается на потоке загрузки миниатюр, не на главном.
 */
object Pixels {
    /** dHash, 64 бита: яркость 9×8, бит — «правый пиксель ярче левого». 0 зарезервирован под «не посчитан». */
    fun dHash(src: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(src, 9, 8, true)
        val px = IntArray(72)
        s.getPixels(px, 0, 9, 0, 0, 9, 8)
        if (s !== src) s.recycle()
        var h = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (luma(px[y * 9 + x + 1]) > luma(px[y * 9 + x])) h = h or (1L shl bit)
            bit++
        }
        return if (h == 0L) 1L else h
    }

    /**
     * Цвет, который запоминается: не среднее (оно всегда грязно-бурое), а самый весомый оттенок среди
     * насыщенных пикселей 16×16; насыщенных мало — серый по средней яркости (белое, чёрное, серое).
     */
    fun memoryColor(src: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(src, 16, 16, true)
        val px = IntArray(256)
        s.getPixels(px, 0, 16, 0, 0, 16, 16)
        if (s !== src) s.recycle()
        val weight = FloatArray(Hue.entries.size)
        val sum = Array(Hue.entries.size) { FloatArray(3) }
        val hsv = FloatArray(3)
        var vSum = 0f
        for (c in px) {
            android.graphics.Color.colorToHSV(c, hsv)
            vSum += hsv[2]
            if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue
            val hue = Hue.of(c).ordinal
            val w = hsv[1] * hsv[2]
            weight[hue] += w
            sum[hue][0] += android.graphics.Color.red(c) * w
            sum[hue][1] += android.graphics.Color.green(c) * w
            sum[hue][2] += android.graphics.Color.blue(c) * w
        }
        val best = weight.indices.maxBy { weight[it] }
        // насыщенного меньше ~12% кадра — кадр «бесцветный»
        if (weight[best] < 256 * 0.12f * 0.5f) {
            val v = (vSum / 256f * 255f).toInt().coerceIn(0, 255)
            return android.graphics.Color.rgb(v, v, v)
        }
        val w = weight[best]
        return android.graphics.Color.rgb((sum[best][0] / w).toInt(), (sum[best][1] / w).toInt(), (sum[best][2] / w).toInt())
    }

    /** Очень сильное размытие для скрытых кадров: 10 пикселей → 40 со сглаживанием → два прохода размытия 5×5. */
    fun veil(src: Bitmap): Bitmap {
        val k = 10f / maxOf(src.width, src.height)
        val tiny = Bitmap.createScaledBitmap(src, maxOf(1, (src.width * k).toInt()), maxOf(1, (src.height * k).toInt()), true)
        val big = Bitmap.createScaledBitmap(tiny, tiny.width * 4, tiny.height * 4, true)
        if (tiny !== src) tiny.recycle()
        val w = big.width; val h = big.height
        var px = IntArray(w * h); big.getPixels(px, 0, w, 0, 0, w, h)
        repeat(2) { px = box(box(px, w, h, true), w, h, false) }
        val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        big.recycle()
        return out
    }

    /** Размытие окном 5 по одной оси (край — повтор крайнего пикселя). */
    private fun box(src: IntArray, w: Int, h: Int, horizontal: Boolean): IntArray {
        val out = IntArray(src.size)
        val n = if (horizontal) w else h; val lines = if (horizontal) h else w
        for (l in 0 until lines) for (i in 0 until n) {
            var r = 0; var g = 0; var b = 0
            for (d in -2..2) {
                val j = (i + d).coerceIn(0, n - 1)
                val c = if (horizontal) src[l * w + j] else src[j * w + l]
                r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF
            }
            out[if (horizontal) l * w + i else i * w + l] = (0xFF shl 24) or ((r / 5) shl 16) or ((g / 5) shl 8) or (b / 5)
        }
        return out
    }

    private fun luma(c: Int) = (c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114
}
