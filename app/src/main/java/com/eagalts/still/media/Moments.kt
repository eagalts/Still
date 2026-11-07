package com.eagalts.still.media

/**
 * Правило 2 — «лента моментов». Стопка — это сцена: кадры одного выхода, на которых одно и то же
 * (экраны на паре, туман на прогулке, записи экрана), даже если между ними сняты другие.
 *
 * 1. Выходы — по ритму съёмки: пауза отделяет выход, если она намного длиннее обычных пауз вокруг неё
 *    (прогулка с перерывами по 40 минут — один выход) и не короче [FLOOR_MS]. Такая граница — довод, а не стена:
 *    кадры по разные её стороны дальше друг от друга на [BETA], и одно и то же содержимое склеивается и через паузу
 *    (браузер в 18:26 и он же с 19:35). Стена — только пауза больше [HARD_MS].
 * 2. Сцены — по смыслу кадра (вектор модели, [Embedder]): средняя связь, склеиваем, пока среднее
 *    расстояние между группами не больше [TAU]. Расстояние — непохожесть (1 − косинус) плюс немного за разницу
 *    во времени ([ALPHA] за [T_MS] и больше) и за границу выхода ([BETA]).
 * Чужой кадр посреди сцены её не рвёт: сцена собирается по сходству, а не по соседству.
 * Одиночный кадр остаётся обычным фото. Пороги подобраны на открытом архиве событий и проверены на реальных лентах.
 */
object Moments {
    const val K = 4.0
    const val FLOOR_MS = 45 * 60_000L
    const val HARD_MS = 4 * 60 * 60_000L
    const val MIN_TYPICAL_MS = 60_000L
    const val WIN = 8
    const val TAU = 0.85f
    const val ALPHA = 0.1f
    const val BETA = 0.1f
    const val T_MS = 3 * 60 * 60_000L
    /** Выход длиннее — режется по самым большим паузам: матрица расстояний растёт как квадрат. */
    const val MAX_OUTING = 600

    /** Номер выхода для каждого кадра; [times] — по возрастанию, мс. */
    fun outings(times: LongArray): IntArray {
        val n = times.size
        val lab = IntArray(n)
        if (n < 2) return lab
        val g = LongArray(n - 1) { times[it + 1] - times[it] }
        val around = LongArray(2 * WIN)
        var cur = 0
        for (i in g.indices) {
            val gap = g[i]
            var m = 0
            for (j in maxOf(0, i - WIN) until minOf(g.size, i + WIN + 1)) if (j != i) around[m++] = g[j]
            val typical = if (m == 0) gap.toDouble() else median(around, m)
            if (gap > HARD_MS || (gap > FLOOR_MS && gap > K * maxOf(typical, MIN_TYPICAL_MS.toDouble()))) cur++
            lab[i + 1] = cur
        }
        return lab
    }

    private fun median(a: LongArray, m: Int): Double {
        val s = a.copyOf(m); s.sort()
        return if (m % 2 == 1) s[m / 2].toDouble() else (s[m / 2 - 1] + s[m / 2]) / 2.0
    }

    /**
     * Сцены: номер группы для каждого кадра. [vecs] — векторы int8 одной длины, [times] — мс.
     * Средняя связь цепочкой ближайших соседей (NN-chain): O(n²) по времени, без перебора всех пар на каждом шаге.
     * Средняя связь не даёт инверсий, поэтому разрез «склейки не дальше [tau]» — ровно группы на этом пороге.
     */
    fun scenes(
        vecs: List<ByteArray>, times: LongArray, outing: IntArray? = null,
        tau: Float = TAU, alpha: Float = ALPHA, beta: Float = BETA,
    ): IntArray {
        val n = vecs.size
        if (n < 2) return IntArray(n)
        val norm = FloatArray(n) { i -> var s = 0L; for (b in vecs[i]) s += b * b; Math.sqrt(s.toDouble()).toFloat().coerceAtLeast(1e-6f) }
        val d = FloatArray(n * n)
        for (i in 0 until n) for (j in i + 1 until n) {
            val a = vecs[i]; val b = vecs[j]
            var dot = 0L
            for (k in a.indices) dot += a[k] * b[k]
            val cos = dot / (norm[i] * norm[j])
            val dt = Math.abs(times[i] - times[j]).toFloat() / T_MS
            val v = (1f - cos) + alpha * minOf(1f, dt) + if (outing != null && outing[i] != outing[j]) beta else 0f
            d[i * n + j] = v; d[j * n + i] = v
        }
        val size = IntArray(n) { 1 }
        val alive = BooleanArray(n) { true }
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
        val chain = IntArray(n); var top = 0
        var left = n
        while (left > 1) {
            if (top == 0) { chain[top++] = alive.indexOfFirst { it } }
            val a = chain[top - 1]
            val prev = if (top >= 2) chain[top - 2] else -1
            var b = -1; var best = Float.MAX_VALUE
            // при равенстве — предыдущий в цепочке: иначе цепочка может зациклиться
            if (prev >= 0) { b = prev; best = d[a * n + prev] }
            for (c in 0 until n) if (alive[c] && c != a) {
                val v = d[a * n + c]
                if (v < best) { best = v; b = c }
            }
            if (b == prev) {
                top -= 2
                // склейка b в a: средняя связь по формуле Ланса — Уильямса
                val sa = size[a].toFloat(); val sb = size[b].toFloat()
                for (c in 0 until n) if (alive[c] && c != a && c != b) {
                    val v = (sa * d[a * n + c] + sb * d[b * n + c]) / (sa + sb)
                    d[a * n + c] = v; d[c * n + a] = v
                }
                alive[b] = false; size[a] += size[b]; left--
                if (best <= tau) parent[find(b)] = find(a)
            } else chain[top++] = b
        }
        val ids = HashMap<Int, Int>()
        return IntArray(n) { ids.getOrPut(find(it)) { ids.size } }
    }

