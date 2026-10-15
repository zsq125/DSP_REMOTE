package com.deepseek.dshremote.net

import com.deepseek.dshremote.ui.ImageSizing

/**
 * Turns a gateway attachment payload into raw image bytes, safely.
 *
 * Two things matter here and both were requested by the user:
 *  1. **Bad input must never crash the UI**: a truncated or non-base64 payload
 *     yields null, and the caller renders DSH's `.status` error layer instead.
 *  2. **Large images must not OOM the App**. DSH renders into a 180px-tall frame
 *     (`ImagePreview.module.css`), so decoding at full resolution wastes memory
 *     for pixels that are never displayed. [maxEdgePx] bounds the longest side.
 *
 * The decode itself lives in [decodeToBitmap] so the platform bitmap type stays
 * out of the pure-JVM unit tests; everything above it is testable.
 */
object AttachmentBytes {

    /** Gateway cap: `ATTACHMENT_MAX_BYTES` in pc-gateway/lib/dsh.mjs (8 MiB). */
    const val MAX_BYTES = 8L * 1024 * 1024

    /** Longest edge we ever decode at (DSH's frame is 180px tall). */
    const val DEFAULT_MAX_EDGE_PX = 2048

    /**
     * Validate the raw payload before handing it to the base64 decoder.
     * @param data base64 text from the gateway
     * @param declaredBytes the `attachment.bytes` DSH reported, when known
     * @return the payload when acceptable, else null
     */
    fun accept(data: String?, declaredBytes: Long): String? {
        if (data.isNullOrEmpty()) return null
        // base64 alphabet plus padding; anything else means the payload is corrupt.
        if (!data.matches(BASE64)) return null
        if (declaredBytes > MAX_BYTES) return null
        // decoded length ~= 3/4 of the base64 length; guard when DSH reported none
        if (declaredBytes <= 0 && (data.length.toLong() * 3 / 4) > MAX_BYTES) return null
        return data
    }

    /** Sample size to decode at, keeping both edges >= [targetEdgePx] (Android convention). */
    fun inSampleSize(naturalWidth: Int, naturalHeight: Int, targetEdgePx: Int): Int {
        if (naturalWidth <= 0 || naturalHeight <= 0) return 1
        val half = targetEdgePx.coerceAtLeast(1) / 2
        var sample = 1
        while (naturalWidth / (sample * 2) >= half && naturalHeight / (sample * 2) >= half) {
            sample *= 2
        }
        return sample
    }

    /** The frame size an image should be drawn at (DSH: 180px tall, contain). */
    fun frameFor(frameWidthPx: Int, naturalWidth: Int, naturalHeight: Int): ImageSizing.Size =
        ImageSizing.contain(
            naturalWidth = naturalWidth,
            naturalHeight = naturalHeight,
            frameWidth = frameWidthPx,
            frameHeight = ImageSizing.FRAME_HEIGHT_PX,
        )

    private val BASE64 = Regex("^[A-Za-z0-9+/]*={0,2}$")
}

/** Result of an attachment load, mirroring DSH's `.image[data-ready]` / `.status` states. */
sealed interface ImageLoadState {
    /** Nothing requested yet (lazy: DSH loads images on demand). */
    data object Idle : ImageLoadState

    /** Bytes are being fetched. */
    data object Loading : ImageLoadState

    /** Decoded and ready to draw (fade in, per `.image[data-ready]{opacity:1}`). */
    data class Ready(val bitmap: Any) : ImageLoadState

    /** The reference is unusable: unsupported media type or an empty attachment id. */
    data object Empty : ImageLoadState

    /** Failed: bad payload, unsupported media type, too large, or a network error. */
    data class Failed(val reason: Reason) : ImageLoadState {
        enum class Reason { MALFORMED, UNSUPPORTED_TYPE, TOO_LARGE, NETWORK }
    }
}