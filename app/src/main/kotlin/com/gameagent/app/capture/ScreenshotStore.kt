package com.gameagent.app.capture

import android.content.Context
import android.graphics.Bitmap
import com.gameagent.core.diagnostics.DiagnosticEventSink
import com.gameagent.core.diagnostics.EventCategory
import com.gameagent.core.perception.ImageFrame
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional on-device screenshot storage.
 *
 * Off by default and never enabled implicitly: frames stay in memory unless the user turns this on
 * in Settings. When enabled, storage is deliberately bounded in three ways - a minimum interval
 * between saves, a maximum number of retained files, and a total byte budget - so a long session
 * cannot fill the phone's storage. Nothing is ever uploaded.
 */
class ScreenshotStore(
    private val context: Context,
    private val events: DiagnosticEventSink,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gameagent-screenshots").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private var lastSavedAtMs = 0L

    val directory: File get() = File(context.filesDir, DIRECTORY_NAME)

    fun isEnabled(settings: com.gameagent.app.data.AppSettings): Boolean = settings.storeScreenshots

    /**
     * Saves a frame if the user enabled storage and the interval has elapsed.
     *
     * Runs on a dedicated single thread so PNG encoding never blocks the capture callback, and drops
     * the request entirely if a save is already in progress.
     */
    fun maybeSave(frame: ImageFrame, settings: com.gameagent.app.data.AppSettings, nowMs: Long) {
        if (!settings.storeScreenshots) return
        if (nowMs - lastSavedAtMs < settings.screenshotIntervalMs) return
        if (!busy.compareAndSet(false, true)) return
        lastSavedAtMs = nowMs

        // Copy the pixels: the capture engine recycles its array as soon as the frame is consumed.
        val copy = frame.argb.copyOf(frame.width * frame.height)
        val width = frame.width
        val height = frame.height
        val timestamp = frame.timestampMs

        executor.execute {
            try {
                val target = File(ensureDirectory(), "frame_$timestamp.png")
                val bitmap = Bitmap.createBitmap(copy, width, height, Bitmap.Config.ARGB_8888)
                target.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
                bitmap.recycle()
                events.info(EventCategory.CAPTURE, "screenshot stored", mapOf("file" to target.name))
                prune()
            } catch (t: Throwable) {
                events.warn(EventCategory.CAPTURE, "could not store a screenshot", mapOf("error" to t.toString()))
            } finally {
                busy.set(false)
            }
        }
    }

    fun list(): List<File> = directory.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun count(): Int = list().size

    fun totalBytes(): Long = list().sumOf { it.length() }

    /** @return number of files removed. */
    fun clear(): Int {
        val files = list()
        files.forEach { it.delete() }
        events.info(EventCategory.CAPTURE, "stored screenshots cleared", mapOf("removed" to files.size.toString()))
        return files.size
    }

    private fun ensureDirectory(): File {
        val dir = directory
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun prune() {
        val files = list()
        if (files.size > MAX_FILES) {
            files.drop(MAX_FILES).forEach { it.delete() }
        }
        var total = list().sumOf { it.length() }
        val remaining = list().toMutableList()
        while (total > MAX_TOTAL_BYTES && remaining.isNotEmpty()) {
            val oldest = remaining.removeAt(remaining.lastIndex)
            total -= oldest.length()
            oldest.delete()
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    companion object {
        const val DIRECTORY_NAME = "screenshots"
        const val MAX_FILES = 20
        const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L
    }
}
