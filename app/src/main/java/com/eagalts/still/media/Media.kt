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
    /**
     * Момент — как в Фото Apple, по времени и месту: не меньше [SESSION_MIN] кадров подряд, где соседи сняты
     * в пределах часа и 1,5 км (место — из самого файла) или, если места нет, в пределах 15 минут. Складывается
     * целиком, даже если кадры не похожи (фото экрана на паре, прогулка в тумане 18:27–20:49).
     */
    const val SESSION_MIN = 4
    /** Без места: соседние кадры до 15 минут — один момент. */
    const val SESSION_GAP_MS = 15 * 60_000L
    /** С местом: соседние кадры до часа и не дальше [PLACE_M] друг от друга — один момент (прогулка, пара, вечер). */
    const val PLACE_GAP_MS = 60 * 60_000L
    const val PLACE_M = 1500.0
    /** Длиннее — это уже не момент, а день: режем по самым большим паузам. */
    const val SESSION_MAX_SPAN_MS = 6 * 60 * 60_000L

    /** [items] — по убыванию времени съёмки. [hash] — 0, если ещё не посчитан; [color] — null, если не посчитан. */
    fun build(items: List<MediaItem>, color: (Long) -> Int? = { null }, place: (Long) -> FloatArray? = { null }, hash: (Long) -> Long): List<Entry> {
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
        // момент — цепочка кадров одного источника с короткими промежутками — целиком в одну стопку
        // (видео в момент не входят и его не рвут)
        val places = Array(n) { if (items[it].video) null else place(items[it].id) }
        // соседи в одном моменте: оба с местом — до часа и рядом; иначе — до 15 минут
        fun together(a: Int, b: Int, cap: Long?): Boolean {
            val dt = items[a].taken - items[b].taken
            if (cap != null) return dt <= cap
            val pa = places[a]; val pb = places[b]
            return if (pa != null && pb != null) dt <= PLACE_GAP_MS && meters(pa, pb) <= PLACE_M else dt <= SESSION_GAP_MS
        }
        fun sessions(from: Int, to: Int, cap: Long?, out: MutableList<IntArray>) {
            var cur = ArrayList<Int>()
            fun flush() { if (cur.isNotEmpty()) out += cur.toIntArray(); cur = ArrayList() }
            for (i in from..to) {
                val it = items[i]
                if (it.video) continue
                val prev = cur.lastOrNull()
                if (prev != null && (!together(prev, i, cap) || items[prev].source != it.source)) flush()
                cur += i
            }
            flush()
        }
        val wide = ArrayList<IntArray>()
        if (n > 0) sessions(0, n - 1, null, wide)
        // слишком длинный момент — это уже день: режем по самым большим паузам, пока куски не станут короче предела
        fun cut(sess: IntArray, out: MutableList<IntArray>) {
            val span = items[sess.first()].taken - items[sess.last()].taken
            if (span <= SESSION_MAX_SPAN_MS || sess.size < 2) { out += sess; return }
            var at = 1; var best = -1L
            for (k in 1 until sess.size) { val g = items[sess[k - 1]].taken - items[sess[k]].taken; if (g > best) { best = g; at = k } }
            cut(sess.copyOfRange(0, at), out); cut(sess.copyOfRange(at, sess.size), out)
        }
        for (sess in wide) {
            val parts = ArrayList<IntArray>().also { cut(sess, it) }
            for (part in parts) if (part.size >= SESSION_MIN) part.forEach { parent[find(it)] = find(part[0]) }
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

    /** Расстояние между точками, метры (равнопромежуточная проекция — для километров точно). */
    internal fun meters(a: FloatArray, b: FloatArray): Double {
        val lat = Math.toRadians(((a[0] + b[0]) / 2.0))
        val dx = Math.toRadians((b[1] - a[1]).toDouble()) * Math.cos(lat); val dy = Math.toRadians((b[0] - a[0]).toDouble())
        return 6_371_000.0 * Math.sqrt(dx * dx + dy * dy)
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
