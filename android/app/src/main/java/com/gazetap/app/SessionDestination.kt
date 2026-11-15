package com.gazetap.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * A destination in the phone's public Downloads folder, so a file can be found and shared from any
 * file manager or messaging app without adb.
 *
 * Why MediaStore on Android 10+: scoped storage blocks direct writes to /sdcard/Download, and
 * MediaStore is the supported way to publish a user-visible document with no runtime permission.
 * On Android 8-9 the legacy public Downloads path still works, but needs WRITE_EXTERNAL_STORAGE.
 */
class SessionDestination private constructor(
    /** Human-readable location, e.g. "Download/GazeTap/gazetap_session_20260101_101010.csv". */
    val displayPath: String,
    val outputStream: OutputStream,
    private val onComplete: (Boolean) -> Unit,
) {
    /** Call exactly once: marks the MediaStore row visible (or deletes it on failure). */
    fun complete(success: Boolean) {
        onComplete(success)
    }

    companion object {
        const val FOLDER = "GazeTap"

        fun create(
            context: Context,
            fileName: String,
            mimeType: String = "text/csv",
        ): SessionDestination {
            val displayPath = "Download/$FOLDER/$fileName"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("could not create a Downloads entry")
                val stream = resolver.openOutputStream(uri)
                    ?: throw IllegalStateException("could not open the Downloads entry for writing")
                return SessionDestination(displayPath, stream) { ok ->
                    if (ok) {
                        resolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                            null,
                            null,
                        )
                    } else {
                        resolver.delete(uri, null, null)
                    }
                }
            }

            // Android 8-9: legacy public Downloads directory.
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(downloads, FOLDER)
            if (!dir.exists() && !dir.mkdirs()) {
                throw IllegalStateException("could not create ${dir.absolutePath}")
            }
            val file = File(dir, fileName)
            return SessionDestination(displayPath, FileOutputStream(file)) { ok ->
                if (!ok) file.delete()
            }
        }
    }
}
