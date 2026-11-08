package com.eagalts.still.media

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * Миниатюры: своя LRU в памяти поверх системного кэша. Бюджет — от класса памяти телефона (на слабых меньше).
 * Декодирование — не больше 4 потоков сразу; плитка ушла с экрана — её декодирование отменяется.
 * Пока миниатюра в руках, из неё же бесплатно снимаются отпечаток и цвет (для поиска).
 */
class Thumbs(ctx: Context?, private val lib: Library, private val index: Index, private val vectors: Vectors? = null) {
    private val budget: Int = run {
        val am = ctx?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mb = am?.memoryClass ?: 128
        val share = if (am?.isLowRamDevice == true) 10 else 6
        (mb * 1024 * 1024 / share).coerceIn(8 shl 20, 96 shl 20)
    }
    private val cache = object : LruCache<Long, Bitmap>(budget) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pool = Dispatchers.IO.limitedParallelism(4)

    /** Ключ — кадр и ступень размера: при щипке крупная миниатюра годится и для мелкой плитки. */
    private fun key(id: Long, step: Int) = id * 4 + step

    // 512 — потолок системного кэша миниатюр: крупнее Android декодирует оригинал (медленно). Ленте хватает 512.
    fun step(px: Int) = when { px <= 170 -> 0; px <= 340 -> 1; px <= 560 -> 2; else -> 3 }
    private fun stepPx(step: Int) = intArrayOf(160, 320, 512, 1024)[step]

    /** Уже есть в памяти (этой ступени или крупнее) — без ожидания, чтобы плитка не мигала пустой. */
    fun peek(id: Long, px: Int): Bitmap? {
        for (s in step(px)..3) cache.get(key(id, s))?.let { return it }
        return null
    }

    suspend fun load(item: MediaItem, px: Int): Bitmap? {
        peek(item.id, px)?.let { return it }
        val s = step(px)
        return withContext(pool) {
            val bmp = cancellable { sig -> lib.thumbnail(item, stepPx(s), sig) } ?: return@withContext null
            cache.put(key(item.id, s), bmp)
            if (!item.video && !index.hasPrint(item.id)) runCatching { index.putPrint(item.id, Pixels.dHash(bmp), Pixels.memoryColor(bmp)) }
            bmp
        }
    }

    /** Отпечаток для поиска по цвету, если плитку ещё не показывали. */
    suspend fun ensurePrint(item: MediaItem) {
        if (item.video || index.hasPrint(item.id)) return
        load(item, 160)
    }

    /** Фоновые отпечатки и векторы: один поток, мимо кэша и мимо потоков видимых плиток — лента не ждёт. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val printPool = Dispatchers.IO.limitedParallelism(1)

    /** Нужен ли кадру фоновый проход: отпечаток (фото) или вектор сцены (фото и видео — по кадру-обложке). */
    fun needsWork(item: MediaItem, embedding: Boolean): Boolean =
        (!item.video && !index.hasPrint(item.id)) || (embedding && (vectors?.has(item.id) == false || vectors?.paper(item.id) == null))

    suspend fun printInBackground(item: MediaItem, embedder: Embedder? = null, paperwork: Paperwork? = null) {
        val needPrint = !item.video && !index.hasPrint(item.id)
        val needVec = embedder != null && vectors?.has(item.id) == false
        // бумага ли — по вектору; вектор уже есть (посчитан раньше) — миниатюра не нужна
        fun judge() { val vs = vectors ?: return; if (paperwork != null && vs.paper(item.id) == null) vs[item.id]?.let { vs.putPaper(item.id, paperwork.score(it)) } }
        if (!needPrint && !needVec) { if (paperwork != null) withContext(printPool) { judge() }; return }
        withContext(printPool) {
            // одна миниатюра на всё: 256 — модели нужно 224, отпечатку хватило бы и 160
            val bmp = cancellable { sig -> lib.thumbnail(item, if (needVec) 256 else 160, sig) } ?: return@withContext
            if (needPrint) runCatching { index.putPrint(item.id, Pixels.dHash(bmp), Pixels.memoryColor(bmp)) }
            if (needVec) embedder?.embed(bmp)?.let { vectors?.put(item.id, it) }
            bmp.recycle()
            judge()
        }
    }

    /**
     * Скрытый кадр — очень размытая копия: кадр сжат до 10 пикселей по длинной стороне (сильнее размыть нельзя —
     * деталей просто не остаётся), плавно поднят до 40 и дважды сглажен — без ступенек, когда растянут на плитку.
     * Без фильтров видеокарты и без декодирования крупнее миниатюры: дешевле обычной плитки, хоть вся галерея скрыта.
     * В памяти — до 4 МБ таких копий (≈ 6 КБ на кадр), остальное пересчитывается из системной миниатюры.
     */
    private val veils = object : LruCache<Long, Bitmap>(4 shl 20) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    fun peekVeil(id: Long): Bitmap? = veils.get(id)
    suspend fun loadVeil(item: MediaItem): Bitmap? {
        veils.get(item.id)?.let { return it }
        val src = load(item, 160) ?: return null
        val v = withContext(pool) { Pixels.veil(src) }
        veils.put(item.id, v)
        return v
    }

    fun trim() = cache.trimToSize(budget / 4)
}
