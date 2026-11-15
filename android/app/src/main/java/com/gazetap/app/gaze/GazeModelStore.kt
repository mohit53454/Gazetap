package com.gazetap.app.gaze

import java.io.File

/**
 * Persists the fitted gaze model, plus the analysis resolution it was calibrated at.
 *
 * Why the resolution matters: the iris features are normalised by eye size but the landmarks
 * themselves shift with input resolution, so a model fitted at 640x480 is not valid at 320x240.
 * Storing the resolution lets the app refuse to use a mismatched model instead of silently pointing
 * in the wrong place.
 *
 * Why ONE file, written atomically: the model and its metadata used to be two separate writes. A
 * process killed between them (swiping the app out of recents is exactly that) left a model file the
 * loader could not trust, so the next start asked for a whole new 25-point calibration. Metadata now
 * lives inside the same JSON, which is written to a temp file and renamed into place, and the
 * previous good copy is kept as a backup.
 */
class GazeModelStore(private val file: File) {

    data class Stored(
        val model: RidgePolynomialModel,
        /** 0 when the file predates resolution tracking. */
        val imageWidth: Int,
        val imageHeight: Int,
    )

    /** Why the last [load] refused the saved file, so the diagnostics report can say it. */
    var lastLoadError: String? = null
        private set

    private val metaFile: File get() = File("${file.path}.meta")
    private val backupFile: File get() = File("${file.path}.bak")

    fun save(model: RidgePolynomialModel, imageWidth: Int, imageHeight: Int) {
        file.parentFile?.mkdirs()
        val json = model.toJson().trimEnd()
        require(json.endsWith("}")) { "model json must end with }" }
        val enriched = json.dropLast(1) +
            ",\"imageWidth\":$imageWidth,\"imageHeight\":$imageHeight," +
            "\"modelVersion\":$MODEL_VERSION}"
        val tmp = File(file.path + ".tmp")
        tmp.writeText(enriched)
        if (file.exists()) {
            runCatching { file.copyTo(backupFile, overwrite = true) }
        }
        if (!tmp.renameTo(file)) {
            // Some filesystems refuse a cross-directory rename; fall back to a copy.
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        if (metaFile.exists()) metaFile.delete()
    }

    fun load(): Stored? {
        lastLoadError = null
        return read(file) ?: read(backupFile)
    }

    private fun read(source: File): Stored? {
        if (!source.exists()) {
            if (lastLoadError == null) lastLoadError = "no saved calibration"
            return null
        }
        return try {
            val text = source.readText()
            val model = RidgePolynomialModel.fromJson(text)
            if (!model.isFitted()) {
                lastLoadError = "saved weights do not match the model basis"
                return null
            }
            var version = intAfter(text, "modelVersion")
            var w = intAfter(text, "imageWidth")
            var h = intAfter(text, "imageHeight")
            // 0.23 and earlier kept the metadata in a side file: still accepted on upgrade.
            if (version == 0 && metaFile.exists()) {
                val meta = metaFile.readText()
                version = intAfter(meta, "modelVersion")
                w = intAfter(meta, "imageWidth")
                h = intAfter(meta, "imageHeight")
            }
            // A model fitted against an older feature basis points somewhere else entirely, and
            // silently using it is worse than asking for one recalibration.
            if (version != MODEL_VERSION) {
                lastLoadError =
                    "saved from an older feature basis (version $version, expected $MODEL_VERSION)"
                return null
            }
            Stored(model, w, h)
        } catch (e: Exception) {
            lastLoadError = "saved calibration is unreadable: ${e.message}"
            null
        }
    }

    /** True when a model (or its backup) is on disk, even if it could not be loaded. */
    fun hasSavedModel(): Boolean = file.exists() || backupFile.exists()

    /** True when [model] can be used at the given analysis resolution. */
    fun matchesResolution(stored: Stored, imageWidth: Int, imageHeight: Int): Boolean {
        if (stored.imageWidth == 0) return true // legacy model: allow, the user can recalibrate
        return stored.imageWidth == imageWidth && stored.imageHeight == imageHeight
    }

    fun exists(): Boolean = file.exists()

    fun delete() {
        if (file.exists()) file.delete()
        if (metaFile.exists()) metaFile.delete()
        if (backupFile.exists()) backupFile.delete()
        File(file.path + ".tmp").delete()
    }

    private fun intAfter(json: String, key: String): Int {
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return 0
        val colon = json.indexOf(':', idx)
        if (colon < 0) return 0
        return json.substring(colon + 1)
            .takeWhile { it.isDigit() || it == ' ' }
            .trim()
            .toIntOrNull() ?: 0
    }

    companion object {
        const val FILE_NAME = "gaze_model.json"

        /** Bump when the meaning of the input vector changes; older models must be refitted. */
        private const val MODEL_VERSION = 2

        fun forContext(dir: File): GazeModelStore = GazeModelStore(File(dir, FILE_NAME))
    }
}
