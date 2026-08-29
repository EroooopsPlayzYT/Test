package com.eroooops.beamformer.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Recording(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val modified: Long,
) {
    /** "beam" or "raw" — which half of a session this file is. */
    val kind: String get() = when {
        name.endsWith("-beam.wav") -> "beamformed"
        name.endsWith("-raw.wav") -> "raw channels"
        else -> "audio"
    }
}

/**
 * The saved sessions, kept in app-scoped storage.
 *
 * App-scoped means no storage permission is needed and nothing survives an
 * uninstall; sharing goes out through a content URI, which is also how a file
 * gets off the phone for offline work.
 */
class Recordings(context: Context) {

    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "recordings")

    fun directory(): File = directory.apply { mkdirs() }

    fun list(): List<Recording> = directory.listFiles()
        ?.filter { it.isFile && it.extension == "wav" }
        ?.sortedByDescending { it.lastModified() }
        ?.map { Recording(it, it.name, it.length(), it.lastModified()) }
        ?: emptyList()

    fun newLabel(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun delete(recording: Recording): Boolean = runCatching { recording.file.delete() }
        .getOrDefault(false)

    fun totalBytes(): Long = list().sumOf { it.sizeBytes }

    /** A share intent for one recording, or null if the file has gone. */
    fun shareIntent(recording: Recording): Intent? = runCatching {
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.recordings",
            recording.file,
        )
        Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrNull()

    /** Plain-text share, used for the capability report. */
    fun shareText(text: String, subject: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
}
