package com.eagalts.still

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.eagalts.still.media.Index
import com.eagalts.still.media.MediaItem
import com.eagalts.still.media.MediaStoreLibrary
import com.eagalts.still.media.Source
import com.eagalts.still.ui.StillApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var still: Still
    private var unobserve: () -> Unit = {}

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshAccess() }
    private val system = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        still.requestDone(it.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(this)
        I18n.init(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Actions.cleanShared(this)
        still = Still(this, MediaStoreLibrary(this), Index(File(noBackupFilesDir, "index.bin")), lifecycleScope)
        still.lastCrash = CrashReporter.takeLast(this)
        still.onExternalClosed = { finish() }
        still.askAccess = ::ask
        refreshAccess()
        handle(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            StillApp(still, dark, onAsk = ::ask)
            LaunchedEffect(still.request) {
                // окно системы не открылось — это отказ, а не тишина: Still скажет об этом
                still.request?.let { runCatching { system.launch(IntentSenderRequest.Builder(it.sender).build()) }.onFailure { still.requestDone(false) } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        when (i?.action) {
            Actions.SHARE_ORIGINAL -> i.getLongArrayExtra(Actions.EXTRA_IDS)?.let { still.shareById(it, keepLocation = true) }
            Intent.ACTION_VIEW -> i.data?.let(::openExternal)
        }
    }

    /** Открыли фото или видео из другого приложения: тот же просмотр, «вниз» — вернуться туда. */
    private fun openExternal(uri: Uri) {
        val type = i(contentResolver.getType(uri))
        lifecycleScope.launch {
            val video = type.startsWith("video/")
            val (w, h) = withContext(Dispatchers.IO) { size(uri, video) }
            val now = System.currentTimeMillis()
            val item = MediaItem(
                id = Long.MIN_VALUE + (uri.hashCode().toLong() and 0xFFFFFFF), video = video, taken = now, added = now,
                source = Source.OTHER, bucketId = 0, bucket = "", mime = type, width = w, height = h, size = 0, external = uri,
            )
            still.viewing = Viewing(listOf(item), 0, null, external = true)
        }
    }

    private fun i(s: String?) = s ?: ""

    private fun size(uri: Uri, video: Boolean): Pair<Int, Int> = runCatching {
        if (video) MediaMetadataRetriever().use { r ->
            r.setDataSource(this, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            val exif = contentResolver.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } ?: 0
            if (exif == 90 || exif == 270) o.outHeight to o.outWidth else o.outWidth to o.outHeight
        }
    }.getOrDefault(0 to 0)

    private fun wanted(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun refreshAccess() {
        val full = if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        val partial = Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val before = still.access
        still.access = when { full -> Access.FULL; partial -> Access.PARTIAL; else -> Access.NONE }
        if (still.access != Access.NONE && (before != still.access || !still.loaded)) still.reload(force = true)
    }

    private fun ask() = perms.launch(wanted())

    override fun onResume() {
        super.onResume()
        still.resumed()
    }

    override fun onStart() {
        super.onStart()
        refreshAccess()
        still.reload()
        // изменения медиа — по событию (снял кадр, другое приложение удалило), без опроса
        unobserve = still.lib.observe { still.reload() }
    }

    override fun onStop() {
        unobserve(); unobserve = {}
        lifecycleScope.launch(Dispatchers.IO) { still.index.saveIfDirty() }
        still.thumbs.trim()
        super.onStop()
    }
}
