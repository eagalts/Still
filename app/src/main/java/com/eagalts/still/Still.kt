package com.eagalts.still

import android.content.Context
import android.content.IntentSender
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.eagalts.still.media.Ephemeral
import com.eagalts.still.media.Entry
import com.eagalts.still.media.Index
import com.eagalts.still.media.Library
import com.eagalts.still.media.MediaItem
import com.eagalts.still.media.Query
import com.eagalts.still.media.Similar
import com.eagalts.still.media.Source
import com.eagalts.still.media.Moments
import com.eagalts.still.media.Thumbs
import com.eagalts.still.media.Embedder
import com.eagalts.still.media.Vectors
import com.eagalts.still.media.Paperwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Что сейчас показывает лента. null — главная лента «Твои фото». */
sealed class Filter {
    abstract val title: String
    data object Flow : Filter() { override val title get() = t("Поток") }
    data object Videos : Filter() { override val title get() = t("Видео") }
    data object Screens : Filter() { override val title get() = t("Скриншоты") }
    data object Favorites : Filter() { override val title get() = t("Избранное") }
    data object Trash : Filter() { override val title get() = t("Недавно удалённые") }
    data class Album(val id: Long, val name: String) : Filter() { override val title get() = name }
    data class Search(val text: String) : Filter() {
        val query = Query.parse(text)
        override val title get() = text
    }
}

/**
 * Ячейка сетки — кадр или стопка (её обложка). [label] — подпись месяца или года
 * на первом кадре периода (при отдалении): подпись лежит на фото, а не отдельной полосой.
 */
sealed class Cell {
    abstract val key: Any
    class Tile(val entry: Entry, val item: MediaItem, val label: String? = null) : Cell() {
        override val key: Any get() = item.id
    }
}

class Feed(val cells: List<Cell>, val cols: Int, val gen: Int)

/**
 * Подписи времени по числу столбцов (как в Фото Apple): обычная лента — сплошная, без подписей;
 * отдалил до предела — месяцы с годом; ещё дальше — только годы.
 */
enum class Level { NONE, MONTHS, YEARS }

/** Ступени щипка: частые — каждая перескладка под пальцами маленькая. */
val ColumnSteps = intArrayOf(2, 3, 4, 5, 6, 8, 10, 13)
fun levelOf(cols: Int) = when { cols <= 5 -> Level.NONE; cols <= 10 -> Level.MONTHS; else -> Level.YEARS }

enum class Access { NONE, PARTIAL, FULL }

/** Открытый просмотр: что листаем, с какого кадра, и откуда пришли (лента с фильтром или без). */
class Viewing(val items: List<MediaItem>, val start: Int, val fromFilter: Filter?, val external: Boolean = false)

/** Запрос к системе, который требует окна (корзина, избранное): активность запускает, итог — в [Still.requestDone]. */
class SystemRequest(val sender: IntentSender, val onDone: (Boolean) -> Unit)

