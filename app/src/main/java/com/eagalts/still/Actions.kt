package com.eagalts.still

import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.service.chooser.ChooserAction
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.eagalts.still.media.Library
import com.eagalts.still.media.MediaItem
import java.io.File

/** Системные действия с кадрами. Фото Still сам не меняет: правки идут через окна Android (корзина, избранное). */
object Actions {
    const val SHARE_ORIGINAL = "com.eagalts.still.SHARE_ORIGINAL"
    const val EXTRA_IDS = "ids"

    fun trashRequest(ctx: Context, uris: List<Uri>, toTrash: Boolean): IntentSender? =
        if (Build.VERSION.SDK_INT >= 30) runCatching { MediaStore.createTrashRequest(ctx.contentResolver, uris, toTrash).intentSender }.getOrNull()
        else null

    fun favoriteRequest(ctx: Context, uris: List<Uri>, on: Boolean): IntentSender? =
        if (Build.VERSION.SDK_INT >= 30) runCatching { MediaStore.createFavoriteRequest(ctx.contentResolver, uris, on).intentSender }.getOrNull()
        else null

    class Prepared(val uris: List<Uri>, val mime: String, val stripped: Int, val untouched: Int)

    private fun shareDir(ctx: Context) = File(ctx.cacheDir, "share")

    /** Копии прошлых отправок — стираются при запуске: получатель их уже забрал. */
    fun cleanShared(ctx: Context) { shareDir(ctx).listFiles()?.forEach { it.delete() } }

    // поля, по которым можно узнать место, время до секунды и сам телефон
    private val privateTags = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_BEARING_REF, ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR, ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DIFFERENTIAL, ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_CAMERA_OWNER_NAME, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SERIAL_NUMBER, ExifInterface.TAG_IMAGE_UNIQUE_ID, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_MAKER_NOTE, ExifInterface.TAG_XMP,
    )

    /**
     * Копии для отправки: JPEG/PNG/WebP — без места и данных камеры (EXIF/XMP), HEIC — перекодируется в JPEG (в нём
     * метаданные не правятся). Видео уходит как есть: честно считаем его в [Prepared.untouched]. Имена файлов — свои.
     */
    fun prepareShare(ctx: Context, items: List<MediaItem>, lib: Library, keepLocation: Boolean): Prepared {
        val mime = when {
            items.all { !it.video } -> "image/*"
            items.all { it.video } -> "video/*"
            else -> "*/*"
        }
        if (keepLocation) return Prepared(items.map(lib::uri), mime, 0, 0)
        val dir = shareDir(ctx).apply { mkdirs() }
        var stripped = 0; var untouched = 0
        val uris = items.mapIndexed { i, it ->
            val src = lib.uri(it)
            val ext = when (it.mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; else -> null }
            runCatching {
                when {
                    it.video -> { untouched++; src }
                    ext != null -> {
                        val f = File(dir, "Still_${i + 1}.$ext")
                        ctx.contentResolver.openInputStream(src)!!.use { inp -> f.outputStream().use(inp::copyTo) }
                        val ex = ExifInterface(f)
                        privateTags.forEach { tag -> ex.setAttribute(tag, null) }
                        ex.saveAttributes()
                        stripped++
                        FileProvider.getUriForFile(ctx, ctx.packageName + ".share", f)
                    }
                    else -> {
                        val bmp = lib.full(it, 4096)?.copy(Bitmap.Config.ARGB_8888, false) ?: error("decode")
                        val f = File(dir, "Still_${i + 1}.jpg")
                        f.outputStream().use { o -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, o) }
                        stripped++
                        FileProvider.getUriForFile(ctx, ctx.packageName + ".share", f)
                    }
                }
            }.getOrElse { _ -> untouched++; src }
        }
        return Prepared(uris, mime, stripped, untouched)
    }

    /**
     * Системное окно «Поделиться». На Android 14+ в нём же — кнопка «С местом съёмки»: выбор в момент отправки,
     * а не в настройках.
     */
    fun launchShare(ctx: Context, p: Prepared, ids: List<Long>, keptLocation: Boolean) {
        val send = if (p.uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, p.uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(p.uris))
        send.type = p.mime
        send.clipData = ClipData.newRawUri(null, p.uris[0]).apply { p.uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, null)
        if (Build.VERSION.SDK_INT >= 34 && !keptLocation && p.stripped > 0) {
            val again = Intent(ctx, MainActivity::class.java).setAction(SHARE_ORIGINAL)
                .putExtra(EXTRA_IDS, ids.toLongArray()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, 7, again, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            chooser.putExtra(
                Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS,
                arrayOf(ChooserAction.Builder(Icon.createWithResource(ctx, R.drawable.ic_place), t("С местом съёмки"), pi).build()),
            )
        }
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }
}
