package com.gameagent.app.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.compose.ui.graphics.asImageBitmap
import com.gameagent.app.data.AppSettings
import com.gameagent.app.image.ImageBufferLayout
import com.gameagent.core.diagnostics.CaptureMetrics
import com.gameagent.core.diagnostics.DiagnosticEventSink
import com.gameagent.core.diagnostics.EventCategory
import com.gameagent.core.diagnostics.LatencyTracker
import com.gameagent.core.geometry.DisplayRotation
import com.gameagent.core.geometry.ScreenGeometry
import com.gameagent.core.perception.ImageFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Screen capture via MediaProjection + VirtualDisplay + ImageReader.
 *
 * Platform rules this implementation follows deliberately (they are the reason for most of the
 * structure here, and they were verified against the current Android documentation - see
 * docs/INPUT_MECHANISMS.md):
 *
 *  1. **Consent is never bypassed.** A projection can only be created from the result of the system
 *     consent dialog; without it [start] fails and says so.
 *  2. **The foreground service is promoted first.** From API 34 a projection is refused with
 *     `SecurityException` unless a foreground service of type `mediaProjection` is already running,
 *     so [ScreenCaptureService] calls `startForeground` before calling [start].
 *  3. **One consent token, one projection, one VirtualDisplay.** From API 34 a second
 *     `createVirtualDisplay` on the same projection throws, so a resolution or rotation change
 *     *resizes* the existing display and swaps its surface ([applyResize]) instead of recreating it.
 *  4. **Everything is released.** `MediaProjection.Callback.onStop` fires when the user revokes
 *     sharing (or the device locks on Android 15+), and that path releases the reader, the virtual
 *     display and the callbacks. Resource creation is also rolled back on any failure.
 *  5. **No frame is produced that nobody asked for.** Frames are throttled to the configured
 *     interval, the reader keeps at most two images, and one reusable byte array plus one reusable
 *     int array are recycled for every frame, so a long session does not churn the heap.
 */
