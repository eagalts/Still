package com.eagalts.still.media

import com.eagalts.still.Vault
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Своё знание Still о кадрах: что оставлено навсегда, отпечатки (dHash) и цвет. Лежит одним файлом,
 * зашифрованным [Vault]; потерялся ключ — всё считается заново, фото это не касается.
 * Пишется только при уходе из приложения и только если что-то изменилось.
 */
class Index(private val file: File?) {
    private val kept = HashSet<Long>()
    private val hash = HashMap<Long, Long>()
    private val color = HashMap<Long, Int>()
    /** Место съёмки из файла (широта, долгота) или NaN — проверено, места нет. Нет ключа — ещё не читали. */
    private val place = HashMap<Long, Long>()
    @Volatile private var dirty = false
    private val lock = Any()

    init { file?.let(Vault::read)?.let { runCatching { decode(it) } } }

    fun keptIds(): Set<Long> = synchronized(lock) { HashSet(kept) }
    fun keep(ids: Collection<Long>) = synchronized(lock) { if (kept.addAll(ids)) dirty = true }
    fun hash(id: Long): Long = synchronized(lock) { hash[id] ?: 0L }
    fun color(id: Long): Int? = synchronized(lock) { color[id] }
    fun hasPrint(id: Long): Boolean = synchronized(lock) { id in hash }
    fun placeChecked(id: Long): Boolean = synchronized(lock) { id in place }
    /** Место кадра или null (нет в файле / ещё не читали). */
    fun place(id: Long): FloatArray? = synchronized(lock) {
        val v = place[id] ?: return null
        val lat = Float.fromBits((v ushr 32).toInt()); val lon = Float.fromBits(v.toInt())
        if (lat.isNaN()) null else floatArrayOf(lat, lon)
    }
    fun putPlace(id: Long, lat: Float, lon: Float) = synchronized(lock) {
        place[id] = (lat.toRawBits().toLong() shl 32) or (lon.toRawBits().toLong() and 0xFFFFFFFFL); dirty = true
    }

    fun putPrint(id: Long, h: Long, c: Int) = synchronized(lock) {
        hash[id] = h; color[id] = c; dirty = true
    }

    /** Убрать знание о кадрах, которых больше нет (удалены другим приложением). */
    fun retain(alive: Set<Long>) = synchronized(lock) {
        val before = kept.size + hash.size
        kept.retainAll(alive); hash.keys.retainAll(alive); color.keys.retainAll(alive); place.keys.retainAll(alive)
        if (kept.size + hash.size != before) dirty = true
    }

    fun saveIfDirty() {
        val f = file ?: return
        val bytes = synchronized(lock) { if (!dirty) return; dirty = false; encode() }
        runCatching { Vault.write(f, bytes) }
    }

    private fun encode(): ByteArray {
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).use { o ->
            o.writeByte(2)
            o.writeInt(kept.size); kept.forEach(o::writeLong)
            o.writeInt(hash.size); hash.forEach { (k, v) -> o.writeLong(k); o.writeLong(v); o.writeInt(color[k] ?: 0) }
            o.writeInt(place.size); place.forEach { (k, v) -> o.writeLong(k); o.writeLong(v) }
        }
        return bo.toByteArray()
    }

    private fun decode(b: ByteArray) {
        DataInputStream(ByteArrayInputStream(b)).use { i ->
            val v = i.readByte().toInt()
            if (v != 1 && v != 2) return
            repeat(i.readInt()) { kept += i.readLong() }
            repeat(i.readInt()) { val k = i.readLong(); hash[k] = i.readLong(); color[k] = i.readInt() }
            if (v >= 2) repeat(i.readInt()) { val k = i.readLong(); place[k] = i.readLong() }
        }
    }
}
