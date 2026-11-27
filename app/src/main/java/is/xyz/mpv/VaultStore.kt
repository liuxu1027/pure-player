package `is`.xyz.mpv

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Private vault: moves videos into the app-private directory (with a .nomedia
 * marker) and removes their MediaStore entry, so they disappear from the home
 * list, gallery and file managers. Restore moves them back and re-indexes them.
 *
 * The app has no MANAGE_EXTERNAL_STORAGE, so deleting a shared-storage file
 * goes through ContentResolver.delete() on its MediaStore Uri, which may throw
 * RecoverableSecurityException (user consent) — callers handle that.
 */
object VaultStore {
    private const val PREFS_NAME = "pure_vault"
    private const val KEY_ITEMS = "items"
    private const val VAULT_DIR_NAME = "private"

    data class VaultItem(
        val originalPath: String,
        val vaultName: String,
        val displayName: String,
        val durationMs: Long,
        val sizeBytes: Long,
        val dateAdded: Long
    )

    /** A pending move: the file has been copied into the vault but the original is not yet removed. */
    data class PendingMove(
        val video: VideoItem,
        val vaultFile: File,
        val vaultName: String
    )

    /** App-private vault dir; creates it plus a .nomedia marker. Null if not usable. */
    fun vaultDir(context: Context): File? {
        val dir = context.getExternalFilesDir(VAULT_DIR_NAME) ?: return null
        return try {
            if (!dir.exists() && !dir.mkdirs()) return null
            if (!dir.isDirectory || !dir.canWrite()) return null
            val nomedia = File(dir, ".nomedia")
            if (!nomedia.exists()) {
                try {
                    nomedia.createNewFile()
                } catch (e: Exception) {
                    // best effort
                }
            }
            dir
        } catch (e: Exception) {
            null
        }
    }

    fun loadItems(context: Context): List<VaultItem> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return emptyList()
        val items = mutableListOf<VaultItem>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(
                    VaultItem(
                        o.optString("originalPath"),
                        o.optString("vaultName"),
                        o.optString("displayName"),
                        o.optLong("durationMs"),
                        o.optLong("sizeBytes"),
                        o.optLong("dateAdded")
                    )
                )
            }
        } catch (e: Exception) {
            // corrupted prefs -> treat as empty
        }
        return items
    }

    fun saveItems(context: Context, items: List<VaultItem>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject().apply {
                    put("originalPath", it.originalPath)
                    put("vaultName", it.vaultName)
                    put("displayName", it.displayName)
                    put("durationMs", it.durationMs)
                    put("sizeBytes", it.sizeBytes)
                    put("dateAdded", it.dateAdded)
                }
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun addItem(context: Context, item: VaultItem) {
        val items = loadItems(context).toMutableList()
        items.removeAll { it.originalPath == item.originalPath }
        items.add(0, item)
        saveItems(context, items)
    }

    fun removeItem(context: Context, originalPath: String) {
        val items = loadItems(context).filter { it.originalPath != originalPath }
        saveItems(context, items)
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_ITEMS).apply()
    }

    /**
     * Copy the video file into the vault dir. Returns a PendingMove, or null on
     * failure (source missing, vault dir unusable, copy/verify failed). The
     * original is untouched here; call [deleteOriginal] + [completeMove] next.
     * Runs on the caller's thread (do it off the main thread for large files).
     */
    fun copyToVault(context: Context, video: VideoItem): PendingMove? {
        val src = File(video.path)
        if (!src.exists() || !src.isFile) return null
        val dir = vaultDir(context) ?: return null
        var vaultName = src.name
        var dest = File(dir, vaultName)
        var n = 1
        while (dest.exists()) {
            val base = src.nameWithoutExtension
            val ext = src.extension
            vaultName = if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"
            dest = File(dir, vaultName)
            n++
        }
        try {
            src.inputStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            }
        } catch (e: Exception) {
            dest.delete()
            return null
        }
        if (!dest.exists() || dest.length() != src.length()) {
            dest.delete()
            return null
        }
        return PendingMove(video, dest, vaultName)
    }

    /**
     * Delete the original MediaStore entry (and, best effort, the file itself).
     * May throw RecoverableSecurityException when the system needs the user's
     * consent to delete a file the app does not own — the caller must catch it
     * and launch the provided IntentSender, then retry.
     */
    fun deleteOriginal(context: Context, pending: PendingMove) {
        context.contentResolver.delete(Uri.parse(pending.video.uri), null, null)
        try {
            val f = File(pending.video.path)
            if (f.exists()) f.delete()
        } catch (e: Exception) {
            // best effort; MediaStore delete normally removes the file already
        }
    }

    /** Record the vault item after the original was removed. */
    fun completeMove(context: Context, pending: PendingMove) {
        val v = pending.video
        addItem(
            context,
            VaultItem(v.path, pending.vaultName, v.name, v.durationMs, v.sizeBytes, v.dateAdded)
        )
    }

    /** Discard a pending move (delete the vault copy). */
    fun cancelMove(pending: PendingMove) {
        try {
            pending.vaultFile.delete()
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * Move one vault file back to its original location and re-index it via
     * MediaStore (insert with IS_PENDING, write content, clear the flag).
     * Returns true on success. The vault record is removed on success.
     */
    fun restoreItem(context: Context, item: VaultItem): Boolean {
        val dir = vaultDir(context) ?: return false
        val src = File(dir, item.vaultName)
        if (!src.exists() || !src.isFile) return false

        val destFile = File(item.originalPath)
        val parent = destFile.parentFile
        val targetDir: File
        val targetName = destFile.name
        if (parent != null) {
            try {
                if (!parent.exists()) parent.mkdirs()
            } catch (e: Exception) {
                // ignore
            }
            targetDir = if (parent.exists() && parent.isDirectory) {
                parent
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            }
        } else {
            targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        }

        val relPath = relativePathFor(targetDir)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, targetName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeTypeOf(targetName))
            put(MediaStore.Video.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = try {
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            null
        } ?: return false
        try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { input ->
                    input.copyTo(out, DEFAULT_BUFFER_SIZE)
                }
            } ?: run {
                try {
                    resolver.delete(uri, null, null)
                } catch (ignored: Exception) {
                }
                return false
            }
            val clear = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(uri, clear, null, null)
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (ignored: Exception) {
            }
            return false
        }
        try {
            src.delete()
        } catch (e: Exception) {
            // ignore
        }
        removeItem(context, item.originalPath)
        return true
    }

    /** Restore every vault item; returns the items that failed. */
    fun restoreAll(context: Context): List<VaultItem> {
        val failed = mutableListOf<VaultItem>()
        for (item in loadItems(context)) {
            if (!restoreItem(context, item)) failed.add(item)
        }
        return failed
    }

    private fun relativePathFor(dir: File): String {
        return try {
            val root = Environment.getExternalStorageDirectory().absolutePath
            val abs = dir.absolutePath
            var rel = if (abs.startsWith(root)) abs.substring(root.length).trimStart('/') else ""
            if (rel.isEmpty()) rel = Environment.DIRECTORY_MOVIES
            if (!rel.endsWith("/")) rel += "/"
            rel
        } catch (e: Exception) {
            Environment.DIRECTORY_MOVIES + "/"
        }
    }

    private fun mimeTypeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext.isEmpty()) {
            "video/mp4"
        } else {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/mp4"
        }
    }
}
