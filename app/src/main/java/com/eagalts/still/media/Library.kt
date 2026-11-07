package com.eagalts.still.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Источник кадров. На телефоне — [MediaStoreLibrary], в тестах — подделка. */
interface Library {
    fun load(trashed: Boolean = false): List<MediaItem>
    fun uri(it: MediaItem): Uri
    /** Миниатюра для плитки (системный кэш). Блокирующий вызов — только с пула потоков. */
    fun thumbnail(it: MediaItem, px: Int, cancel: CancellationSignal): Bitmap?
    /** Кадр для просмотра: не больше [maxPx] по длинной стороне. */
    fun full(it: MediaItem, maxPx: Int): Bitmap?
    /** Поколение MediaStore: не изменилось — перечитывать нечего. */
    fun generation(): Long = -1
    fun observe(onChange: () -> Unit): () -> Unit = { }
}

class MediaStoreLibrary(ctx: Context) : Library {
    private val cr: ContentResolver = ctx.contentResolver
    private val ctx = ctx.applicationContext
    private val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    override fun load(trashed: Boolean): List<MediaItem> {
        val cols = buildList {
            add(MediaStore.Files.FileColumns._ID); add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            add(MediaStore.MediaColumns.DATE_TAKEN); add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.RELATIVE_PATH); add(MediaStore.MediaColumns.BUCKET_ID)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME); add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE); add(MediaStore.MediaColumns.WIDTH); add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.SIZE); add(MediaStore.MediaColumns.DURATION)
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.MediaColumns.IS_FAVORITE)
            add(MediaStore.MediaColumns.ORIENTATION)
        }.toTypedArray()
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}," +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns.DATE_ADDED} DESC")
            if (Build.VERSION.SDK_INT >= 30) {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
            }
        }
        if (trashed && Build.VERSION.SDK_INT < 30) return emptyList()
        val out = ArrayList<MediaItem>()
        cr.query(files, cols, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(cols[0]); val iType = c.getColumnIndexOrThrow(cols[1])
            val iTaken = c.getColumnIndexOrThrow(cols[2]); val iAdded = c.getColumnIndexOrThrow(cols[3])
            val iPath = c.getColumnIndexOrThrow(cols[4]); val iBid = c.getColumnIndexOrThrow(cols[5])
            val iBucket = c.getColumnIndexOrThrow(cols[6]); val iOwner = c.getColumnIndexOrThrow(cols[7])
            val iMime = c.getColumnIndexOrThrow(cols[8]); val iW = c.getColumnIndexOrThrow(cols[9])
            val iH = c.getColumnIndexOrThrow(cols[10]); val iSize = c.getColumnIndexOrThrow(cols[11])
            val iDur = c.getColumnIndexOrThrow(cols[12])
            val iFav = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndexOrThrow(cols[13]) else -1
            val iRot = c.getColumnIndex(MediaStore.MediaColumns.ORIENTATION)
            out.ensureCapacity(c.count)
            while (c.moveToNext()) {
                val added = c.getLong(iAdded) * 1000
                val taken = c.getLong(iTaken).takeIf { it > 0 } ?: added
                val bucket = c.getString(iBucket) ?: ""
                out += MediaItem(
                    id = c.getLong(iId),
                    video = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
                    taken = taken, added = added,
                    source = Classify.source(c.getString(iPath), bucket, c.getString(iOwner)),
                    bucketId = c.getLong(iBid), bucket = bucket,
                    mime = c.getString(iMime) ?: "",
                    // MediaStore даёт размер сохранённых пикселей; повёрнутый кадр на экране — с переставленными сторонами
                    width = if (rot(c, iRot) % 180 == 0) c.getInt(iW) else c.getInt(iH),
                    height = if (rot(c, iRot) % 180 == 0) c.getInt(iH) else c.getInt(iW),
                    orientation = rot(c, iRot),
                    size = c.getLong(iSize),
                    durationMs = c.getLong(iDur),
                    favorite = iFav >= 0 && c.getInt(iFav) == 1,
                )
            }
        }
        return out
    }

    private fun rot(c: android.database.Cursor, i: Int) = if (i >= 0 && !c.isNull(i)) ((c.getInt(i) % 360) + 360) % 360 else 0

    override fun uri(it: MediaItem): Uri = it.external ?: ContentUris.withAppendedId(
        if (it.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.id
    )

    override fun thumbnail(it: MediaItem, px: Int, cancel: CancellationSignal): Bitmap? =
        runCatching { cr.loadThumbnail(uri(it), Size(px, px), cancel) }.getOrNull()

    override fun full(it: MediaItem, maxPx: Int): Bitmap? = runCatching {
        if (it.video) return@runCatching cr.loadThumbnail(uri(it), Size(maxPx, maxPx), null)
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri(it))) { dec, info, _ ->
            val w = info.size.width; val h = info.size.height
            val k = maxPx.toFloat() / maxOf(w, h)
            if (k < 1f) dec.setTargetSize(maxOf(1, (w * k).toInt()), maxOf(1, (h * k).toInt()))
            // аппаратный битмап: не занимает кучу Java и рисуется сразу с GPU
            dec.allocator = ImageDecoder.ALLOCATOR_HARDWARE
        }
    }.getOrNull()

    override fun generation(): Long = if (Build.VERSION.SDK_INT >= 30)
        runCatching { MediaStore.getGeneration(ctx, MediaStore.VOLUME_EXTERNAL) }.getOrDefault(-1) else -1

    override fun observe(onChange: () -> Unit): () -> Unit {
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        cr.registerContentObserver(files, true, o)
        return { cr.unregisterContentObserver(o) }
    }
}

/** Отмена декодирования, когда плитка ушла с экрана: корутину отменили — системный декодер тоже бросает работу. */
suspend fun <T> cancellable(block: (CancellationSignal) -> T): T = suspendCancellableCoroutine { cont ->
    val sig = CancellationSignal()
    cont.invokeOnCancellation { sig.cancel() }
    cont.resume(block(sig))
}
