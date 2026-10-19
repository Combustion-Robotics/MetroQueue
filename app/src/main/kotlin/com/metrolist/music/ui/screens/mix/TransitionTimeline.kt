/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.mix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.metrolist.music.playback.mix.CustomTransition
import com.metrolist.music.playback.mix.MixAnalysis
import com.metrolist.music.playback.mix.MixWaveform
import com.metrolist.music.playback.mix.SmartMixPlanner
import com.metrolist.music.playback.mix.TransitionStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * What the timeline shows. The outgoing song's positions are the shared time axis; the incoming
 * song starts playing from [incomingStartMs] at [transitionStartMs].
 *
 * @param durationMs the overlap, 0 for a cut.
 */
@Immutable
internal class TimelineModel(
    val outgoingDurationMs: Long,
    val incomingDurationMs: Long?,
    val endMs: Long,
    val durationMs: Long,
    val incomingStartMs: Long,
    val style: TransitionStyle,
    val tail: MixAnalysis?,
    val head: MixAnalysis?,
    val autoEndMs: Long,
    val autoIncomingStartMs: Long,
) {
    val transitionStartMs: Long get() = endMs - durationMs

    fun incomingToTimeline(positionMs: Double): Double = transitionStartMs + positionMs - incomingStartMs
}

/**
 * Two waveform lanes on a shared time axis: the end of the outgoing song on top, the start of the
 * incoming one below. Dragging the lower lane picks where the incoming song starts, the handles
 * between the lanes set the overlap and where the outgoing song ends; pinch zooms, dragging the
 * top lane scrolls and a double tap fits the transition back into view.
 *
 * @param tailStatus shown in the top lane instead of a waveform, when it has none.
 * @param playheadMs the playing position on the time axis, read while drawing only.
 */
