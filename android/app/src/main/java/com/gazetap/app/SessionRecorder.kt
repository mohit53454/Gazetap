package com.gazetap.app

import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.Locale

class SessionRecorder {
    private var writer: BufferedWriter? = null
    private var destination: SessionDestination? = null
    private var frameIndex: Long = 0
    @Volatile private var recording = false

    val currentPath: String? get() = destination?.displayPath

    /** Starts writing to [dest] (normally a CSV in Download/GazeTap). */
    @Synchronized
    fun start(dest: SessionDestination) {
        stop(completed = false)
        val w = BufferedWriter(OutputStreamWriter(dest.outputStream, Charsets.UTF_8))
        writer = w
        destination = dest
        frameIndex = 0
        recording = true
        try {
            w.write("frame_index,timestamp_ms,inference_ms,image_w,image_h,yaw,pitch,roll,blendshape_count,blendshapes,landmark_count,landmarks\n")
            w.flush()
        } catch (e: Exception) {
            recording = false
            try { w.close() } catch (_: Exception) {}
            writer = null
            destination = null
            dest.complete(false)
            throw e
        }
    }

    @Synchronized
    fun append(result: FaceLandmarkerResult, pose: HeadPose, inferenceMs: Long, imageH: Int, imageW: Int) {
        val w = writer ?: return
        if (!recording) return
        try {
            val ts = result.timestampMs()
            val blendshapes = result.faceBlendshapes()
            val sbBlend = StringBuilder()
            var bCount = 0
            if (blendshapes.isPresent && blendshapes.get().isNotEmpty()) {
                val cats = blendshapes.get()[0]
                bCount = cats.size
                cats.forEachIndexed { i, c ->
                    if (i > 0) sbBlend.append('|')
                    sbBlend.append(c.categoryName()).append(':')
                        .append(String.format(Locale.US, "%.4f", c.score()))
                }
            }
            val sbLm = StringBuilder()
            var lmCount = 0
            val faces = result.faceLandmarks()
            if (faces.isNotEmpty()) {
                val lms = faces[0]
                lmCount = lms.size
                lms.forEachIndexed { i, lm ->
                    if (i > 0) sbLm.append('|')
                    sbLm.append(String.format(Locale.US, "%.5f,%.5f,%.5f", lm.x(), lm.y(), lm.z()))
                }
            }
            val line = buildString {
                append(frameIndex).append(',')
                append(ts).append(',')
                append(inferenceMs).append(',')
                append(imageW).append(',')
                append(imageH).append(',')
                append(String.format(Locale.US, "%.4f", pose.yaw)).append(',')
                append(String.format(Locale.US, "%.4f", pose.pitch)).append(',')
                append(String.format(Locale.US, "%.4f", pose.roll)).append(',')
                append(bCount).append(',')
                append('"').append(sbBlend.toString().replace("\"", "'")).append('"').append(',')
                append(lmCount).append(',')
                append('"').append(sbLm.toString().replace("\"", "'")).append('"')
            }
            w.write(line)
            w.newLine()
            frameIndex++
            if (frameIndex % 30 == 0L) w.flush()
        } catch (_: Exception) {
            // best-effort recording; avoid crashing pipeline
        }
    }

    /**
     * Stops writing.
     * @param completed true when the file should be kept and published to the Downloads folder
     * @return the display path of the saved file, or null when nothing was being recorded
     */
    @Synchronized
    fun stop(completed: Boolean = true): String? {
        val wasRecording = recording
        recording = false
        try {
            writer?.flush()
        } catch (_: Exception) {
        }
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        val dest = destination
        destination = null
        if (dest != null) {
            dest.complete(completed)
        }
        return if (wasRecording) dest?.displayPath else null
    }

    fun isRecording(): Boolean = recording
}
