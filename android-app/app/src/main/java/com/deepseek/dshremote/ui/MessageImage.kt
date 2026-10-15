package com.deepseek.dshremote.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.dshremote.net.AttachmentBytes
import com.deepseek.dshremote.net.ImageLoadState

/**
 * One image inside a message bubble, built to DSH's `ImagePreview.module.css`:
 *
 * ```css
 * .frame  { display:grid; place-items:center; width:100%; height:180px;
 *           overflow:hidden; border-radius:var(--dsw-radius-sm);
 *           background:var(--dsw-alias-bg-base) }
 * .image  { width:100%; height:100%; object-fit:contain; opacity:0 }
 * .image[data-ready] { opacity:1 }                 // fades in once loaded
 * .status { position:absolute; inset:0; display:flex; align-items:center;
 *           justify-content:center; gap:8px; padding:12px;
 *           color:var(--dsw-alias-label-tertiary); font-size:12px; line-height:18px }
 * ```
 *
 * All three failure modes the user asked for are represented:
 * loading (spinner over the frame), error (DSH's `.status` line), empty
 * (unsupported media type) -- never a silent blank box.
 */
@Composable
internal fun MessageImage(
    state: ImageLoadState,
    c: DshColors,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    // 180px frame, 8px radius (--dsw-radius-sm), matching DSH exactly.
    Box(
        modifier
            .fillMaxWidth()
            .height(ImageSizing.FRAME_HEIGHT_PX.dp)
            .clip(RoundedCornerShape(DshTokens.radiusSm))
            .background(c.chatBackground)
            .then(if (onClick != null && state is ImageLoadState.Ready) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is ImageLoadState.Ready -> {
                // `.image { opacity:0 }` -> `.image[data-ready] { opacity:1 }`
                val fade by animateFloatAsState(targetValue = 1f, label = "img-fade")
                val bmp = state.bitmap as? ImageBitmap ?: return@Box
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,   // object-fit: contain
                    modifier = Modifier.fillMaxWidth().alpha(fade),
                )
            }

            is ImageLoadState.Loading -> CircularProgressIndicator(
                modifier = Modifier.height(16.dp),
                strokeWidth = 2.dp,
                color = c.textTertiary,
            )

            is ImageLoadState.Failed -> Text(
                text = statusText(state.reason),
                style = DshTokens.xxs12,        // 12px / 18px
                color = c.textTertiary,          // --dsw-alias-label-tertiary
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(12.dp),
            )

            is ImageLoadState.Empty -> Text(
                text = "no image",
                style = DshTokens.xxs12,
                color = c.textTertiary,
                modifier = Modifier.padding(12.dp),
            )

            is ImageLoadState.Idle -> Unit    // lazy: nothing drawn until requested
        }
    }
}

/** `.status` copy for each failure mode (DSH shows the same muted line). */
internal fun statusText(reason: ImageLoadState.Failed.Reason): String = when (reason) {
    ImageLoadState.Failed.Reason.MALFORMED -> "image could not be decoded"
    ImageLoadState.Failed.Reason.UNSUPPORTED_TYPE -> "unsupported image format"
    ImageLoadState.Failed.Reason.TOO_LARGE -> "image is too large"
    ImageLoadState.Failed.Reason.NETWORK -> "image failed to load"
}

/** Small caption shown under an image when DSH supplied a display name. */
@Composable
internal fun ImageCaption(name: String?, c: DshColors) {
    if (name.isNullOrBlank()) return
    Text(
        name,
        style = DshTokens.xxxs11,
        color = c.textTertiary,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )
}

/**
 * Decodes base64 into a Bitmap, sampling down when the image is larger than
 * [AttachmentBytes.DEFAULT_MAX_EDGE_PX] so a huge screenshot cannot OOM the App.
 *
 * @return null when the payload cannot be decoded (caller shows the error state)
 */
internal fun decodeAttachmentBitmap(
    base64: String,
    naturalWidth: Int,
    naturalHeight: Int,
    maxEdgePx: Int = AttachmentBytes.DEFAULT_MAX_EDGE_PX,
): Bitmap? = try {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = AttachmentBytes.inSampleSize(naturalWidth, naturalHeight, maxEdgePx)
        inPreferredConfig = Bitmap.Config.RGB_565   // halves memory for opaque previews
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
} catch (e: OutOfMemoryError) {
    null   // never let a hostile image kill the process
} catch (e: Exception) {
    null
}

/** Converts a bitmap for Compose, guarding the (rare) conversion failure. */
internal fun Bitmap.toImageBitmapOrNull(): ImageBitmap? = try {
    asImageBitmap()
} catch (e: Exception) {
    null
}

/** Column of images for one message, mirroring DSH's `MessageImages` ordering. */
@Composable
internal fun MessageImages(
    images: List<com.deepseek.dshremote.protocol.Message.ImageRef>,
    states: Map<String, ImageLoadState>,
    c: DshColors,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (img in images) {
            val st = states[img.attachmentId] ?: ImageLoadState.Idle
            if (st is ImageLoadState.Idle) continue      // lazy, like DSH's loadImage
            MessageImage(state = st, c = c)
            ImageCaption(img.name, c)
        }
    }
}

/** Simple row helper used by callers that show a per-image status inline. */
@Composable
internal fun ImageStatusRow(text: String, c: DshColors, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = DshTokens.xxs12, color = c.textTertiary)
    }
}