@Composable
internal fun TransitionTimeline(
    model: TimelineModel,
    snapToBeats: Boolean,
    tailStatus: String?,
    headStatus: String?,
    playheadMs: () -> Long?,
    onEndChange: (Long) -> Unit,
    onDurationChange: (Long) -> Unit,
    onIncomingStartChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val tailWave = remember(model.tail?.waveform) { model.tail?.waveform?.toWave() }
    val headWave = remember(model.head?.waveform) { model.head?.waveform?.toWave() }
    val current by rememberUpdatedState(model)
    val snap by rememberUpdatedState(snapToBeats)
    val onEnd by rememberUpdatedState(onEndChange)
    val onDuration by rememberUpdatedState(onDurationChange)
    val onIncomingStart by rememberUpdatedState(onIncomingStartChange)
    // Reused for the volume curves, which change with every drag
    val curvePath = remember { Path() }

    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val viewport = remember(widthPx) { Viewport(widthPx).apply { fit(model) } }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(viewport) {
                    var lastTapMs = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val geometry = Geometry(size.height.toFloat(), this)
                        val start = current
                        val target = hitTest(down.position, start, viewport, geometry, HANDLE_TOUCH_RADIUS.toPx())
                        val touchSlop = viewConfiguration.touchSlop
                        val snapPx = SNAP_DISTANCE.toPx()
                        var dragOriginX = Float.NaN
                        var zooming = false
                        var lastSnap: Double? = null
                        var upTimeMs = down.uptimeMillis

                        fun snapped(
                            value: Double,
                            candidates: List<Double>,
                        ): Double {
                            if (!snap) return value
                            val best = candidates.minByOrNull { abs(it - value) }
                            if (best == null || abs(best - value) * viewport.pxPerMs > snapPx) {
                                lastSnap = null
                                return value
                            }
                            if (best != lastSnap) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            lastSnap = best
                            return best
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) {
                                upTimeMs = event.changes.firstOrNull()?.uptimeMillis ?: upTimeMs
                                break
                            }
                            if (pressed >= 2) {
                                zooming = true
                                viewport.zoom(event.calculateZoom(), event.calculateCentroid().x, event.calculatePan().x, current)
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            // After a pinch, wait for all fingers to lift
                            if (zooming) continue
                            val change = event.changes.firstOrNull { it.id == down.id && it.pressed } ?: break
                            if (dragOriginX.isNaN()) {
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                // Leave vertical drags to the scrolling screen
                                if (abs(dy) > touchSlop && abs(dy) > abs(dx)) return@awaitEachGesture
                                if (abs(dx) <= touchSlop) continue
                                dragOriginX = change.position.x
                            }
                            val stepX = change.position.x - change.previousPosition.x
                            change.consume()
                            val m = current
                            val deltaMs = (change.position.x - dragOriginX) / viewport.pxPerMs.toDouble()
                            when (target) {
                                DragTarget.PAN -> viewport.pan(stepX, m)
                                DragTarget.END -> {
                                    val maxEnd = m.outgoingDurationMs.toDouble()
                                    val minEnd = if (m.style == TransitionStyle.CUT) 0.0 else min(m.durationMs.toDouble(), maxEnd)
                                    val raw = (start.endMs + deltaMs).coerceIn(minEnd, maxEnd)
                                    val end = snapped(raw, listOfNotNull(m.tail?.nearestBeat(raw), m.autoEndMs.toDouble()))
                                    onEnd(end.roundToStep())
                                }
                                DragTarget.START -> {
                                    val raw = start.transitionStartMs + deltaMs
                                    val transitionStart = snapped(raw, listOfNotNull(m.tail?.nearestBeat(raw)))
                                    val duration =
                                        (m.endMs - transitionStart).coerceIn(
                                            CustomTransition.MIN_DURATION_MS.toDouble(),
                                            CustomTransition.MAX_DURATION_MS.toDouble(),
                                        )
                                    onDuration(duration.roundToStep())
                                }
                                DragTarget.INCOMING -> {
                                    // The song moves with the finger, so its start point moves the other way
                                    val maxStart = m.incomingDurationMs?.let { (it - MIN_INCOMING_LEFT_MS).coerceAtLeast(0L) }
                                    val raw = (start.incomingStartMs - deltaMs).coerceIn(0.0, maxStart?.toDouble() ?: Double.MAX_VALUE)
                                    val incomingStart = snapped(raw, listOfNotNull(m.beatAlignedIncomingStart(raw), m.autoIncomingStartMs.toDouble()))
                                    onIncomingStart(incomingStart.roundToStep())
                                }
                            }
                        }

                        if (dragOriginX.isNaN() && !zooming && upTimeMs - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                            if (down.uptimeMillis - lastTapMs < viewConfiguration.doubleTapTimeoutMillis) {
                                viewport.fit(current)
                                lastTapMs = 0L
                            } else {
                                lastTapMs = upTimeMs
                            }
                        }
                    }
                },
        ) {
            val m = model
            val geometry = Geometry(size.height, this)
            val laneA = geometry.laneATop
            val laneB = geometry.laneBTop
            val laneHeight = geometry.laneHeight
            val bottom = laneB + laneHeight
            val xStart = viewport.x(m.transitionStartMs.toDouble())
            val xEnd = viewport.x(m.endMs.toDouble())
            val cut = m.style == TransitionStyle.CUT
            val outgoingColor = colors.primary
            val incomingColor = colors.tertiary
            val idleColor = colors.onSurface.copy(alpha = 0.16f)
            val corner = CornerRadius(LANE_CORNER.toPx())

            drawRoundRect(colors.surfaceContainerHigh, Offset(0f, laneA), Size(size.width, laneHeight), corner)
            drawRoundRect(colors.surfaceContainerHigh, Offset(0f, laneB), Size(size.width, laneHeight), corner)
            if (!cut) {
                drawRect(outgoingColor.copy(alpha = 0.08f), Offset(xStart, laneA), Size(xEnd - xStart, bottom - laneA))
            }

            m.tail?.let { drawBeats(it, 0.0, laneA, laneHeight, viewport) }
            m.head?.let { drawBeats(it, m.transitionStartMs - m.incomingStartMs.toDouble(), laneB, laneHeight, viewport) }

            tailWave?.let {
                drawWave(it, viewport.x(it.startMs.toDouble()), laneA, laneHeight, viewport, Float.NEGATIVE_INFINITY, xEnd, outgoingColor, idleColor)
            }
            headWave?.let {
                val originX = viewport.x(m.incomingToTimeline(it.startMs.toDouble()))
                drawWave(it, originX, laneB, laneHeight, viewport, xStart, Float.POSITIVE_INFINITY, incomingColor, idleColor)
            }

            drawStyleHint(m, xStart, xEnd, geometry, viewport, outgoingColor, incomingColor)

            val inset = CURVE_INSET.toPx()
            val strokeWidth = CURVE_WIDTH.toPx()
            drawVolumeCurve(curvePath, xStart, xEnd, laneA + inset, laneHeight - 2 * inset, fadeIn = false)
            drawPath(curvePath, outgoingColor, style = Stroke(strokeWidth))
            drawVolumeCurve(curvePath, xStart, xEnd, laneB + inset, laneHeight - 2 * inset, fadeIn = true)
            drawPath(curvePath, incomingColor, style = Stroke(strokeWidth))

            val markerWidth = MARKER_WIDTH.toPx()
            if (!cut) drawHandle(xStart, laneA, bottom, geometry.gapCenter, incomingColor, markerWidth)
            drawHandle(xEnd, laneA, bottom, geometry.gapCenter, outgoingColor, markerWidth)

            playheadMs()?.let {
                val x = viewport.x(it.toDouble())
                drawLine(colors.onSurface, Offset(x, laneA), Offset(x, bottom), markerWidth)
            }
        }

        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(LANE_PADDING))
            LaneStatus(tailStatus, Modifier.weight(1f))
            Spacer(Modifier.height(LANE_GAP))
            LaneStatus(headStatus, Modifier.weight(1f))
            Spacer(Modifier.height(LANE_PADDING))
        }
    }
}

