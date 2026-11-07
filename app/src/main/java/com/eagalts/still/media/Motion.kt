package com.eagalts.still.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/**
 * Живые фото (Motion Photo, Pixel и другие по стандарту Google): короткое видео лежит в конце того же JPEG,
 * его длину называет XMP. Ищется только когда фото зажали в просмотре — в ленте ничего не читается.
 */
object Motion {
    private val itemRe = Regex("""<Container:Item\b[^>]*>""")
    private val lengthRe = Regex("""Item:Length="(\d+)"""")
    private val legacyRe = Regex("""MicroVideoOffset="(\d+)"""")

    /** Длина видео в конце файла (байт) по тексту XMP, или null — не живое фото. */
    fun videoLength(xmp: String?): Long? {
        if (xmp == null || ("MotionPhoto" !in xmp && "MicroVideo" !in xmp)) return null
        // новый формат: элемент контейнера с ролью MotionPhoto и его длиной
        itemRe.findAll(xmp).firstOrNull { "MotionPhoto" in it.value }?.let { m ->
            lengthRe.find(m.value)?.groupValues?.get(1)?.toLongOrNull()?.let { if (it > 0) return it }
        }
        // старый формат Pixel: длина видео от конца файла
        return legacyRe.find(xmp)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
    }

    /** Смещение видео от начала файла, или null. Блокирующий — только с фонового потока. */
    fun videoOffset(ctx: Context, uri: Uri): Long? = runCatching {
        val xmp = ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttribute(ExifInterface.TAG_XMP) }
        val len = videoLength(xmp) ?: return null
        val size = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return null
        (size - len).takeIf { it > 0 }
    }.getOrNull()

    /** Источник для плеера: тот же файл, но с [offset] — плеер видит только вложенное видео. */
    @OptIn(UnstableApi::class)
    fun source(ctx: Context, uri: Uri, offset: Long): MediaSource {
        val factory = DataSource.Factory { Offset(DefaultDataSource(ctx, false), offset) }
        return ProgressiveMediaSource.Factory(factory).createMediaSource(androidx.media3.common.MediaItem.fromUri(uri))
    }

    @OptIn(UnstableApi::class)
    private class Offset(private val inner: DataSource, private val offset: Long) : DataSource by inner {
        override fun open(dataSpec: DataSpec): Long = inner.open(dataSpec.subrange(offset))
    }
}
