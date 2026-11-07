package com.eagalts.still.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface

/**
 * Подробности кадра для панели «вверх»: читаются только когда панель открыли, только с телефона.
 * Место — координатами из самого файла: без карт и геокодинга из сети.
 */
class Details(
    val name: String?,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val camera: String?,
    val lens: String?,
    val fNumber: Double?,
    val exposure: Double?,
    val iso: Int?,
    val focal: Int?,
    val lat: Double?,
    val lon: Double?,
    val durationMs: Long,
)

object DetailsLoader {
    /** Блокирующий — только с фонового потока. */
    fun load(ctx: Context, item: MediaItem, uri: Uri): Details {
        val cr = ctx.contentResolver
        val name = runCatching {
            cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        // координаты Android отдаёт только с доступом к месту съёмки и только из «оригинала»
        val canPlace = ctx.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        val src = if (canPlace && item.external == null) runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri) else uri
        if (item.video) {
            var lat: Double? = null; var lon: Double? = null
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(ctx, src)
                    parseIso6709(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))?.let { (a, b) -> lat = a; lon = b }
                }
            }
            return Details(name, item.size, item.width, item.height, null, null, null, null, null, null, lat, lon, item.durationMs)
        }
        val ex = runCatching { cr.openInputStream(src)?.use { ExifInterface(it) } }.getOrNull()
        val make = ex?.getAttribute(ExifInterface.TAG_MAKE)?.trim()
        val model = ex?.getAttribute(ExifInterface.TAG_MODEL)?.trim()
        val camera = when {
            model.isNullOrEmpty() -> make?.takeIf { it.isNotEmpty() }
            make.isNullOrEmpty() || model.startsWith(make, ignoreCase = true) -> model
            else -> "$make $model"
        }
        val ll = ex?.latLong
        val focal35 = ex?.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)?.takeIf { it > 0 }
        val focal = focal35 ?: ex?.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)?.takeIf { it > 0 }?.let { Math.round(it).toInt() }
        return Details(
            name, item.size, item.width, item.height, camera,
            ex?.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
            ex?.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)?.takeIf { it > 0 },
            ex?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)?.takeIf { it > 0 },
            ex?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)?.takeIf { it > 0 },
            focal, ll?.getOrNull(0), ll?.getOrNull(1), 0,
        )
    }

    /** «+55.7558+037.6173/» → широта и долгота. */
    internal fun parseIso6709(s: String?): Pair<Double, Double>? {
        if (s == null) return null
        val m = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(s) ?: return null
        val a = m.groupValues[1].toDoubleOrNull() ?: return null
        val b = m.groupValues[2].toDoubleOrNull() ?: return null
        return a to b
    }

    /** Выдержка как пишут фотографы: 1/120, 0.5, 2. */
    fun shutter(sec: Double): String = when {
        sec >= 1.0 -> if (sec % 1.0 == 0.0) "${sec.toInt()}" else "%.1f".format(sec)
        sec >= 0.3 -> "%.1f".format(sec)
        else -> "1/${Math.round(1.0 / sec)}"
    }

    /** Координаты: «55.7558° с. ш., 37.6173° в. д.» — буквы сторон света переводятся. */
    fun coords(lat: Double, lon: Double, ns: Pair<String, String>, ew: Pair<String, String>): String =
        "%.4f° %s, %.4f° %s".format(kotlin.math.abs(lat), if (lat >= 0) ns.first else ns.second, kotlin.math.abs(lon), if (lon >= 0) ew.first else ew.second)
}