class Still(
    val ctx: Context?,
    val lib: Library,
    val index: Index,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** Векторы сцен (что на кадре) — для стопок. */
    val vectors: Vectors = Vectors(null),
    /** Модель для векторов; null — стопки только из уже посчитанного (тесты, модель не открылась). */
    private val embedder: (() -> Embedder)? = null,
    /** «Это бумага?» поверх векторов; null — бумаги не ищем. */
    private val paperwork: (() -> Paperwork?)? = null,
) {
    val thumbs = Thumbs(ctx, lib, index, vectors)
    private val screenWidthDp: Float = (ctx?.resources?.configuration?.screenWidthDp ?: 412).toFloat()

    var access by mutableStateOf(Access.NONE)
    var all: List<MediaItem> by mutableStateOf(emptyList()); private set
    private var trashed: List<MediaItem> = emptyList()
    var loaded by mutableStateOf(false); private set

    var filter: Filter? by mutableStateOf(null); private set
    /**
     * Раскладка ленты: ячейки и число столбцов меняются вместе, одним кадром — иначе при щипке
     * на кадр мелькала бы старая раскладка в новой сетке.
     */
    var feed by mutableStateOf(Feed(emptyList(), 3, 0)); private set
    /** Сколько столбцов просили (раскладка под них может ещё строиться). */
    var columns by mutableIntStateOf(3); private set
    /** Через сколько временное (скриншоты, мессенджеры, бумаги) уходит в «Поток», мс — ползунок в «Потоке». */
    var flowTtl by androidx.compose.runtime.mutableLongStateOf(
        ctx?.getSharedPreferences("still", Context.MODE_PRIVATE)?.getLong("flowTtl", Ephemeral.DEFAULT) ?: Ephemeral.DEFAULT
    ); private set
    /** Кадры камеры, которые признаны бумагами (по стопкам), — уходят в «Поток» вместе с временным. */
    @Volatile var paper: Set<Long> = emptySet(); private set

    fun changeFlowTtl(ms: Long) {
        if (ms == flowTtl) return
        flowTtl = ms
        prefs?.edit()?.putLong("flowTtl", ms)?.apply()
        rebuild()
    }

    /** Открытая стопка — поверх ленты, как папка. */
    var stack: Entry? by mutableStateOf(null); private set

    var selection: Set<Long> by mutableStateOf(emptySet())
    var viewing: Viewing? by mutableStateOf(null)
    var sheet by mutableStateOf(false)
    var toast: Pair<String, Long>? by mutableStateOf(null)
    var lastCrash: String? by mutableStateOf(null)
    var request: SystemRequest? by mutableStateOf(null)
    /** Кадр, к которому ленте надо прокрутиться (правило 6: из поиска — в своё место во времени). */
    var reveal: Long? by mutableStateOf(null)
    /** Кадры, которые прямо сейчас уходят из ленты (анимация ухода), — до ответа системы. */
    var leaving: Set<Long> by mutableStateOf(emptySet()); private set

    private var lastGen = Long.MIN_VALUE
    private var reloadJob: Job? = null
    private var reloadAgain = false

    /** Просмотр открыт из другого приложения и закрыт — возвращаемся туда. */
    var onExternalClosed: () -> Unit = {}
    /** Попросить у Android полный доступ (если дали только часть фото). */
    var askAccess: () -> Unit = {}

    fun toast(s: String) { toast = s to System.nanoTime() }

    // ——— загрузка ———

    /** Перечитать MediaStore, если что-то изменилось (поколение растёт при любой правке медиа). */
    fun reload(force: Boolean = false) {
        if (access == Access.NONE) return
        val gen = lib.generation()
        if (!force && gen != -1L && gen == lastGen && loaded) return
        if (reloadJob?.isActive == true) { reloadAgain = true; return }
        reloadJob = scope.launch {
            val items = withContext(Dispatchers.IO) { vectors.load(); lib.load() }
            lastGen = gen
            all = items
            withContext(Dispatchers.Default) { val alive = items.mapTo(HashSet()) { it.id }; index.retain(alive); vectors.retain(alive) }
            if (filter == Filter.Trash) trashed = withContext(Dispatchers.IO) { lib.load(trashed = true) }
            loaded = true
            rebuild()
            printMissing()
            if (reloadAgain) { reloadAgain = false; reload(force = true) }
        }
    }

    private var rebuildJob: Job? = null
    private var printJob: Job? = null
    /** Версия отпечатков и векторов: растёт, когда досчитана порция, — стопки надо склеить заново. */
    @Volatile private var printVersion = 0
    @Volatile private var entryCache: Pair<List<Any?>, List<Entry>>? = null
    /** Сцены посчитанных выходов: выход не изменился — не считаем заново (ключ — состав выхода). */
    private val sceneCache: MutableMap<Long, IntArray> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<Long, IntArray>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, IntArray>?) = size > 4000
        }
    )

    /**
     * Отпечатки и векторы сцен — по событию загрузки, один раз на кадр: свежие первыми, из системных миниатюр.
     * Модель открыта только на время прохода. Лента пересобирается порциями — стопки складываются на глазах.
     */
    private fun printMissing() {
        if (printJob?.isActive == true) return
        val embedding = embedder != null
        printJob = scope.launch {
            val papers = paperwork?.let { withContext(Dispatchers.IO) { it() } }
            // веса распознавания бумаг сменились (обновление) — прежние решения пересчитываются по сохранённым векторам
            papers?.let { vectors.usePaperModel(it.signature) }
            val todo = withContext(Dispatchers.Default) { all.filter { thumbs.needsWork(it, embedding) } }
            if (todo.isEmpty()) return@launch
            val model = embedder?.invoke()
            try {
                // пересборка — после первой порции (видимое внизу складывается сразу), дальше реже и в конце
                todo.chunked(150).forEachIndexed { i, chunk ->
                    chunk.forEach { runCatching { thumbs.printInBackground(it, model, papers) } }
                    if (i == 0 || i % 15 == 14) { printVersion++; rebuild() }
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { model?.close() }
            }
            printVersion++; rebuild()
        }
    }

    fun rebuild() {
        val f = filter; val items = all; val kept = index.keptIds(); val cols = columns; val ttl = flowTtl
        val t = now()
        rebuildJob?.cancel()
        rebuildJob = scope.launch {
            val built = withContext(Dispatchers.Default) { buildCells(f, items, trashed, kept, cols, t, ttl) }
            feed = Feed(built, cols, feed.gen + 1)
            refreshStack(built)
        }
    }

    internal fun buildCells(
        f: Filter?, items: List<MediaItem>, trash: List<MediaItem>, kept: Set<Long>, cols: Int, t: Long, ttl: Long = flowTtl,
    ): List<Cell> {
        // стопки от числа столбцов не зависят: смена ступени пересчитывает только подписи, а не склейку всей ленты
        val key = listOf(f, System.identityHashCode(items), System.identityHashCode(trash), kept, printVersion, t / Ephemeral.HOUR, ttl)
        val cached = entryCache
        val entries = if (cached != null && cached.first == key) cached.second else {
        // бумаги — по стопкам всех кадров (одиночная бумага — по себе), до того как лента решит, что в ней остаётся
        val pp = Paperwork.flowIds(Moments.build(items, vectors::get, sceneCache), vectors::paper)
        paper = pp
        val base = when (f) {
            null -> items.filter { Ephemeral.inFeed(it, t, kept, ttl, pp) }
            Filter.Flow -> items.filter { Ephemeral.temporary(it, pp) }
            Filter.Videos -> items.filter { it.video }
            Filter.Screens -> items.filter { it.source == Source.SCREENSHOT }
            Filter.Favorites -> items.filter { it.favorite }
            Filter.Trash -> trash
            is Filter.Album -> items.filter { it.bucketId == f.id }
            is Filter.Search -> items.filter { f.query.matches(it, index.color(it.id)) }
        }
        // стопки — в ленте и альбомах; в поиске и корзине каждый кадр сам по себе
        val newestFirst = if (f == null || f is Filter.Album || f == Filter.Flow) Moments.build(base, vectors::get, sceneCache)
        else base.map { Entry(it, listOf(it)) }
        // лента идёт от старого к новому: самое свежее — внизу, у знака (как в Фото Apple)
        newestFirst.asReversed().also { entryCache = key to it }
        }
        val level = levelOf(cols)
        val out = ArrayList<Cell>(entries.size + entries.size / 8)
        val cal = Calendar.getInstance()
        var lastPeriod = Long.MIN_VALUE
        // подписи не наезжают друг на друга: ставим, только если до конца ряда и до следующей подписи хватает места
        val tileDp = screenWidthDp / cols
        fun need(label: String) = kotlin.math.ceil((label.length * (if (level == Level.YEARS) 13f else 9f) + 14f) / tileDp).toInt().coerceAtLeast(1)
        var lastLabelAt = Int.MIN_VALUE / 2; var lastNeed = 0
        for (e in entries) {
            val p = if (level == Level.NONE) 0L else period(cal, e.cover.taken, level)
            var label = if (level != Level.NONE && p != lastPeriod) Dates.label(e.cover.taken, level) else null
            lastPeriod = p
            if (label != null) {
                val at = out.size; val col = at % cols; val n = need(label)
                val sameRow = at / cols == lastLabelAt / cols
                if (col + n > cols || (sameRow && at - lastLabelAt < lastNeed)) label = null
                else { lastLabelAt = at; lastNeed = n }
            }
            out += Cell.Tile(e, e.cover, label)
        }
        return out
    }

    private fun period(cal: Calendar, ms: Long, level: Level): Long {
        cal.timeInMillis = ms
        val y = cal.get(Calendar.YEAR).toLong()
        return when (level) {
            Level.NONE -> 0
            Level.MONTHS -> y * 100 + cal.get(Calendar.MONTH)
            Level.YEARS -> y
        }
    }

    // ——— навигация по ленте ———

    fun open(f: Filter?) {
        sheet = false
        selection = emptySet()
        if (f == filter) return
        filter = f
        stack = null
        if (f == Filter.Trash) scope.launch {
            trashed = withContext(Dispatchers.IO) { lib.load(trashed = true) }
            rebuild()
        } else rebuild()
    }

    /**
     * Смена ступени. Раскладка меняется сразу, в этом же кадре, на тех же ячейках — посреди щипка пересборку ленты
     * ждать нельзя (пока она шла, сетка рисовалась уменьшенной, с пустыми краями).
     */
    fun zoomTo(c: Int) {
        if (c == columns) return
        val old = columns
        columns = c
        // раскладка — сразу, в этом же кадре; если сменился уровень подписей (месяцы, годы) — они досчитываются следом
        feed = Feed(feed.cells, c, feed.gen + 1)
        if (levelOf(old) != levelOf(c) || levelOf(c) != Level.NONE) rebuild()
    }

    fun openStack(e: Entry) { if (e.stacked) stack = e }
    fun closeStack() { stack = null }

    /** Лента пересобралась (удалили кадр, досчитались векторы) — открытая стопка берёт новый состав или закрывается. */
    private fun refreshStack(cells: List<Cell>) {
        val old = stack ?: return
        val ids = old.members.mapTo(HashSet()) { it.id }
        val now = cells.asSequence().mapNotNull { (it as? Cell.Tile)?.entry }
            .maxByOrNull { e -> e.members.count { it.id in ids } }
        stack = now?.takeIf { it.stacked && it.members.any { m -> m.id in ids } }
    }

    /** Просмотр кадров открытой стопки: листаются только они. */
    fun viewInStack(item: MediaItem) {
        val e = stack ?: return
        viewing = Viewing(e.members, e.members.indexOf(item).coerceAtLeast(0), filter)
    }

    /** Поиск по цвету: досчитать цвет кадрам, которые ещё не показывались, — лента дополняется на глазах. */
    fun search(text: String) {
        val f = Filter.Search(text.trim())
        if (f.text.isEmpty()) return
        open(f)
        if (f.query.needsColor) scope.launch {
            val pending = all.filter { !it.video && !index.hasPrint(it.id) }
            pending.chunked(48).forEach { chunk ->
                if (filter != f) return@launch
                chunk.forEach { thumbs.ensurePrint(it) }
                rebuild()
            }
        }
    }

    fun albums(): List<Pair<Filter.Album, MediaItem>> = all.groupBy { it.bucketId }
        .filter { (_, v) -> v.first().source != Source.CAMERA }
        .map { (id, v) -> Filter.Album(id, v.first().bucket.ifEmpty { "—" }) to v.first() }
        .sortedByDescending { it.second.taken }

    fun counts() = mapOf(
        Filter.Flow to all.count { Ephemeral.temporary(it, paper) },
        Filter.Videos to all.count { it.video },
        Filter.Screens to all.count { it.source == Source.SCREENSHOT },
        Filter.Favorites to all.count { it.favorite },
    )

    // ——— просмотр ———

    fun view(item: MediaItem) {
        val list = feed.cells.mapNotNull { (it as? Cell.Tile)?.item }
        viewing = Viewing(list, list.indexOf(item).coerceAtLeast(0), filter)
    }

    /** Правило 6: из поиска или альбома — в ленту, на то место во времени, где кадр лежит. */
    fun showInFeed(item: MediaItem) {
        viewing = null
        if (!Ephemeral.inFeed(item, now(), index.keptIds(), flowTtl, paper)) {
            // кадр уже ушёл в «Поток» — показываем его там
            open(Filter.Flow)
        } else open(null)
        reveal = item.id
    }

    /** Сколько кадру осталось в ленте, мс; null — не уходит. */
    fun left(item: MediaItem) = Ephemeral.left(item, now(), index.keptIds(), flowTtl, paper)

    fun keep(ids: Collection<Long>) {
        index.keep(ids)
        toast(if (ids.size == 1) t("Оставлено навсегда") else t("Оставлено навсегда: {0}", ids.size))
        rebuild()
    }

    // ——— выбор ———

    fun selected(): List<MediaItem> { val s = selection; return all.filter { it.id in s } + trashed.filter { it.id in s } }

    /** «Ещё N таких» к текущему выбору — правило 3. Пусто — подсказки нет. */
    fun similarToSelection(): List<MediaItem> {
        val sel = selected()
        if (sel.isEmpty() || sel.size > 3) return emptyList()
        return Similar.of(sel, all).filter { it.id !in selection }
    }

    fun selectSimilar() {
        val more = similarToSelection()
        if (more.isEmpty()) return
        selection = selection + more.map { it.id }
        toast(t("Выбрано {0}", selection.size))
    }

    // ——— действия ———

    fun requestDone(ok: Boolean) {
        val r = request ?: return
        request = null
        r.onDone(ok)
    }

    /** В системную корзину (30 дней). Плитки начинают уходить сразу; система отказала — возвращаются. */
    /**
     * Без особого доступа «Управление медиафайлами» Android подтверждает каждое удаление своим окном.
     * Один раз — при первом удалении — Still предлагает выдать доступ; дальше корзина работает без окон.
     */
    var askManage: List<MediaItem>? by mutableStateOf(null)
    private var afterManage: List<MediaItem>? = null

    private val prefs get() = ctx?.getSharedPreferences("still", Context.MODE_PRIVATE)

    /** Есть ли доступ «Управление медиафайлами» (подменяется в тестах). */
    var canManage: () -> Boolean = ::canManageMedia

    private fun canManageMedia(): Boolean {
        val c = ctx ?: return false
        return android.os.Build.VERSION.SDK_INT >= 31 && runCatching { android.provider.MediaStore.canManageMedia(c) }.getOrDefault(false)
    }

    /** «Разрешить»: открыть системный экран доступа; вернулись — удаление продолжится само. */
    fun grantManage() {
        val c = ctx ?: return
        afterManage = askManage; askManage = null
        prefs?.edit()?.putBoolean("manageAsked", true)?.apply()
        runCatching {
            c.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA, android.net.Uri.parse("package:" + c.packageName))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { afterManage?.let { trash(it, ask = false) }; afterManage = null }
    }

    /** «Не сейчас»: удаляем как раньше, с окном Android, и больше не спрашиваем. */
    fun skipManage() {
        val items = askManage ?: return
        askManage = null
        prefs?.edit()?.putBoolean("manageAsked", true)?.apply()
        trash(items, ask = false)
    }

    /** Вернулись в Still после настроек доступа — доводим начатое удаление. */
    fun resumed() {
        val items = afterManage ?: return
        afterManage = null
        trash(items, ask = false)
    }

    fun trash(items: List<MediaItem>, restore: Boolean = false, ask: Boolean = true) {
        if (items.isEmpty() || ctx == null) return
        if (ask && !restore && android.os.Build.VERSION.SDK_INT >= 31 && !canManage() && prefs?.getBoolean("manageAsked", false) != true) {
            askManage = items
            return
        }
        val ids = items.map { it.id }.toSet()
        val sender = Actions.trashRequest(ctx, items.map(lib::uri), !restore) ?: run { toast(t("Android не дал убрать")); return }
        request = SystemRequest(sender) { ok ->
            // отказ системы раньше проходил молча — со стороны выглядело как «удаление не работает»
            if (ok) removed(ids, restore) else toast(t("Android не дал убрать"))
        }
    }

    /**
     * Система согласилась: кадры рассыпаются и стекают в знак (≈0.7 с), и только потом лента перечитывается —
     * иначе плитки исчезли бы раньше, чем доиграл распад.
     */
    internal fun removed(ids: Set<Long>, restore: Boolean = false) {
        selection = emptySet()
        viewing = null
        toast(if (restore) t("Возвращено: {0}", ids.size) else t("В корзине: {0} · 30 дней", ids.size))
        if (restore) { reload(force = true); return }
        leaving = ids
        scope.launch {
            kotlinx.coroutines.delay(860)
            // перечитываем, пока MediaStore не отдаст ленту без удалённых (он бывает на долю секунды позади)
            for (attempt in 0 until 4) {
                reloadJob?.join()
                reload(force = true)
                reloadJob?.join()
                if (all.none { it.id in ids }) break
                kotlinx.coroutines.delay(400L * (attempt + 1))
            }
            leaving = emptySet()
            if (all.any { it.id in ids }) toast(t("Android не удалил кадр"))
        }
    }

    fun favorite(items: List<MediaItem>, on: Boolean) {
        if (items.isEmpty() || ctx == null) return
        val sender = Actions.favoriteRequest(ctx, items.map(lib::uri), on) ?: return
        request = SystemRequest(sender) { ok ->
            if (ok) { selection = emptySet(); reload(force = true) }
        }
    }

    fun share(items: List<MediaItem>, keepLocation: Boolean = false) {
        val c = ctx ?: return
        if (items.isEmpty()) return
        scope.launch {
            val r = withContext(Dispatchers.IO) { Actions.prepareShare(c, items, lib, keepLocation) }
            Actions.launchShare(c, r, items.map { it.id }, keepLocation)
            if (!keepLocation) when {
                r.stripped > 0 && r.untouched == 0 -> toast(t("Место съёмки убрано"))
                r.stripped > 0 -> toast(t("Место убрано с фото, у видео осталось"))
            }
            selection = emptySet()
        }
    }

    fun shareById(ids: LongArray, keepLocation: Boolean) {
        val set = ids.toSet()
        share(all.filter { it.id in set }, keepLocation)
    }

    /** Предложение в «Потоке»: скриншоты старше месяца — убрать? Только внутри «Потока», без уведомлений. */
    fun oldScreens(): List<MediaItem> {
        val t = now() - 30 * Ephemeral.DAY
        return all.filter { it.source == Source.SCREENSHOT && it.added < t && it.id !in index.keptIds() }
    }
}