@Composable
private fun LaneStatus(
    text: String?,
    modifier: Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

private enum class DragTarget { PAN, END, START, INCOMING }

private fun hitTest(
    position: Offset,
    model: TimelineModel,
    viewport: Viewport,
    geometry: Geometry,
    radiusPx: Float,
): DragTarget {
    if (position.y >= geometry.laneBTop) return DragTarget.INCOMING
    val toEnd = abs(position.x - viewport.x(model.endMs.toDouble()))
    val toStart =
        if (model.style == TransitionStyle.CUT) Float.POSITIVE_INFINITY else abs(position.x - viewport.x(model.transitionStartMs.toDouble()))
    return when {
        min(toEnd, toStart) > radiusPx -> DragTarget.PAN
        toStart < toEnd -> DragTarget.START
        else -> DragTarget.END
    }
}

private class Geometry(
    height: Float,
    density: Density,
) {
    val gap = with(density) { LANE_GAP.toPx() }
    val padding = with(density) { LANE_PADDING.toPx() }
    val laneHeight = (height - gap - 2 * padding) / 2
    val laneATop = padding
    val laneBTop = padding + laneHeight + gap
    val gapCenter = padding + laneHeight + gap / 2
}

/** The visible part of the time axis: [startMs] at the left edge, [pxPerMs] wide per ms. */
@Stable
private class Viewport(
    private val widthPx: Float,
) {
    var startMs by mutableFloatStateOf(0f)
        private set
    var pxPerMs by mutableFloatStateOf(1f)
        private set

    fun x(timeMs: Double): Float = ((timeMs - startMs) * pxPerMs).toFloat()

    fun fit(model: TimelineModel) {
        val from = model.transitionStartMs - FIT_MARGIN_MS
        val to = model.endMs + FIT_MARGIN_MS
        pxPerMs = widthPx / (to - from)
        startMs = from.toFloat()
        clamp(model)
    }

    fun pan(
        dx: Float,
        model: TimelineModel,
    ) {
        startMs -= dx / pxPerMs
        clamp(model)
    }

    fun zoom(
        factor: Float,
        centroidX: Float,
        panX: Float,
        model: TimelineModel,
    ) {
        val anchorMs = startMs + centroidX / pxPerMs
        pxPerMs *= factor
        clampZoom(model)
        startMs = anchorMs - (centroidX + panX) / pxPerMs
        clamp(model)
    }

    private fun clamp(model: TimelineModel) {
        clampZoom(model)
        val (from, to) = bounds(model)
        val span = widthPx / pxPerMs
        startMs = if (span >= to - from) from.toFloat() else startMs.coerceIn(from.toFloat(), (to - span).toFloat())
    }

    private fun clampZoom(model: TimelineModel) {
        val (from, to) = bounds(model)
        val maxPx = widthPx / MIN_VISIBLE_MS
        pxPerMs = pxPerMs.coerceIn(min((widthPx / (to - from)).toFloat(), maxPx), maxPx)
    }

    // The waveforms plus a margin, and always the whole transition
    private fun bounds(model: TimelineModel): Pair<Double, Double> {
        val from = min(model.transitionStartMs.toDouble(), (model.outgoingDurationMs - WAVEFORM_SPAN_MS).toDouble()) - EDGE_MS
        val to = max(model.outgoingDurationMs.toDouble(), model.incomingToTimeline(WAVEFORM_SPAN_MS.toDouble())) + EDGE_MS
        return max(from, -EDGE_MS.toDouble()) to max(to, model.endMs + EDGE_MS.toDouble())
    }
}

/** A waveform as a closed shape in data units: x in ms from [startMs], y in -1..1. */
private class Wave(
    val startMs: Long,
    val path: Path,
)

private fun MixWaveform.toWave(): Wave? {
    val levels = levels()
    val peak = levels.maxOrNull()?.takeIf { it > 0f } ?: return null
    val path = Path()
    path.moveTo(0f, 0f)
    for (i in levels.indices) path.lineTo((i + 0.5f) * stepMs, -levels[i] / peak)
    for (i in levels.indices.reversed()) path.lineTo((i + 0.5f) * stepMs, levels[i] / peak)
    path.close()
    return Wave(startMs, path)
}

/** Draws [wave] in the idle color, and in [playedColor] between [playedFromX] and [playedToX]. */
private fun DrawScope.drawWave(
    wave: Wave,
    originX: Float,
    top: Float,
    height: Float,
    viewport: Viewport,
    playedFromX: Float,
    playedToX: Float,
    playedColor: Color,
    idleColor: Color,
) {
    val scaleY = height / 2 * WAVE_HEIGHT
    val draw: DrawScope.(Color) -> Unit = { color ->
        withTransform({
            translate(originX, top + height / 2)
            scale(viewport.pxPerMs, scaleY, Offset.Zero)
        }) {
            drawPath(wave.path, color)
        }
    }
    clipRect(0f, top, size.width, top + height) { draw(idleColor) }
    val left = playedFromX.coerceAtLeast(0f)
    val right = playedToX.coerceAtMost(size.width)
    if (right > left) clipRect(left, top, right, top + height) { draw(playedColor) }
}

/** Beat ticks of [analysis]'s grid, which sits [offsetMs] later on the time axis. */
private fun DrawScope.drawBeats(
    analysis: MixAnalysis,
    offsetMs: Double,
    top: Float,
    height: Float,
    viewport: Viewport,
) {
    if (!analysis.hasConfidentTempo) return
    val beatMs = analysis.beatMs
    if (beatMs * viewport.pxPerMs < MIN_BEAT_SPACING_PX) return
    val phase = analysis.beatPhaseMs + offsetMs
    val firstVisible = viewport.startMs.toDouble()
    val lastVisible = firstVisible + size.width / viewport.pxPerMs
    var beat = ceil((firstVisible - phase) / beatMs)
    val color = Color.Gray.copy(alpha = 0.35f)
    while (true) {
        val x = viewport.x(phase + beat * beatMs)
        if (x > size.width || phase + beat * beatMs > lastVisible) break
        drawLine(color, Offset(x, top), Offset(x, top + height), 1f)
        beat++
    }
}

/**
 * The player's fade curve as a volume line across a lane: full volume (top) before the transition
 * for the outgoing song, silent after it, and the reverse for the incoming one.
 */
private fun DrawScope.drawVolumeCurve(
    path: Path,
    xStart: Float,
    xEnd: Float,
    top: Float,
    height: Float,
    fadeIn: Boolean,
) {
    fun y(gain: Float) = top + height * (1 - gain)
    path.reset()
    path.moveTo(0f, y(if (fadeIn) 0f else 1f))
    path.lineTo(xStart, y(if (fadeIn) 0f else 1f))
    if (xEnd > xStart) {
        for (step in 1..CURVE_STEPS) {
            val progress = step.toFloat() / CURVE_STEPS
            val gain = if (fadeIn) 1 - (1 - progress).pow(2) else (1 - progress).pow(2)
            path.lineTo(xStart + (xEnd - xStart) * progress, y(gain))
        }
    } else {
        path.lineTo(xEnd, y(if (fadeIn) 1f else 0f))
    }
    path.lineTo(size.width, y(if (fadeIn) 1f else 0f))
}

/** A hint of what the style does during the overlap. */
private fun DrawScope.drawStyleHint(
    model: TimelineModel,
    xStart: Float,
    xEnd: Float,
    geometry: Geometry,
    viewport: Viewport,
    outgoingColor: Color,
    incomingColor: Color,
) {
    val laneA = geometry.laneATop
    val laneB = geometry.laneBTop
    val height = geometry.laneHeight
    val shade = 0.18f
    when (model.style) {
        TransitionStyle.FADE -> Unit
        TransitionStyle.FILTER, TransitionStyle.RISE -> {
            // The outgoing song loses its lows as the sweep rises
            drawTriangle(Offset(xStart, laneA + height), Offset(xEnd, laneA), Offset(xEnd, laneA + height), outgoingColor.copy(alpha = shade))
            if (model.style == TransitionStyle.FILTER) {
                // The incoming song's highs open up
                drawTriangle(Offset(xStart, laneB), Offset(xEnd, laneB), Offset(xStart, laneB + height), incomingColor.copy(alpha = shade))
            } else {
                // Its bass is held back until the drop
                drawRect(incomingColor.copy(alpha = shade), Offset(xStart, laneB + height * 0.65f), Size(xEnd - xStart, height * 0.35f))
                drawLine(incomingColor, Offset(xEnd, laneB), Offset(xEnd, laneB + height), MARKER_WIDTH.toPx() * 1.5f)
            }
        }
        TransitionStyle.ECHO -> {
            val delayMs = model.tail?.let(SmartMixPlanner::echoDelayMs) ?: DEFAULT_ECHO_DELAY_MS
            val spacingPx = delayMs * viewport.pxPerMs
            if (spacingPx < MIN_BEAT_SPACING_PX || model.durationMs <= 0) return
            val center = laneA + height / 2
            var echo = 1
            while (true) {
                val x = xStart + echo * spacingPx
                val fraction = echo * delayMs.toFloat() / model.durationMs
                if (fraction >= 1f || x > size.width) break
                val half = height / 2 * WAVE_HEIGHT * (1 - fraction) * 0.8f
                drawLine(outgoingColor.copy(alpha = 0.5f), Offset(x, center - half), Offset(x, center + half), MARKER_WIDTH.toPx())
                echo++
            }
        }
        TransitionStyle.CUT -> {
            drawLine(outgoingColor, Offset(xEnd, laneA), Offset(xEnd, laneB + height), MARKER_WIDTH.toPx() * 1.5f)
        }
    }
}

private fun DrawScope.drawTriangle(
    a: Offset,
    b: Offset,
    c: Offset,
    color: Color,
) {
    if (abs(b.x - a.x) < 1f && abs(c.x - a.x) < 1f) return
    val path =
        Path().apply {
            moveTo(a.x, a.y)
            lineTo(b.x, b.y)
            lineTo(c.x, c.y)
            close()
        }
    drawPath(path, color)
}

/** A marker line through both lanes with a grip between them. */
private fun DrawScope.drawHandle(
    x: Float,
    top: Float,
    bottom: Float,
    gripY: Float,
    color: Color,
    width: Float,
) {
    drawLine(color, Offset(x, top), Offset(x, bottom), width)
    val gripSize = Size(GRIP_WIDTH.toPx(), GRIP_HEIGHT.toPx())
    drawRoundRect(
        color = color,
        topLeft = Offset(x - gripSize.width / 2, gripY - gripSize.height / 2),
        size = gripSize,
        cornerRadius = CornerRadius(gripSize.width / 2),
    )
}

/** The beat of this analysis' grid closest to [positionMs], when the tempo is known. */
private fun MixAnalysis.nearestBeat(positionMs: Double): Double? {
    if (!hasConfidentTempo) return null
    val beatMs = beatMs
    return beatPhaseMs + floor((positionMs - beatPhaseMs) / beatMs + 0.5) * beatMs
}

/**
 * The incoming start near [incomingStartMs] that puts the incoming beat closest to the transition
 * start onto a beat of the outgoing song.
 */
private fun TimelineModel.beatAlignedIncomingStart(incomingStartMs: Double): Double? {
    val incomingBeat = head?.nearestBeat(incomingStartMs) ?: return null
    val beatTime = transitionStartMs + incomingBeat - incomingStartMs
    val outgoingBeat = tail?.nearestBeat(beatTime) ?: return null
    return (incomingStartMs - (outgoingBeat - beatTime)).coerceAtLeast(0.0)
}

private fun Double.roundToStep(): Long = (this / POSITION_STEP_MS).roundToLong() * POSITION_STEP_MS

private val LANE_GAP = 28.dp
private val LANE_PADDING = 4.dp
private val LANE_CORNER = 12.dp
private val HANDLE_TOUCH_RADIUS = 24.dp
private val SNAP_DISTANCE = 12.dp
private val CURVE_INSET = 4.dp
private val CURVE_WIDTH = 2.dp
private val MARKER_WIDTH = 2.dp
private val GRIP_WIDTH = 12.dp
private val GRIP_HEIGHT = 22.dp
private const val WAVE_HEIGHT = 0.85f
private const val CURVE_STEPS = 32
private const val MIN_BEAT_SPACING_PX = 6f
private const val FIT_MARGIN_MS = 8_000L
private const val EDGE_MS = 5_000L
private const val MIN_VISIBLE_MS = 2_000f
private const val WAVEFORM_SPAN_MS = 45_000L
private const val MIN_INCOMING_LEFT_MS = 1_000L
private const val POSITION_STEP_MS = 10L
private const val DEFAULT_ECHO_DELAY_MS = 375L
