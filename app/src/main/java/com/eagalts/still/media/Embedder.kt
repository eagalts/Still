package com.eagalts.still.media

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * «Что на кадре» — вектор из маленькой модели (MobileNetV3 small, ~4 МБ, лежит в самом приложении), только на
 * телефоне, без сети. Вход — миниатюра 224×224, яркости в [0, 1]; выход — 1024 числа, хранятся байтами (int8):
 * на раскладку по сценам это не влияет. Модель открывается на время фонового прохода и закрывается после.
 */
class Embedder(private val ctx: Context) : AutoCloseable {
    private var interp: org.tensorflow.lite.Interpreter? = null
    private var failed = false
    private val input = ByteBuffer.allocateDirect(SIDE * SIDE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(DIM) }
    private val px = IntArray(SIDE * SIDE)

    private fun open(): org.tensorflow.lite.Interpreter? {
        interp?.let { return it }
        if (failed) return null
        return runCatching {
            val buf = ctx.assets.openFd(MODEL).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
            org.tensorflow.lite.Interpreter(buf, org.tensorflow.lite.Interpreter.Options().setNumThreads(2))
        }.onFailure { failed = true }.getOrNull()?.also { interp = it }
    }

    /** Вектор кадра или null (модель не открылась — стопок по смыслу не будет, лента работает как раньше). */
    fun embed(src: Bitmap): ByteArray? {
        val it = open() ?: return null
        val s = Bitmap.createScaledBitmap(src, SIDE, SIDE, true)
        s.getPixels(px, 0, SIDE, 0, 0, SIDE, SIDE)
        if (s !== src) s.recycle()
        input.rewind()
        for (c in px) {
            input.putFloat((c shr 16 and 0xFF) / 255f)
            input.putFloat((c shr 8 and 0xFF) / 255f)
            input.putFloat((c and 0xFF) / 255f)
        }
        input.rewind()
        return runCatching { it.run(input, output); quantize(output[0]) }.getOrNull()
    }

    override fun close() { interp?.close(); interp = null }

    companion object {
        const val SIDE = 224
        const val DIM = 1024
        const val MODEL = "scenes.tflite"

        /** Сжатие в байты: делим на наибольшее по модулю — направление вектора сохраняется. */
        fun quantize(v: FloatArray): ByteArray {
            var m = 1e-9f
            for (x in v) m = maxOf(m, Math.abs(x))
            return ByteArray(v.size) { Math.round(v[it] / m * 127f).coerceIn(-127, 127).toByte() }
        }
    }
}

/**
 * Векторы кадров — отдельным файлом, зашифрованным [com.eagalts.still.Vault] (килобайт на кадр). Читается
 * в фоне при первой загрузке ленты, пишется при уходе из приложения и только если что-то добавилось.
 */
class Vectors(private val file: File?) {
    private val map = HashMap<Long, ByteArray>()
    /** Бумага ли кадр ([Paperwork]); нет ключа — ещё не решали. */
    private val paper = HashMap<Long, Boolean>()
    private val lock = Any()
    @Volatile var loaded = false; private set
    @Volatile private var dirty = false

    /** Блокирующее чтение — не на главном потоке. */
    fun load() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            file?.let(com.eagalts.still.Vault::read)?.let { runCatching { decode(it) } }
            loaded = true
        }
    }

    operator fun get(id: Long): ByteArray? = synchronized(lock) { map[id] }
    fun has(id: Long): Boolean = synchronized(lock) { id in map }
    fun put(id: Long, v: ByteArray) = synchronized(lock) { map[id] = v; dirty = true }
    fun paper(id: Long): Boolean? = synchronized(lock) { paper[id] }
    /** Какими весами решали «бумага или нет»; другие веса — решения забываются и считаются заново. */
    private var paperModel = 0L
    fun usePaperModel(sig: Long) = synchronized(lock) { if (sig != paperModel) { paper.clear(); paperModel = sig; dirty = true } }
    fun putPaper(id: Long, yes: Boolean) = synchronized(lock) { paper[id] = yes; dirty = true }
    val size: Int get() = synchronized(lock) { map.size }

    fun retain(alive: Set<Long>) = synchronized(lock) { if (map.keys.retainAll(alive) or paper.keys.retainAll(alive)) dirty = true }

    fun saveIfDirty() {
        val f = file ?: return
        val bytes = synchronized(lock) { if (!dirty || !loaded) return; dirty = false; encode() }
        runCatching { com.eagalts.still.Vault.write(f, bytes) }
    }

    private fun encode(): ByteArray {
        val dim = map.values.firstOrNull()?.size ?: 0
        val good = map.count { it.value.size == dim }
        val b = ByteBuffer.allocate(1 + 4 + 4 + good * (8 + dim) + 8 + 4 + paper.size * 9)
        b.put(3); b.putInt(dim); b.putInt(good)
        for ((k, v) in map) if (v.size == dim) { b.putLong(k); b.put(v) }
        b.putLong(paperModel)
        b.putInt(paper.size)
        for ((k, v) in paper) { b.putLong(k); b.put(if (v) 1 else 0) }
        return b.array().copyOf(b.position())
    }

    private fun decode(raw: ByteArray) {
        val b = ByteBuffer.wrap(raw)
        val ver = b.get().toInt()
        if (ver !in 1..3) return
        val dim = b.int; val n = b.int
        repeat(n) {
            if (b.remaining() < 8 + dim) return
            val k = b.long; val v = ByteArray(dim); b.get(v); map[k] = v
        }
        if (ver >= 3 && b.remaining() >= 8) paperModel = b.long
        // v2 не помнил, какими весами решали, — его решения забываем (paperModel = 0 не совпадёт ни с какими весами)
        if (ver >= 3 && b.remaining() >= 4) repeat(b.int) {
            if (b.remaining() < 9) return
            val k = b.long; paper[k] = b.get().toInt() == 1
        }
    }
}
