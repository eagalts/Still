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
        (!item.video && !index.hasPrint(item.id)) || (embedding && vectors?.has(item.id) == false)

    suspend fun printInBackground(item: MediaItem, embedder: Embedder? = null) {
        val needPrint = !item.video && !index.hasPrint(item.id)
        val needVec = embedder != null && vectors?.has(item.id) == false
        if (!needPrint && !needVec) return
        withContext(printPool) {
            // одна миниатюра на всё: 256 — модели нужно 224, отпечатку хватило бы и 160
            val bmp = cancellable { sig -> lib.thumbnail(item, if (needVec) 256 else 160, sig) } ?: return@withContext
            if (needPrint) runCatching { index.putPrint(item.id, Pixels.dHash(bmp), Pixels.memoryColor(bmp)) }
            if (needVec) embedder?.embed(bmp)?.let { vectors?.put(item.id, it) }
            bmp.recycle()
        }
    }

    fun trim() = cache.trimToSize(budget / 4)
}
