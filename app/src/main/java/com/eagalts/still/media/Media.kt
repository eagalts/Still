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
    /** Поворот из EXIF (0/90/180/270). [width]/[height] — уже с ним, как кадр виден. */
    val orientation: Int = 0,
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
 * Правило 2 — «лента моментов». Кадры одного источника складываются в стопку, если это серия (≤ [BURST_MS]
 * между кадрами) или почти одинаковые снимки в окне [NEAR_MS]: отпечатки (dHash) различаются не больше чем на
 * [NEAR_BITS] бит, а при заметной разнице ещё и основной цвет совпадает. Кадр сравнивается не только с соседом,
 * а с несколькими предыдущими в окне — чужой снимок между двумя похожими стопку не рвёт.
 * Видео в стопки не идут. Лучший — самый тяжёлый файл: на одном сюжете больший JPEG = больше деталей (резче).
 */
object Stacks {
    const val BURST_MS = 2_000L
    const val NEAR_MS = 10 * 60_000L
    const val NEAR_BITS = 12
    /** До этой разницы отпечатков цвет не проверяем — кадры и так почти одинаковые. */
    const val SURE_BITS = 6
    private const val LOOK_BACK = 12

    /** [items] — по убыванию времени съёмки. [hash] — 0, если ещё не посчитан; [color] — null, если не посчитан. */
    fun build(items: List<MediaItem>, color: (Long) -> Int? = { null }, hash: (Long) -> Long): List<Entry> {
        val n = items.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
        val hashes = LongArray(n) { hash(items[it].id) }
        for (i in 1 until n) {
            val b = items[i]
            if (b.video) continue
            var best = -1; var bestD = Int.MAX_VALUE
            var j = i - 1
            while (j >= 0 && i - j <= LOOK_BACK) {
                val a = items[j]
                if (kotlin.math.abs(a.taken - b.taken) > NEAR_MS) break
                val d = distance(a, b, hashes[j], hashes[i], color)
                if (d >= 0 && d < bestD) { best = j; bestD = d }
                j--
            }
            if (best >= 0) parent[find(i)] = find(best)
        }
        // стопка встаёт на место своего самого свежего кадра
        val groups = LinkedHashMap<Int, ArrayList<MediaItem>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { ArrayList() } += items[i]
        return groups.values.map { m -> Entry(m.maxBy { it.size }, m) }
    }

    /** −1 — не складываются; иначе «насколько похожи» (меньше — ближе), серия — 0. */
    internal fun distance(a: MediaItem, b: MediaItem, ha: Long, hb: Long, color: (Long) -> Int?): Int {
        if (a.video || b.video || a.source != b.source) return -1
        if (kotlin.math.abs(a.taken - b.taken) <= BURST_MS) return 0
        if (ha == 0L || hb == 0L) return -1
        val d = java.lang.Long.bitCount(ha xor hb)
        if (d > NEAR_BITS) return -1
        if (d > SURE_BITS) {
            val ca = color(a.id); val cb = color(b.id)
            if (ca == null || cb == null || Hue.of(ca) != Hue.of(cb)) return -1
        }
        return d
    }

    internal fun joins(a: MediaItem, b: MediaItem, hash: (Long) -> Long): Boolean = distance(a, b, hash(a.id), hash(b.id)) { null } >= 0
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
