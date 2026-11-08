package com.remotemonitor.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local edge recordings under getExternalFilesDir("recordings"), capped by count and total size (oldest evicted first). */
class RecordingManager(private val ctx: Context) {
    companion object {
        const val MAX_BYTES = 500L * 1024 * 1024
        const val MAX_FILES = 20
    }

    fun dir(): File {
        val d = ctx.getExternalFilesDir("recordings") ?: File(ctx.filesDir, "recordings")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun listFiles(): List<File> =
        dir().listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.sortedBy { it.lastModified() } ?: emptyList()

    fun newRecordingFile(): File {
        enforceQuota()
        val name = "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        return File(dir(), name)
    }

    fun enforceQuota() {
        var files = listFiles()
        var totalBytes = files.sumOf { it.length() }
        while (files.isNotEmpty() && (files.size >= MAX_FILES || totalBytes > MAX_BYTES)) {
            val oldest = files.first()
            totalBytes -= oldest.length()
            oldest.delete()
            files = files.drop(1)
        }
    }

    fun delete(filename: String): Boolean {
        val f = safeFile(filename) ?: return false
        return f.delete()
    }

    /** Resolves filename -> file inside the recordings dir only, rejecting any path traversal attempt. */
    fun safeFile(filename: String): File? {
        if (filename.isBlank() || filename.contains("..") || filename.contains('/') || filename.contains('\\')) return null
        val f = File(dir(), filename)
        return if (f.exists() && f.isFile) f else null
    }
}