class MediaProjectionCaptureEngine(
    private val context: Context,
    private val settingsProvider: () -> AppSettings,
    private val events: DiagnosticEventSink,
    private val previewBuilder: (ImageFrame) -> PreviewImage? = { null },
) {
    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = mutableState.asStateFlow()

    private val mutableMetrics = MutableStateFlow(CaptureMetrics())
    val metrics: StateFlow<CaptureMetrics> = mutableMetrics.asStateFlow()

    private val mutablePreview = MutableStateFlow<PreviewImage?>(null)
    val preview: StateFlow<PreviewImage?> = mutablePreview.asStateFlow()

    private val mutableFrames = MutableSharedFlow<FrameBundle>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<FrameBundle> = mutableFrames.asSharedFlow()

    /** Invoked when capture ends by itself (projection revoked, device locked, error). */
    var onCaptureEnded: ((String) -> Unit)? = null

    private val thread = HandlerThread("gameagent-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    private var captureSize = CaptureSize(0, 0)
    private var displayWidth = 0
    private var displayHeight = 0
    private var rotationDegrees = 0

    private var reusableBytes = ByteArray(0)

    /**
     * A small pool of pixel arrays.
     *
     * Frames are handed to the pipeline together with a releaser that returns the array here, so a
     * long capture session allocates at most [PIXEL_POOL_SIZE] arrays instead of one per frame. The
     * pool only ever grows when a burst of frames is in flight; steady state reuses the same arrays
     * and the oldest array is dropped instead of being queued for ever.
     */
    private val pixelPool = ArrayDeque<IntArray>(PIXEL_POOL_SIZE)
    private val pixelPoolLock = Any()

    private var frameId = 0L
    private var lastFrameAtMs = 0L
    private var lastPreviewAtMs = 0L
    private var framesCaptured = 0L
    private var framesDropped = 0L
    private val frameIntervals = LatencyTracker(60)
    private var tearingDown = false

    // ------------------------------------------------------------------ start

    /**
     * @param resultCode result code from the system consent dialog
     * @param data       the consent `Intent` returned by the system
     * @return true when capture started
     */
    fun start(resultCode: Int, data: Intent?): Boolean {
        if (projection != null) {
            events.warn(EventCategory.CAPTURE, "capture is already running; ignoring start")
            return false
        }
        if (data == null) {
            fail("no screen-capture consent data was provided")
            return false
        }

        mutableState.value = CaptureState.Starting
        val info = currentDisplayInfo()
        displayWidth = info.width
        displayHeight = info.height
        rotationDegrees = info.rotationDegrees
        val settings = settingsProvider()
        captureSize = CaptureSizeCalculator.compute(displayWidth, displayHeight, settings.effectiveCaptureScale, info.maxPixels)
        events.info(
            EventCategory.CAPTURE,
            "creating capture surface",
            mapOf(
                "display" to "${displayWidth}x$displayHeight",
                "capture" to "${captureSize.width}x${captureSize.height}",
                "scale" to settings.effectiveCaptureScale.toString(),
            ),
        )

        val manager = context.getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            fail("MediaProjectionManager is unavailable on this device")
            return false
        }

        val created = try {
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            // The documented failure for API 34+ when the mediaProjection foreground service is not
            // running yet, or when a consent token is reused, is SecurityException.
            fail("could not create the projection: ${t.message ?: t::class.java.simpleName}")
            return false
        }
        if (created == null) {
            fail("the system returned a null projection (consent was probably revoked)")
            return false
        }
        projection = created

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                events.warn(EventCategory.CAPTURE, "the system stopped the screen projection")
                handleExternalStop("the screen projection was stopped by the system")
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                // We mirror the whole display at the display's dimensions, so the captured buffer
                // always represents the full screen and normalised coordinates stay display-relative.
                // A differing content size means the user shared a single app; report it instead of
                // silently re-scaling, which would invalidate every derived touch coordinate.
                if (width != displayWidth || height != displayHeight) {
                    events.warn(
                        EventCategory.CAPTURE,
                        "captured content size differs from the display; coordinates remain display-relative",
                        mapOf("content" to "${width}x$height", "display" to "${displayWidth}x$displayHeight"),
                    )
                }
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                events.debug(EventCategory.CAPTURE, "captured content visibility changed", mapOf("visible" to isVisible.toString()))
            }
        }
        projectionCallback = callback
        created.registerCallback(callback, mainHandler)

        return try {
            createVirtualDisplay(created)
            registerDisplayListener()
            mutableState.value = CaptureState.Running(
                captureWidth = captureSize.width,
                captureHeight = captureSize.height,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                rotationDegrees = rotationDegrees,
            )
            events.info(EventCategory.CAPTURE, "screen capture started")
            true
        } catch (t: Throwable) {
            fail("could not create the capture surface: ${t.message ?: t::class.java.simpleName}")
            releaseResources()
            false
        }
    }

    private fun createVirtualDisplay(mediaProjection: MediaProjection) {
        val reader = newReader(captureSize)
        imageReader = reader
        virtualDisplay = mediaProjection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            captureSize.width,
            captureSize.height,
            context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )
    }

    private fun newReader(size: CaptureSize): ImageReader =
        ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, MAX_ACQUIRED_IMAGES).also { reader ->
            reader.setOnImageAvailableListener({ available -> onImageAvailable(available) }, handler)
        }

    // ----------------------------------------------------------------- frames

    private fun onImageAvailable(reader: ImageReader) {
        val image: Image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            framesDropped++
            events.warn(EventCategory.CAPTURE, "could not acquire a frame", mapOf("error" to t.toString()))
            null
        } ?: return

        try {
            val now = SystemClock.elapsedRealtime()
            val settings = settingsProvider()

            if (lastFrameAtMs != 0L && now - lastFrameAtMs < settings.effectiveCaptureIntervalMs) {
                framesDropped++
                return
            }
            if (lastFrameAtMs != 0L) frameIntervals.record(now - lastFrameAtMs)
            lastFrameAtMs = now

            val frame = convert(image, settings) ?: run {
                framesDropped++
                return
            }
            framesCaptured++
            frameId = frame.frameId
            val geometry = ScreenGeometry(
                captureWidth = captureSize.width,
                captureHeight = captureSize.height,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                rotation = DisplayRotation.fromDegrees(rotationDegrees),
                frameId = frame.frameId,
                capturedAtMs = frame.timestampMs,
            )
            if (!mutableFrames.tryEmit(FrameBundle(frame, geometry))) {
                framesDropped++
            }
            maybePublishPreview(frame, settings, now)
            publishMetrics()
        } finally {
            // Image objects are pooled by the reader; failing to close one starves it permanently.
            image.close()
        }
    }

    private fun convert(image: Image, settings: AppSettings): ImageFrame? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val needed = ImageBufferLayout.requiredBufferSize(width, height, rowStride, pixelStride)

        if (reusableBytes.size < needed) reusableBytes = ByteArray(needed)
        val buffer = plane.buffer
        buffer.rewind()
        val toRead = minOf(needed, buffer.remaining(), reusableBytes.size)
        buffer.get(reusableBytes, 0, toRead)

        val pixelCount = width * height
        val pixels = borrowPixels(pixelCount)
        val written = ImageBufferLayout.repackRgba(
            source = reusableBytes,
            sourceWidth = width,
            sourceHeight = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            cropLeft = 0,
            cropTop = 0,
            outWidth = width,
            outHeight = height,
            destination = pixels,
        )
        if (written == 0) {
            recyclePixels(pixels)
            return null
        }

        return ImageFrame.pooled(
            frameId = frameId + 1,
            timestampMs = System.currentTimeMillis(),
            width = width,
            height = height,
            argb = pixels,
            releaser = { recyclePixels(pixels) },
        )
    }

    /**
     * Takes a pixel buffer from the pool, or allocates one.
     *
     * Buffers are matched by *capacity*, not by exact length: a pooled buffer that is larger than
     * the current frame is still usable, because [ImageFrame] documents that only the first
     * `width * height` entries are meaningful. Smaller buffers are discarded rather than grown,
     * which keeps every returned array immutable in size and therefore safe to recycle later.
     */
    private fun borrowPixels(count: Int): IntArray = synchronized(pixelPoolLock) {
        var candidate = pixelPool.removeFirstOrNull()
        while (candidate != null && candidate.size < count) {
            candidate = pixelPool.removeFirstOrNull()
        }
        candidate ?: IntArray(count)
    }

    private fun recyclePixels(pixels: IntArray) = synchronized(pixelPoolLock) {
        if (pixelPool.size < PIXEL_POOL_SIZE) pixelPool.addLast(pixels)
    }

    private fun maybePublishPreview(frame: ImageFrame, settings: AppSettings, nowMs: Long) {
        if (!settings.previewEnabled) return
        if (nowMs - lastPreviewAtMs < settings.previewIntervalMs) return
        lastPreviewAtMs = nowMs
        previewBuilder(frame)?.let { mutablePreview.value = it }
    }

    private fun publishMetrics() {
        mutableMetrics.value = mutableMetrics.value.copy(
            running = mutableState.value.isLive,
            framesCaptured = framesCaptured,
            framesDropped = framesDropped,
            bytesPerFrame = captureSize.width * captureSize.height * ImageBufferLayout.RGBA_PIXEL_BYTES,
            captureWidth = captureSize.width,
            captureHeight = captureSize.height,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            lastFrameAtMs = if (lastFrameAtMs == 0L) 0L else System.currentTimeMillis(),
            framesPerSecond = frameIntervals.meanMs().takeIf { it > 0.0 }?.let { 1000.0 / it } ?: 0.0,
            frameInterval = frameIntervals.summary(),
        )
    }

    // ------------------------------------------------------- resize / rotation

    private fun registerDisplayListener() {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return
        displayManager = manager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                handler.post { applyDisplayChange() }
            }
        }
        displayListener = listener
        manager.registerDisplayListener(listener, handler)
    }

    private fun applyDisplayChange() {
        if (projection == null) return
        val info = currentDisplayInfo()
        val settings = settingsProvider()
        val size = CaptureSizeCalculator.compute(info.width, info.height, settings.effectiveCaptureScale, info.maxPixels)
        val rotationChanged = info.rotationDegrees != rotationDegrees
        val sizeChanged = info.width != displayWidth || info.height != displayHeight

        displayWidth = info.width
        displayHeight = info.height
        rotationDegrees = info.rotationDegrees

        if (rotationChanged || sizeChanged) {
            events.info(
                EventCategory.CAPTURE,
                "display geometry changed",
                mapOf(
                    "display" to "${info.width}x${info.height}",
                    "rotation" to info.rotationDegrees.toString(),
                    "capture" to "${size.width}x${size.height}",
                    "captureSizeChanged" to (size != captureSize).toString(),
                ),
            )
        }

        // Always refresh the published geometry so the mapper sees the new display dimensions, even
        // when the capture surface size can stay the same.
        if (size != captureSize) {
            applyResize(size)
        }
        val current = mutableState.value
        if (current is CaptureState.Running) {
            mutableState.value = current.copy(
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                rotationDegrees = rotationDegrees,
            )
        }
    }

    /**
     * Resizes the existing VirtualDisplay and swaps in a new ImageReader.
     *
     * This must NOT call `createVirtualDisplay` again: from API 34 the platform refuses a second
     * virtual display on the same projection, and the user-facing symptom is a hard
     * `SecurityException` that kills capture.
     */
    private fun applyResize(size: CaptureSize) {
        val display = virtualDisplay ?: return
        val previousReader = imageReader
        val replacement = try {
            newReader(size)
        } catch (t: Throwable) {
            events.error(EventCategory.CAPTURE, "could not create a reader for the new size", mapOf("error" to t.toString()))
            return
        }

        try {
            display.resize(size.width, size.height, context.resources.displayMetrics.densityDpi)
            display.setSurface(replacement.surface)
            imageReader = replacement
            captureSize = size
            previousReader?.setOnImageAvailableListener(null, null)
            previousReader?.close()
            events.info(EventCategory.CAPTURE, "capture surface resized", mapOf("capture" to "${size.width}x${size.height}"))
        } catch (t: Throwable) {
            events.error(EventCategory.CAPTURE, "could not resize the capture surface", mapOf("error" to t.toString()))
            replacement.close()
        }
    }

    // ------------------------------------------------------------------- stop

    /** User-initiated stop. Releases everything and ends in [CaptureState.Stopped]. */
    fun stop(reason: String) {
        if (tearingDown) return
        events.info(EventCategory.CAPTURE, "stopping screen capture", mapOf("reason" to reason))
        releaseResources()
        mutableMetrics.value = mutableMetrics.value.copy(running = false)
        mutableState.value = CaptureState.Stopped(reason)
    }

    private fun handleExternalStop(reason: String) {
        if (tearingDown) return
        releaseResources(alreadyStoppedProjection = true)
        mutableMetrics.value = mutableMetrics.value.copy(running = false)
        mutableState.value = CaptureState.Stopped(reason)
        onCaptureEnded?.invoke(reason)
    }

    private fun releaseResources(alreadyStoppedProjection: Boolean = false) {
        tearingDown = true
        try {
            displayListener?.let { displayManager?.unregisterDisplayListener(it) }
            displayListener = null
            displayManager = null

            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null

            virtualDisplay?.release()
            virtualDisplay = null

            projectionCallback?.let { callback ->
                runCatching { projection?.unregisterCallback(callback) }
            }
            projectionCallback = null

            val active = projection
            projection = null
            if (active != null && !alreadyStoppedProjection) {
                runCatching { active.stop() }
            }
        } finally {
            tearingDown = false
        }
    }

    private fun fail(reason: String) {
        events.error(EventCategory.CAPTURE, "screen capture failed", mapOf("reason" to reason))
        mutableState.value = CaptureState.Failed(reason)
    }

    fun release() {
        releaseResources()
    }

    // -------------------------------------------------------------- display info

    private data class DisplayInfo(val width: Int, val height: Int, val rotationDegrees: Int, val maxPixels: Int)

    private fun currentDisplayInfo(): DisplayInfo {
        val rotation = resolveRotation()
        val (width, height) = resolveDisplaySize()
        return DisplayInfo(width, height, rotation, CaptureSizeCalculator.DEFAULT_MAX_PIXELS)
    }

    private fun resolveRotation(): Int {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return 0
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0
        return display.rotation * 90
    }

    @Suppress("DEPRECATION")
    private fun resolveDisplaySize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = context.getSystemService(WindowManager::class.java)
            val bounds = windowManager?.maximumWindowMetrics?.bounds
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val manager = context.getSystemService(DisplayManager::class.java)
        val display = manager?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        if (display != null) {
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(context.resources.displayMetrics)
        }
        return metrics.widthPixels to metrics.heightPixels
    }

    companion object {
        const val VIRTUAL_DISPLAY_NAME = "GameAgentCapture"

        /** Two images is the documented minimum that still allows acquireLatestImage to recycle. */
        const val MAX_ACQUIRED_IMAGES = 2

        const val REQUEST_CODE_SCREEN_CAPTURE = 1001

        /** Four arrays covers the two-slot queue, the in-flight frame and one spare. */
        const val PIXEL_POOL_SIZE = 4

        /** Builds a small preview bitmap. Once per preview interval at most. */
        fun defaultPreviewBuilder(frame: ImageFrame, maxWidth: Int = 320): PreviewImage {
            val scale = minOf(1f, maxWidth.toFloat() / frame.width.toFloat())
            val width = (frame.width * scale).toInt().coerceAtLeast(1)
            val height = (frame.height * scale).toInt().coerceAtLeast(1)
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val sourceY = (y.toLong() * frame.height / height).toInt().coerceIn(0, frame.height - 1)
                val sourceRow = sourceY * frame.width
                val targetRow = y * width
                for (x in 0 until width) {
                    val sourceX = (x.toLong() * frame.width / width).toInt().coerceIn(0, frame.width - 1)
                    pixels[targetRow + x] = frame.argb[sourceRow + sourceX]
                }
            }
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            return PreviewImage(
                image = bitmap.asImageBitmap(),
                frameId = frame.frameId,
                capturedAtMs = frame.timestampMs,
                width = width,
                height = height,
            )
        }
    }
}
