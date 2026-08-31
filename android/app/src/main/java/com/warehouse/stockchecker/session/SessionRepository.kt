package com.warehouse.stockchecker.session

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the "saved sessions" surface.
 *
 * Storage strategy:
 *
 *  * **Q+ (API 29+)**: write JSON to `MediaStore.Downloads/StockCheck/` via scoped storage. No
 *    permission needed. The URI returned from `MediaStore` is used for the share/open chooser.
 *  * **Pre-Q (API 24-28)**: write JSON to the legacy public `Downloads/StockCheck/` directory
 *    (requires `WRITE_EXTERNAL_STORAGE`). Expose the file through FileProvider so external apps
 *    get a temporary read grant.
 *
 * On top of either storage layer, the repository keeps a small **in-app index** of sessions in
 * [android.content.SharedPreferences]. This is the data source for the Sessions screen — the
 * filesystem is the source of truth, the index is a fast, offline-friendly mirror of it. If a
 * session's file ever disappears (the user wipes Downloads), it just stops showing up after
 * the next read of the directory.
 *
 * Thread safety: all public methods are safe to call from the main thread. The actual file IO
 * happens synchronously but is small (kilobytes per session) and rare (user-initiated).
 */
class SessionRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Saves a session. The caller supplies the anchors from the current capture; the
     * repository decides where they go.
     *
     * @return a [SessionIndexEntry] describing the saved session on success, or null if the
     *   export was empty (no anchors to save).
     */
    fun save(
        anchors: List<WorldAnchorSnapshot>,
        frameWidth: Int,
        frameHeight: Int,
        inferenceTimeMs: Long,
        customName: String?
    ): SaveResult {
        if (anchors.isEmpty()) return SaveResult.NoAnchors

        val createdAt = System.currentTimeMillis()
        val displayName = customName?.takeIf { it.isNotBlank() }
            ?: defaultName(createdAt)
        val safeStem = sanitiseFileStem(displayName).ifBlank { defaultStem(createdAt) }
        val appVersion = readAppVersion()
        val payload = SessionExport.from(
            anchors = anchors.map { it.toTracked() },
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            inferenceTimeMs = inferenceTimeMs,
            createdAtMillis = createdAt,
            appVersion = appVersion
        )
        val json = payload.toJson().toString(2)

        val (relativePath, contentUri, fileForPreQ) = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                writeScoped(payload, safeStem, createdAt, json)
            else -> writeLegacy(safeStem, json)
        }

        val entry = SessionIndexEntry(
            id = safeStem,
            displayName = displayName,
            createdAtMillis = createdAt,
            anchorCount = anchors.size,
            inferenceTimeMs = inferenceTimeMs,
            relativePath = relativePath
        )
        appendToIndex(entry)
        return SaveResult.Saved(entry, contentUri, fileForPreQ)
    }

    /** Reads the persisted index; oldest entries first. */
    fun index(): List<SessionIndexEntry> {
        val raw = prefs.getString(KEY_INDEX, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SessionIndexEntry(
                    id = o.getString("id"),
                    displayName = o.getString("displayName"),
                    createdAtMillis = o.getLong("createdAtMillis"),
                    anchorCount = o.getInt("anchorCount"),
                    inferenceTimeMs = o.getLong("inferenceTimeMs"),
                    relativePath = o.getString("relativePath")
                )
            }
        }.getOrElse { error ->
            Log.w(TAG, "Index unreadable; resetting", error)
            prefs.edit().remove(KEY_INDEX).apply()
            emptyList()
        }
    }

    fun delete(entry: SessionIndexEntry) {
        val current = index().toMutableList()
        val removed = current.removeAll { it.id == entry.id }
        if (!removed) return
        writeIndex(current)
        // Best-effort: also delete the underlying file (Q+ uses MediaStore; pre-Q uses File).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            deleteScoped(entry)
        } else {
            deleteLegacy(entry)
        }
    }

    /**
     * Builds the URI an external app should be given when the user picks "Open" or "Share".
     * On Q+ this is the MediaStore URI we already hold. On pre-Q it is a FileProvider URI
     * backed by the legacy file.
     */
    fun uriFor(entry: SessionIndexEntry, preQFile: File?): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStore.Downloads.EXTERNAL_CONTENT_URI
                .buildUpon()
                .appendPath(entry.relativePath.substringAfterLast('/', "").trimEnd())
                .build()
        }
        val file = preQFile ?: legacyFileFor(entry)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    // -- storage backends --

    private data class WriteResult(
        val relativePath: String,
        val contentUri: Uri,
        val preQFile: File? = null
    )

    private fun writeScoped(
        payload: SessionExport,
        stem: String,
        createdAt: Long,
        json: String
    ): WriteResult {
        val resolver = context.contentResolver
        val relativeDir = "StockCheck/$stem.json".let { path ->
            // MediaStore EXTERNAL_CONTENT_URI inserts the file name as the last segment;
            // we want the file itself, not a directory tree.
            // Relative path used in the index: "StockCheck/<filename>".
            val fileName = "$stem-${TIMESTAMP_FILE.format(Date(createdAt))}.json"
            "StockCheck/$fileName" to fileName
        }
        val (relativePath, fileName) = relativeDir
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StockCheck")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore refused to insert a Downloads entry")
        try {
            resolver.openOutputStream(uri)?.use { os: OutputStream ->
                os.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("MediaStore URI has no output stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return WriteResult(relativePath, uri)
        } catch (t: Throwable) {
            // Surface the failure and leave no orphan pending row.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun writeLegacy(stem: String, json: String): WriteResult {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "StockCheck"
        )
        if (!dir.exists() && !dir.mkdirs()) {
            error("Could not create $dir")
        }
        val fileName = "$stem-${TIMESTAMP_FILE.format(Date())}.json"
        val file = File(dir, fileName)
        FileOutputStream(file).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return WriteResult(
            relativePath = "StockCheck/$fileName",
            contentUri = Uri.EMPTY,
            preQFile = file
        )
    }

    private fun deleteScoped(entry: SessionIndexEntry) {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?"
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/${entry.relativePath.substringBeforeLast('/')}/"
        val displayName = entry.relativePath.substringAfterLast('/')
        try {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(relative, displayName),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val rowUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI.buildUpon()
                        .appendPath(id.toString()).build()
                    runCatching { resolver.delete(rowUri, null, null) }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Best-effort delete failed for ${entry.id}", t)
        }
    }

    private fun deleteLegacy(entry: SessionIndexEntry) {
        runCatching { legacyFileFor(entry).delete() }
    }

    private fun legacyFileFor(entry: SessionIndexEntry): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            entry.relativePath
        )

    // -- index helpers --

    private fun appendToIndex(entry: SessionIndexEntry) {
        val current = index().toMutableList()
        // Replace any previous entry with the same id, then append at the end.
        current.removeAll { it.id == entry.id }
        current.add(entry)
        writeIndex(current)
    }

    private fun writeIndex(entries: List<SessionIndexEntry>) {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("displayName", entry.displayName)
                    put("createdAtMillis", entry.createdAtMillis)
                    put("anchorCount", entry.anchorCount)
                    put("inferenceTimeMs", entry.inferenceTimeMs)
                    put("relativePath", entry.relativePath)
                }
            )
        }
        prefs.edit().putString(KEY_INDEX, arr.toString()).apply()
    }

    private fun readAppVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "unknown"
    }.getOrDefault("unknown")

    sealed class SaveResult {
        data class Saved(
            val entry: SessionIndexEntry,
            val contentUri: Uri,
            val preQFile: File? = null
        ) : SaveResult()

        data object NoAnchors : SaveResult()
    }

    companion object {
        private const val TAG = "SessionRepository"
        private const val PREFS_NAME = "stockcheck_sessions"
        private const val KEY_INDEX = "index"

        private val TIMESTAMP_FILE = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        private val TIMESTAMP_HUMAN = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        fun defaultName(createdAt: Long): String =
            "Session ${TIMESTAMP_HUMAN.format(Date(createdAt))}"

        private fun defaultStem(createdAt: Long): String =
            "session_${TIMESTAMP_FILE.format(Date(createdAt))}"

        private fun sanitiseFileStem(raw: String): String =
            raw.trim()
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), "_")
                .take(80)
    }
}

/**
 * Lightweight snapshot of an anchor, used as the public input to [SessionRepository.save].
 *
 * Kept separate from [com.warehouse.stockchecker.tracking.WorldTrackedDetection] so callers can build
 * it from either the live tracker or a replay buffer without leaking the whole tracker into
 * the session layer.
 */
data class WorldAnchorSnapshot(
    val trackId: Int,
    val label: String,
    val classId: Int,
    val score: Float,
    val hits: Int,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float
) {
    fun toTracked() = com.warehouse.stockchecker.tracking.WorldTrackedDetection(
        trackId = trackId,
        detection = com.warehouse.stockchecker.ml.Detection(
            classId = classId,
            label = label,
            score = score,
            box = com.warehouse.stockchecker.ml.BoxF(boxLeft, boxTop, boxRight, boxBottom)
        ),
        box = com.warehouse.stockchecker.ml.BoxF(boxLeft, boxTop, boxRight, boxBottom),
        capture = com.warehouse.stockchecker.camera.CameraOrientation.UNKNOWN,
        hits = hits,
        isLive = true,
        millisSinceSeen = 0L
    )
}
