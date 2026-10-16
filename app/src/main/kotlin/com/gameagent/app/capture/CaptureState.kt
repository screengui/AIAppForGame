package com.gameagent.app.capture

import androidx.compose.ui.graphics.ImageBitmap
import com.gameagent.core.geometry.ScreenGeometry
import com.gameagent.core.perception.ImageFrame

/** Lifecycle of the MediaProjection capture stage. */
sealed interface CaptureState {
    data object Idle : CaptureState

    /** Consent was granted and capture resources are being created. */
    data object Starting : CaptureState

    data class Running(
        val captureWidth: Int,
        val captureHeight: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val rotationDegrees: Int,
    ) : CaptureState

    /** Stopped. [reason] is shown verbatim in the UI so the user knows why. */
    data class Stopped(val reason: String) : CaptureState

    data class Failed(val reason: String) : CaptureState

    val isLive: Boolean get() = this is Running
}

/** One captured frame plus the geometry snapshot that makes its coordinates meaningful. */
data class FrameBundle(
    val frame: ImageFrame,
    val geometry: ScreenGeometry,
)

/** A downscaled frame for on-screen preview. Nothing else consumes this. */
data class PreviewImage(
    val image: ImageBitmap,
    val frameId: Long,
    val capturedAtMs: Long,
    val width: Int,
    val height: Int,
)
