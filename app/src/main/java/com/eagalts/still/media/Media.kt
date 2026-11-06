package com.eagalts.still.media

/**
 * Откуда кадр. Камера — это память, остальное — информация (скриншоты, мессенджеры, загрузки):
 * на этом различии стоит вся лента Still.
 */
enum class Source { CAMERA, SCREENSHOT, MESSENGER, DOWNLOAD, OTHER }

/** Один кадр или видео из MediaStore. Времена — в миллисекундах. Ни путей, ни имён файлов: только папка-альбом. */
class MediaItem(
    val id: Long,
    val video: Boolean,
    val taken: Long,
    val added: Long,
    val source: Source,
    val bucketId: Long,
    val bucket: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val durationMs: Long = 0,
    val favorite: Boolean = false,
    /** Открыт из другого приложения: свой адрес, в MediaStore его может и не быть. */
    val external: android.net.Uri? = null,
) {
    val temporary: Boolean get() = source != Source.CAMERA
    override fun equals(other: Any?) = other is MediaItem && other.id == id
    override fun hashCode() = id.hashCode()
}

object Classify {
    private val messengers = listOf("telegram", "whatsapp", "viber", "signal", "vkontakte", "/vk/", "messenger", "snapchat", "discord", "wechat", "line/")
    private val messengerPkgs = listOf("org.telegram", "org.thunderdog.challegram", "com.whatsapp", "com.viber", "org.thoughtcrime.securesms",
        "com.vkontakte", "ru.oneme", "com.facebook.orca", "com.snapchat", "com.discord", "com.tencent.mm", "jp.naver.line")

    /**
     * По `RELATIVE_PATH`, имени папки и пакету-владельцу (где Android его даёт). Сначала шум, потом камера:
     * «DCIM/Screenshots» — это скриншоты, хоть и в DCIM.
     */
    fun source(relativePath: String?, bucket: String?, owner: String?): Source {
        val p = (relativePath ?: "").lowercase().let { if (it.endsWith("/")) it else "$it/" }
        val b = (bucket ?: "").lowercase()
        val o = (owner ?: "").lowercase()
        return when {
            "screenshot" in p || "screenshot" in b || "screenrecord" in p || "screen record" in p -> Source.SCREENSHOT
            messengers.any { it in p } || messengerPkgs.any { o.startsWith(it) } -> Source.MESSENGER
            p.startsWith("download/") -> Source.DOWNLOAD
            p.startsWith("dcim/") || "camera" in o -> Source.CAMERA
            else -> Source.OTHER
        }
    }
}

/**
 * Правило 1 — «временное по умолчанию». Камера в ленте навсегда; всё остальное живёт в ленте [TEMP_DAYS] дней
 * с момента появления на телефоне и тихо уходит в «Поток» (не удаляется). Оставленное вручную — навсегда.
 */
object Ephemeral {
    const val TEMP_DAYS = 14
    const val DAY = 86_400_000L

    fun inFeed(it: MediaItem, now: Long, kept: Set<Long>): Boolean =
        !it.temporary || it.id in kept || now - it.added < TEMP_DAYS * DAY

    /** Сколько полных дней кадру осталось в ленте; null — он не уходит (камера или оставлен). */
    fun daysLeft(it: MediaItem, now: Long, kept: Set<Long>): Int? {
        if (!it.temporary || it.id in kept) return null
        val left = TEMP_DAYS * DAY - (now - it.added)
        return if (left <= 0) 0 else ((left + DAY - 1) / DAY).toInt()
    }
}

/** Плитка ленты: одиночный кадр или стопка (сверху лучший). */
class Entry(val cover: MediaItem, val members: List<MediaItem>) {
    val stacked: Boolean get() = members.size > 1
    val key: Long get() = cover.id
}

/**
 * Правило 2 — «лента моментов». Соседние по времени кадры одного источника складываются в стопку, если это
 * серия (≤ [BURST_MS] между кадрами) или почти одинаковые снимки (≤ [NEAR_MS] и разница dHash ≤ [NEAR_BITS]).
 * Видео в стопки не идут. Лучший — самый тяжёлый файл: на одном сюжете больший JPEG = больше деталей (резче).
 */
object Stacks {
    const val BURST_MS = 2_000L
    const val NEAR_MS = 90_000L
    const val NEAR_BITS = 6

    /** [items] — по убыванию времени съёмки. [hash] — 0, если ещё не посчитан. */
    fun build(items: List<MediaItem>, hash: (Long) -> Long): List<Entry> {
        val out = ArrayList<Entry>(items.size)
        var run = ArrayList<MediaItem>()
        fun flush() {
            if (run.isEmpty()) return
            out += Entry(run.maxBy { it.size }, run)
            run = ArrayList()
        }
        for (it in items) {
            val prev = run.lastOrNull()
            if (prev != null && !joins(prev, it, hash)) flush()
            run += it
        }
        flush()
        return out
    }

    internal fun joins(a: MediaItem, b: MediaItem, hash: (Long) -> Long): Boolean {
        if (a.video || b.video || a.source != b.source) return false
        val dt = kotlin.math.abs(a.taken - b.taken)
        if (dt <= BURST_MS) return true
        if (dt > NEAR_MS) return false
        val ha = hash(a.id); val hb = hash(b.id)
        return ha != 0L && hb != 0L && java.lang.Long.bitCount(ha xor hb) <= NEAR_BITS
    }
}

/**
 * Правило 3 — «чистка по образцу». Выделил один мусорный кадр — «таких» ещё N: тот же источник, та же папка,
 * то же видео/фото и та же форма кадра (скриншоты одного телефона — один размер). Камеру по образцу не чистим:
 * там каждый кадр свой.
 */
object Similar {
    fun of(examples: Collection<MediaItem>, all: List<MediaItem>): List<MediaItem> {
        if (examples.isEmpty() || examples.any { !it.temporary }) return emptyList()
        val keys = examples.map(::key).toSet()
        if (keys.size != 1) return emptyList()
        val k = keys.first()
        return all.filter { key(it) == k }
    }

    private fun key(it: MediaItem): String {
        val aspect = if (it.width > 0 && it.height > 0) Math.round(maxOf(it.width, it.height) * 10f / minOf(it.width, it.height)) else 0
        return "${it.source}|${it.bucketId}|${it.video}|$aspect"
    }
}
