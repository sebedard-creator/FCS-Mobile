package com.fcsmobile.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import org.json.JSONObject

data class DeviceMedia(val uri: Uri, val name: String, val folder: String, val mime: String,
    val size: Long, val takenAt: Long, val signature: String) {
    fun metadata(hash: String) = JSONObject().put("sha256", hash).put("name", name)
        .put("relative_path", folder).put("mime", mime).put("size", size).put("taken_at", takenAt)
}

object MediaScanner {
    fun fullAccess(context: Context): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
    fun scan(context: Context, applyExclusions: Boolean = true): List<DeviceMedia> {
        check(fullAccess(context)) { "Autoriser l'accès à toutes les photos et vidéos dans les réglages" }
        val excluded = if (applyExclusions) AppSettings(context).excluded else emptySet()
        val result = mutableListOf<DeviceMedia>()
        for (volume in MediaStore.getExternalVolumeNames(context)) {
            val version = MediaStore.getVersion(context, volume)
            val collections = listOf(MediaStore.Images.Media.getContentUri(volume), MediaStore.Video.Media.getContentUri(volume))
            for (collection in collections) {
                val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.Images.ImageColumns.DATE_TAKEN)
                val cursor = context.contentResolver.query(collection, projection, null, null, null)
                    ?: error("Impossible de lire l'inventaire des médias")
                cursor.use {
                    while (it.moveToNext()) {
                        val folder = (it.getString(2) ?: "").trim('/') + "/"
                        if (excluded.any { skip -> folder.startsWith(skip.trim('/') + "/") }) continue
                        val size = it.getLong(4)
                        if (size <= 0) continue
                        val baseUri = ContentUris.withAppendedId(collection, it.getLong(0))
                        val originalAccess = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val uri = if (originalAccess) MediaStore.setRequireOriginal(baseUri) else baseUri
                        val taken = if (it.isNull(8) || it.getLong(8) <= 0) it.getLong(5) else it.getLong(8) / 1000
                        result.add(DeviceMedia(uri, it.getString(1) ?: "media", folder,
                            it.getString(3) ?: if (collection.toString().contains("video")) "video/mp4" else "image/jpeg",
                            size, taken, "$version:${it.getLong(6)}:${it.getLong(7)}:$size:$originalAccess"))
                    }
                }
            }
        }
        return result
    }
}
