package com.deepseek.dshremote.ui

/**
 * Image sizing for message attachments (requirement 4, image part).
 *
 * Source: `dsh-client-ui-primitives/lib/ImagePreview.module.css`
 * ```css
 * .frame  { display:grid; place-items:center; width:100%; height:180px;
 *           overflow:hidden; border-radius:var(--dsw-radius-sm);   /* 8px */
 *           background:var(--dsw-alias-bg-base) }
 * .image  { width:100%; height:100%; min-height:0; object-fit:contain; opacity:0 }
 * .image[data-ready] { opacity:1 }
 * .status { position:absolute; inset:0; display:flex; align-items:center;
 *           justify-content:center; gap:8px; padding:12px;
 *           color:var(--dsw-alias-label-tertiary); font-size:12px; line-height:18px }
 * ```
 * i.e. DSH letterboxes into a fixed 180px-tall frame with `object-fit: contain`,
 * and the status line uses 12px/18px in the tertiary label colour.
 *
 * ⚠ Availability: the gateway exposes **no** attachment endpoint today
 * (`rg attachment pc-gateway` finds only an error-code string), so images cannot
 * be fetched yet. This component plus [ImageSizing] is the App-side half; it
 * activates as soon as a `src` is supplied.
 */
object ImageSizing {

    /** `.frame { height: 180px }` */
    const val FRAME_HEIGHT_PX = 180

    /** `.status { font-size:12px; line-height:18px }` */
    const val STATUS_FONT_PX = 12
    const val STATUS_LINE_HEIGHT_PX = 18

    /** `.frame { border-radius: var(--dsw-radius-sm) }` = 8px */
    const val CORNER_PX = 8

    /** Guards against absurd values from untrusted metadata. */
    const val MIN_EDGE_PX = 1
    const val MAX_EDGE_PX = 8192

    /**
     * `object-fit: contain` inside a fixed-height frame: the rendered image is
     * scaled down to fit both axes, never cropped, never upscaled past the frame.
     *
     * @param naturalWidth image width in px (from metadata)
     * @param naturalHeight image height in px
     * @param frameWidth available width in px
     * @param frameHeight frame height in px (defaults to [FRAME_HEIGHT_PX])
     * @return the drawn size, always fitting inside the frame
     */
    fun contain(
        naturalWidth: Int,
        naturalHeight: Int,
        frameWidth: Int,
        frameHeight: Int = FRAME_HEIGHT_PX,
    ): Size {
        if (naturalWidth < MIN_EDGE_PX || naturalHeight < MIN_EDGE_PX) return Size(0, 0)
        if (frameWidth < MIN_EDGE_PX || frameHeight < MIN_EDGE_PX) return Size(0, 0)
        val w = naturalWidth.coerceAtMost(MAX_EDGE_PX)
        val h = naturalHeight.coerceAtMost(MAX_EDGE_PX)
        val scale = minOf(frameWidth.toFloat() / w, frameHeight.toFloat() / h)
        // DSH uses `object-fit: contain`, which scales DOWN only: a small image is
        // never blown up to fill the frame (that would blur it and misrepresent it).
        val applied = minOf(scale, 1f)
        return Size((w * applied).toInt(), (h * applied).toInt())
    }

    /** Drawn area as a fraction of the frame (used to decide if we upscale). */
    fun coverage(drawn: Size, frameWidth: Int, frameHeight: Int): Float {
        if (frameWidth <= 0 || frameHeight <= 0 || drawn.width <= 0 || drawn.height <= 0) return 0f
        val area = drawn.width.toFloat() * drawn.height
        return area / (frameWidth.toFloat() * frameHeight)
    }

    /** A tiny value object so the sizing logic stays pure and unit-testable. */
    data class Size(val width: Int, val height: Int) {
        val isEmpty: Boolean get() = width <= 0 || height <= 0
    }
}