    /**
     * Лента записей: [items] — по убыванию времени съёмки (как отдаёт библиотека); ответ — тоже, стопка встаёт
     * на место своего самого свежего кадра. [vec] — вектор кадра или null (ещё не посчитан: кадр пока один).
     * [cache] — сцены уже посчитанных выходов: выход не изменился — не считаем заново.
     */
    fun build(items: List<MediaItem>, vec: (Long) -> ByteArray?, cache: MutableMap<Long, IntArray>? = null): List<Entry> {
        val n = items.size
        if (n == 0) return emptyList()
        val asc = items.asReversed()
        val times = LongArray(n) { asc[it].taken }
        val out = outings(times)
        val group = IntArray(n) { -1 }
        var next = 0
        var from = 0
        // стена — только пауза больше HARD_MS; границы выходов внутри — мягкие (BETA в расстоянии)
        while (from < n) {
            var to = from
            while (to + 1 < n && times[to + 1] - times[to] <= HARD_MS) to++
            for ((a, b) in split(times, from, to)) next = scenesOf(asc, a, b, out, vec, cache, group, next)
            from = to + 1
        }
        // кадры без пары — сами по себе; стопка — в порядке съёмки
        val members = HashMap<Int, ArrayList<MediaItem>>()
        for (i in 0 until n) if (group[i] >= 0) members.getOrPut(group[i]) { ArrayList() } += asc[i]
        val entries = ArrayList<Entry>(n)
        val done = HashSet<Int>()
        for (i in n - 1 downTo 0) {
            val g = group[i]
            if (g < 0) { entries += Entry(asc[i], listOf(asc[i])); continue }
            if (!done.add(g)) continue
            val m = members.getValue(g)
            entries += Entry(cover(m), m)
        }
        return entries
    }

    /** Длинный отрезок без стен режется по самым большим паузам на куски не больше [MAX_OUTING]. */
    private fun split(times: LongArray, a: Int, b: Int): List<Pair<Int, Int>> {
        if (b - a + 1 <= MAX_OUTING) return listOf(a to b)
        var at = a + 1; var best = -1L
        for (k in a + 1..b) { val g = times[k] - times[k - 1]; if (g > best) { best = g; at = k } }
        return split(times, a, at - 1) + split(times, at, b)
    }

    private fun scenesOf(
        asc: List<MediaItem>, a: Int, b: Int, out: IntArray, vec: (Long) -> ByteArray?, cache: MutableMap<Long, IntArray>?,
        group: IntArray, base: Int,
    ): Int {
        val idx = (a..b).filter { vec(asc[it].id) != null }
        if (idx.size < 2) return base
        var sig = 1125899906842597L
        for (i in idx) sig = 31 * (31 * sig + asc[i].id) + out[i]
        val lab = cache?.get(sig) ?: scenes(idx.map { vec(asc[it].id)!! }, LongArray(idx.size) { asc[idx[it]].taken }, IntArray(idx.size) { out[idx[it]] })
            .also { cache?.put(sig, it) }
        val count = IntArray(idx.size)
        for (l in lab) count[l]++
        for ((k, i) in idx.withIndex()) if (count[lab[k]] > 1) group[i] = base + lab[k]
        return base + idx.size
    }

    /** Обложка — лучший кадр: самое тяжёлое фото (на одном сюжете больший JPEG = больше деталей); видео — если только видео. */
    private fun cover(m: List<MediaItem>): MediaItem = m.filter { !it.video }.maxByOrNull { it.size } ?: m.first()
}
