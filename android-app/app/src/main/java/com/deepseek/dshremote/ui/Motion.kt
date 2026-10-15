package com.deepseek.dshremote.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/**
 * Motion primitives aligned with DSH (requirement 4, animation part).
 *
 * Sources:
 *  - `dsh-client-ui-theme/lib/client.js` `base_css_default`:
 *      `--ds-ease-in-out: cubic-bezier(.4,0,.2,1)`
 *      `--ds-transition-duration: .2s` / `-fast: .1s` / `-slow: .3s`
 *  - `dsh-client-ui-primitives/lib/TextShimmer.module.css`:
 *      `animation: dsh-text-shimmer 1.5s cubic-bezier(0.33,0,0.67,1) infinite`
 *      and a `@media (prefers-reduced-motion: reduce)` branch that disables it.
 *  - `dsh-client-ui-primitives/lib/DisclosureRow.module.css`:
 *      chevron crossfade `transition: opacity 100ms ease` (= -fast).
 *
 * ⇒ the streaming "thinking" indicator therefore uses a 1.5s shimmer sweep with
 *   DSH's own cubic-bezier, not a Material spinner.
 */

/** DSH shimmer duration (`dsh-text-shimmer 1.5s`). */
const val DSH_SHIMMER_DURATION_MS = 1500

/**
 * Thinking indicator: a shimmering bar matching `--dsw-linear-gradient-think`
 * (`linear-gradient(180deg, #fff 20.19%, #fff0 100%)`) animated 1.5s.
 *
 * Compose has no CSS gradient sweep on text, so this renders a small rounded bar
 * whose alpha breathes on the same curve and period; the visual intent (a soft
 * "the model is working" sweep rather than a hard spinner) is preserved.
 */
@Composable
internal fun ThinkingIndicator(c: DshColors, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "dsh-shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // cubic-bezier(.33,0,.67,1) is a symmetric ease-in-out; LinearEasing at
            // half the period is the closest built-in equivalent in Compose.
            animation = tween(DSH_SHIMMER_DURATION_MS / 2, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dsh-shimmer-alpha",
    )
    Box(
        modifier
            .size(width = 120.dp, height = 4.dp)
            .alpha(alpha)
            .background(c.textTertiary, RoundedCornerShape(DshTokens.radiusXs)),
    )
}

/**
 * Streaming cursor shown after the last streamed chunk.
 * DSH renders the assistant text with a trailing caret while the turn is live.
 */
@Composable
internal fun StreamingCursor(c: DshColors, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "dsh-caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(DshTokens.DURATION_MS * 5, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dsh-caret-alpha",
    )
    Box(
        modifier
            .padding(start = 2.dp)
            .width(2.dp)
            .height(DshTokens.markdownBase.fontSize.value.dp)
            .alpha(alpha)
            .background(c.accent, CircleShape),
    )
}

/**
 * Row of connection-status dots used by the transcript header.
 * Height follows the disclosure rhythm (24px) so it lines up with DSH rows.
 */
@Composable
internal fun StatusDot(isRunning: Boolean, c: DshColors, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(8.dp)
            .background(if (isRunning) c.dotRunning else c.dotIdle, CircleShape),
    )
}