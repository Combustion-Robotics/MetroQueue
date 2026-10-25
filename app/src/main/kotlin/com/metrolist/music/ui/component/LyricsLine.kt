/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.graphics.BlurMaskFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.constants.LyricsEffects
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.WordTimestamp
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.ui.screens.settings.LyricsPosition
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.PI

private data class HyphenGroupWord(
    val pos: Int,
    val size: Int,
    val isLast: Boolean,
    val groupStartMs: Long,
    val groupEndMs: Long
)

private val GLOW_MAX_RADIUS = 12.dp
private const val GLOW_MAX_ALPHA = 0.4f
private const val GLOW_BASE_STRENGTH = 0.35f
private const val GLOW_RELEASE_MS = 420.0

private const val DEPTH_MAX_LINES = 4
internal const val LINE_DISTANCE_MAX = DEPTH_MAX_LINES + 1
private const val DEPTH_BLUR_STEPS_PER_LINE = 4
private val DEPTH_BLUR_PER_LINE = 1.2.dp
private const val DEPTH_DIM_PER_LINE = 0.07f

private const val ACTIVE_LINE_SCALE = 1.04f

private const val HALO_ALPHA = 0.16f
private val HALO_PADDING_X = 12.dp
private val HALO_PADDING_Y = 10.dp

/**
 * Glow of one glyph: builds up while the fill sweeps across it, peaks as the fill edge leaves it
 * and then fades, so it trails the edge and dies out shortly after the word ends.
 */
private fun glyphGlowLevel(positionMs: Long, word: WordTimestamp, indexInWord: Int, wordLength: Int): Float {
    val startMs = word.startTime * 1000
    // Same timing as the fill (see charLp), so the glow follows the visible edge
    val durationMs = (word.endTime * 1000 - startMs).coerceAtLeast(100.0)
    val glyphStartMs = startMs + durationMs * indexInWord / wordLength
    val glyphEndMs = startMs + durationMs * (indexInWord + 1) / wordLength
    val position = positionMs.toDouble()
    return when {
        position < glyphStartMs -> 0f
        position < glyphEndMs -> ((position - glyphStartMs) / (glyphEndMs - glyphStartMs)).toFloat()
        else -> (1.0 - (position - glyphEndMs) / GLOW_RELEASE_MS).toFloat().coerceAtLeast(0f)
    }
}

/**
 * How strongly a word glows: every sung word a little, long held words the most. Subtle tones
 * it down; Off keeps only the glow of held words.
 */
private fun wordGlowStrength(word: WordTimestamp, effects: LyricsEffects): Float {
    val durationMs = ((word.endTime - word.startTime) * 1000).toFloat()
    val msPerChar = durationMs / word.text.length.coerceAtLeast(1)
    val held = ((msPerChar - 100f) / 250f).coerceIn(0f, 1f) * 0.6f + ((durationMs - 300f) / 1500f).coerceIn(0f, 1f) * 0.4f
    val strength = GLOW_BASE_STRENGTH + (1f - GLOW_BASE_STRENGTH) * held
    return when (effects) {
        LyricsEffects.FULL -> strength
        LyricsEffects.SUBTLE -> strength * 0.6f
        LyricsEffects.OFF -> held
    }
}

private val WAVE_HEIGHT = 1.2.dp
private val NO_WORD = Triple(0f, null as WordTimestamp?, false)

private const val CRESCENDO_STEP = 0.012f
private const val CRESCENDO_PEAK = 0.06f
private const val CRESCENDO_DECAY = 3.5f
private const val CRESCENDO_FREQ = 5f
private const val CRESCENDO_EXIT_MS = 600f

/**
 * Extra scale of a word in a hyphenated group ("la-la-la"): each segment a bit larger than the
 * last, the final one springing up, then all settling back once the group ends. Continuous at
 * every boundary, so nothing jumps when a segment starts or the group ends.
 */
private fun crescendoScale(group: HyphenGroupWord, sungFactor: Float, positionMs: Long): Float {
    val base = group.pos * CRESCENDO_STEP
    val exit = ((positionMs - group.groupEndMs) / CRESCENDO_EXIT_MS).coerceIn(0f, 1f)
    return when {
        exit > 0f -> {
            val atEnd = if (group.isLast) base + CRESCENDO_PEAK else base
            atEnd * exp(-CRESCENDO_DECAY * exit) * cos(CRESCENDO_FREQ * exit * PI.toFloat()) * (1f - exit)
        }
        group.isLast -> base + CRESCENDO_PEAK * (1f - exp(-CRESCENDO_DECAY * sungFactor) * cos(CRESCENDO_FREQ * sungFactor * PI.toFloat()) * (1f - sungFactor))
        else -> base + 0.02f * sin(PI.toFloat() * sungFactor)
    }
}

/**
 * Blurred copy of a glyph drawn under it. Radii are bucketed so each mask filter is created
 * once and reused, rather than allocated per glyph and frame.
 */
private class GlyphGlow(
    typeface: Typeface?,
    textSizePx: Float,
    letterSpacingEm: Float,
    private val maxRadiusPx: Float,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = textSizePx
        letterSpacing = letterSpacingEm
    }
    private val maskFilters = arrayOfNulls<BlurMaskFilter>(RADIUS_STEPS)

    fun draw(canvas: android.graphics.Canvas, text: String, baseline: Float, color: Int, radiusPx: Float) {
        val step = (radiusPx / maxRadiusPx * RADIUS_STEPS).roundToInt().coerceIn(1, RADIUS_STEPS) - 1
        paint.maskFilter = maskFilters[step]
            ?: BlurMaskFilter(maxRadiusPx * (step + 1) / RADIUS_STEPS, BlurMaskFilter.Blur.NORMAL).also { maskFilters[step] = it }
        paint.color = color
        canvas.drawText(text, 0f, baseline, paint)
    }

    private companion object {
        const val RADIUS_STEPS = 8
    }
}

/**
 * Accent fading to transparent over one unit, moved and stretched onto a glyph's fill edge before
 * each draw, so the edge is one draw and the shader is built once per color.
 */
private class FillEdgeBrush(color: Color) : ShaderBrush() {
    private val shader = LinearGradientShader(Offset.Zero, Offset(1f, 0f), listOf(color, color.copy(alpha = 0f)))
    private val matrix = Matrix()

    fun setEdge(startX: Float, width: Float) {
        matrix.setScale(width, 1f)
        matrix.postTranslate(startX, 0f)
        shader.setLocalMatrix(matrix)
    }

    override fun createShader(size: Size): Shader = shader
}

/** Bounds and line of each grapheme cluster, read from the text layout once rather than every frame. */
private class ClusterBounds(layout: TextLayoutResult, charOffsets: IntArray) {
    val left = FloatArray(charOffsets.size)
    val top = FloatArray(charOffsets.size)
    val right = FloatArray(charOffsets.size)
    val bottom = FloatArray(charOffsets.size)
    val line = IntArray(charOffsets.size)

    init {
        charOffsets.forEachIndexed { i, offset ->
            val bounds = layout.getBoundingBox(offset)
            left[i] = bounds.left
            top[i] = bounds.top
            right[i] = bounds.right
            bottom[i] = bounds.bottom
            line[i] = layout.getLineForOffset(offset)
        }
    }
}

private fun String.containsRtl(): Boolean {
    for (c in this) {
        val directionality = Character.getDirectionality(c).toInt()
        if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT.toInt() ||
            directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC.toInt()
        ) {
            return true
        }
    }
    return false
}

// Indic scripts (Devanagari through Sinhala) join glyphs across grapheme clusters, e.g. the
// Bengali/Devanagari headstroke, so drawing clusters separately leaves seams and overlaps.
private fun String.containsJoinedIndic(): Boolean = any { it in '\u0900'..'\u0DFF' }

/**
 * Splits a string into Unicode grapheme clusters using BreakIterator.
 * This correctly handles Devanagari, Bengali, Arabic, Hangul, emoji, etc.
 * where a single visible glyph is composed of multiple code points (e.g. base
 * consonant + matra + anusvara = one cluster, not three separate chars).
 */
private fun String.toGraphemeClusters(): List<String> {
    if (isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(this)
    var start = it.first()
    var end = it.next()
    while (end != java.text.BreakIterator.DONE) {
        result.add(substring(start, end))
        start = end
        end = it.next()
    }
    return result
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LyricsLine(
    item: LyricsEntry,
    isSynced: Boolean,
    isActiveLine: Boolean,
    bgVisible: Boolean,
    isSelected: Boolean,
    isSelectionModeActive: Boolean,
    currentPosition: () -> Long,
    lyricsOffset: Long,
    playerConnection: PlayerConnection,
    lyricsTextSize: Float,
    lyricsLineSpacing: Float,
    expressiveAccent: Color,
    haloColor: Color,
    effects: LyricsEffects,
    musicLevel: () -> Float,
    lyricsTextPosition: LyricsPosition,
    respectAgentPositioning: Boolean,
    isAutoScrollEnabled: Boolean,
    /** Lines from the current one, at most [LINE_DISTANCE_MAX]; farther lines look the same. */
    currentLineDistance: Int,
    romanizeAsMain: Boolean,
    enabledLanguages: List<String>,
    romanizeLyrics: Boolean,
    onSizeChanged: (Int) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    
    val agentAlignment = when {
        respectAgentPositioning && item.agent == "v1" -> Alignment.Start
        respectAgentPositioning && item.agent == "v2" -> Alignment.End
        respectAgentPositioning && item.agent == "v1000" -> Alignment.CenterHorizontally
        item.isBackground -> Alignment.CenterHorizontally
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> Alignment.Start
            LyricsPosition.CENTER -> Alignment.CenterHorizontally
            LyricsPosition.RIGHT -> Alignment.End
        }
    }
    
    val agentTextAlign = when {
        respectAgentPositioning && item.agent == "v1" -> TextAlign.Left
        respectAgentPositioning && item.agent == "v2" -> TextAlign.Right
        respectAgentPositioning && item.agent == "v1000" -> TextAlign.Center
        item.isBackground -> TextAlign.Center
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> TextAlign.Left
            LyricsPosition.CENTER -> TextAlign.Center
            LyricsPosition.RIGHT -> TextAlign.Right
        }
    }

    // Lines around the current one are blurred and dimmed by their distance to it, for depth.
    // Off while the user scrolls or selects, where every line should be readable. Lines further
    // than DEPTH_MAX_LINES sit one step beyond it, where the blur has faded back out: they are
    // nearly transparent or off screen, and skip the blur's offscreen layer.
    val depthTarget = if (
        effects == LyricsEffects.FULL && isSynced && isAutoScrollEnabled && !isSelectionModeActive && !isActiveLine &&
        !(item.isBackground && bgVisible) && currentLineDistance >= 0
    ) {
        currentLineDistance.coerceAtMost(LINE_DISTANCE_MAX).toFloat()
    } else 0f
    val depth by animateFloatAsState(depthTarget, tween(600, easing = FastOutSlowInEasing), label = "lyricsLineDepth")
    val depthBlurs = remember(density) { arrayOfNulls<RenderEffect>(DEPTH_MAX_LINES * DEPTH_BLUR_STEPS_PER_LINE + 1) }

    // Drawn larger only, so the measured line heights and positions stay as they are
    val lineScale by animateFloatAsState(
        targetValue = if (effects != LyricsEffects.OFF && isSynced && isActiveLine && !item.isBackground) ACTIVE_LINE_SCALE else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "lyricsLineScale",
    )
    val scaleOrigin = when (agentTextAlign) {
        TextAlign.Left -> TransformOrigin(0f, 0.5f)
        TextAlign.Right -> TransformOrigin(1f, 0.5f)
        else -> TransformOrigin.Center
    }

    val itemModifier = modifier
        .graphicsLayer {
            scaleX = lineScale
            scaleY = lineScale
            transformOrigin = scaleOrigin
            alpha = 1f - DEPTH_DIM_PER_LINE * depth.coerceAtMost(DEPTH_MAX_LINES.toFloat())
            // Dimming alone then needs no offscreen layer
            compositingStrategy = CompositingStrategy.ModulateAlpha
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val blurDepth = if (depth <= DEPTH_MAX_LINES) depth else DEPTH_MAX_LINES * (DEPTH_MAX_LINES + 1 - depth)
                // Bucketed so animating the radius reuses a few effects instead of one per frame
                val step = (blurDepth * DEPTH_BLUR_STEPS_PER_LINE).roundToInt()
                renderEffect = if (step == 0) null else depthBlurs[step] ?: run {
                    val radius = DEPTH_BLUR_PER_LINE.toPx() * step / DEPTH_BLUR_STEPS_PER_LINE
                    BlurEffect(radius, radius, TileMode.Decal).also { depthBlurs[step] = it }
                }
            }
        }
        .fillMaxWidth()
        .onSizeChanged { onSizeChanged(it.height) }
        .clip(RoundedCornerShape(8.dp))
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
        .background(if (isSelected && isSelectionModeActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent)
        .padding(
            start = when (lyricsTextPosition) { LyricsPosition.LEFT, LyricsPosition.RIGHT -> 11.dp; LyricsPosition.CENTER -> 24.dp },
            end = when (lyricsTextPosition) { LyricsPosition.LEFT, LyricsPosition.RIGHT -> 11.dp; LyricsPosition.CENTER -> 24.dp },
            top = if (item.isBackground) 0.dp else 12.dp,
            bottom = if (item.isBackground) 2.dp else 12.dp // simplified gap logic
        )

    Box(modifier = itemModifier, contentAlignment = when {
        respectAgentPositioning && item.agent == "v1" -> Alignment.CenterStart
        respectAgentPositioning && item.agent == "v2" -> Alignment.CenterEnd
        item.isBackground -> Alignment.Center
        respectAgentPositioning && item.agent == "v1000" -> Alignment.Center
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> Alignment.CenterStart
            LyricsPosition.RIGHT -> Alignment.CenterEnd
            LyricsPosition.CENTER -> Alignment.Center
        }
    }) {
        @Composable
        fun LyricContent() {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = agentAlignment) {
                val inactiveAlpha = if (item.isBackground) 0.08f else 0.2f
                val activeAlpha = 1f
                val focusedAlpha = if (item.isBackground) 0.5f else 0.3f
                val targetAlpha = if (!isSynced || item.isBackground || isActiveLine) {
                    activeAlpha
                } else if (isAutoScrollEnabled && currentLineDistance >= 0) {
                    when (currentLineDistance) {
                        0 -> focusedAlpha
                        1 -> 0.2f; 2 -> 0.2f; 3 -> 0.15f; 4 -> 0.1f; else -> 0.08f
                    }
                } else inactiveAlpha
                
                val animatedAlpha by animateFloatAsState(targetAlpha, tween(400, easing = FastOutSlowInEasing), label = "lyricsLineAlpha")
                val lineColor = expressiveAccent.copy(alpha = if (item.isBackground) focusedAlpha else animatedAlpha)
                
                val romanizedTextState by item.romanizedTextFlow.collectAsStateWithLifecycle()
                val isRomanizedAvailable = romanizedTextState != null
                val mainTextRaw = if (romanizeAsMain && isRomanizedAvailable) romanizedTextState else item.text
                val subTextRaw = if (romanizeAsMain && isRomanizedAvailable) item.text else romanizedTextState
                val mainText = if (item.isBackground) mainTextRaw?.removePrefix("(")?.removeSuffix(")") else mainTextRaw
                val subText = if (item.isBackground) subTextRaw?.removePrefix("(")?.removeSuffix(")") else subTextRaw

                val lyricStyle = TextStyle(
                    fontSize = if (item.isBackground) (lyricsTextSize * 0.7f).sp else lyricsTextSize.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = if (item.isBackground) FontStyle.Italic else FontStyle.Normal,
                    lineHeight = if (item.isBackground) (lyricsTextSize * 0.7f * lyricsLineSpacing).sp else (lyricsTextSize * lyricsLineSpacing).sp,
                    letterSpacing = (-0.5).sp,
                    textAlign = agentTextAlign,
                    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                )

                val effectiveWords = if (item.words?.isNotEmpty() == true) {
                    item.words
                } else if (mainText != null) {
                    remember(mainText, item.time) {
                        val words = mainText.split(Regex("\\s+")).filter { it.isNotBlank() }
                        val wordDurationSec = 0.18
                        val wordStaggerSec = 0.03
                        val startTimeSec = item.time / 1000.0
                        words.mapIndexed { idx, wordText ->
                            WordTimestamp(
                                text = wordText,
                                startTime = startTimeSec + (idx * wordStaggerSec),
                                endTime = startTimeSec + (idx * wordStaggerSec) + wordDurationSec,
                                hasTrailingSpace = idx < words.size - 1
                            )
                        }
                    }
                } else null

                if (isSynced && effectiveWords != null && (isActiveLine || currentLineDistance <= 3) && mainText != null) {
                    WordLevelLyrics(
                        mainText = mainText,
                        words = effectiveWords,
                        isActiveLine = isActiveLine,
                        currentPosition = currentPosition,
                        lyricsOffset = lyricsOffset,
                        playerConnection = playerConnection,
                        lyricStyle = lyricStyle,
                        lineColor = lineColor,
                        expressiveAccent = expressiveAccent,
                        haloColor = haloColor,
                        effects = effects,
                        musicLevel = musicLevel,
                        isBackground = item.isBackground,
                        focusedAlpha = focusedAlpha,
                        alignment = agentTextAlign
                    )
                } else {
                    Text(
                        text = mainText ?: "",
                        style = lyricStyle.copy(color = if (isActiveLine) expressiveAccent else lineColor),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                if (romanizeLyrics && enabledLanguages.isNotEmpty()) {
                    subText?.let { 
                        Text(
                            text = it,
                            fontSize = 18.sp,
                            color = expressiveAccent.copy(alpha = 0.6f),
                            textAlign = agentTextAlign,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                
                val transText by item.translatedTextFlow.collectAsStateWithLifecycle()
                transText?.let { 
                    Text(
                        text = it,
                        fontSize = 16.sp,
                        color = expressiveAccent.copy(alpha = 0.5f),
                        textAlign = agentTextAlign,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        if (item.isBackground) {
            AnimatedVisibility(
                visible = bgVisible,
                enter = fadeIn(tween(durationMillis = 250, delayMillis = 100)),
                exit = fadeOut(tween(250))
            ) {
                LyricContent()
            }
        } else {
            LyricContent()
        }
    }
}

@Composable
private fun WordLevelLyrics(
    mainText: String,
    words: List<WordTimestamp>,
    isActiveLine: Boolean,
    currentPosition: () -> Long,
    lyricsOffset: Long,
    playerConnection: PlayerConnection,
    lyricStyle: TextStyle,
    lineColor: Color,
    expressiveAccent: Color,
    haloColor: Color,
    effects: LyricsEffects,
    musicLevel: () -> Float,
    isBackground: Boolean,
    focusedAlpha: Float,
    alignment: TextAlign
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    // The glow is drawn with the platform paint, so it needs the typeface the lyrics actually use
    // (custom fonts included) to line up with the glyphs. Mask filters need hardware support (P+).
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val resolvedTypeface by remember(fontFamilyResolver, lyricStyle.fontFamily, lyricStyle.fontWeight, lyricStyle.fontStyle) {
        fontFamilyResolver.resolve(
            fontFamily = lyricStyle.fontFamily,
            fontWeight = lyricStyle.fontWeight ?: FontWeight.Normal,
            fontStyle = lyricStyle.fontStyle ?: FontStyle.Normal,
        )
    }
    val glyphGlow = remember(resolvedTypeface, lyricStyle.fontSize, lyricStyle.letterSpacing, density) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@remember null
        val fontSize = lyricStyle.fontSize
        GlyphGlow(
            typeface = resolvedTypeface as? Typeface,
            textSizePx = with(density) { fontSize.toPx() },
            letterSpacingEm = if (lyricStyle.letterSpacing.isSp && fontSize.isSp) lyricStyle.letterSpacing.value / fontSize.value else 0f,
            maxRadiusPx = with(density) { GLOW_MAX_RADIUS.toPx() },
        )
    }
    
    // Read only in the Canvas. Starts from the shared position whenever the line turns (in)active,
    // without subscribing this composition to it; the active line then runs its own frame clock.
    var smoothPosition by remember(isActiveLine) {
        mutableLongStateOf(Snapshot.withoutReadObservation { currentPosition() } + lyricsOffset)
    }
    val motionScale = if (effects == LyricsEffects.OFF) 0.5f else 1f

    val haloLevel by animateFloatAsState(
        targetValue = if (isActiveLine && effects != LyricsEffects.OFF) 1f else 0f,
        animationSpec = tween(if (isActiveLine) 500 else 350),
        label = "lyricsLineHalo",
    )
    // After the line ends its glyph glow keeps fading from where it was instead of vanishing
    val glowTail by animateFloatAsState(
        targetValue = if (isActiveLine) 1f else 0f,
        animationSpec = if (isActiveLine) snap() else tween(GLOW_RELEASE_MS.toInt(), easing = LinearEasing),
        label = "lyricsLineGlowTail",
    )
    val fillEdgeBrush = remember(expressiveAccent) { FillEdgeBrush(expressiveAccent) }
    // Unit-radius gradient scaled to the text when drawn, so the shader is built once per color
    val haloBrush = remember(haloColor) {
        Brush.radialGradient(
            0f to haloColor,
            0.5f to haloColor.copy(alpha = haloColor.alpha * 0.5f),
            1f to haloColor.copy(alpha = 0f),
            center = Offset.Zero,
            radius = 1f,
        )
    }
    
    LaunchedEffect(isActiveLine) {
        if (isActiveLine) {
            var lastPlayerPos = playerConnection.player.currentPosition
            var lastUpdateTime = System.currentTimeMillis()
            while (isActive) {
                withFrameMillis {
                    val now = System.currentTimeMillis()
                    val playerPos = playerConnection.player.currentPosition
                    if (playerPos != lastPlayerPos) {
                        lastPlayerPos = playerPos
                        lastUpdateTime = now
                    }
                    val elapsed = now - lastUpdateTime
                    smoothPosition = lastPlayerPos + lyricsOffset + (if (playerConnection.player.isPlaying) elapsed else 0)
                }
            }
        }
    }

    val (effectiveWords, effectiveToOriginalIdx) = remember(words, isBackground) {
        words.flatMapIndexed { originalIdx, word ->
            val shouldSplit = word.text.contains('-') && word.text.length > 1 &&
                (!word.hasTrailingSpace || words.size == 1)
            if (shouldSplit) {
                val segments = mutableListOf<String>()
                var start = 0
                for (i in 0 until word.text.length) {
                    if (word.text[i] == '-') {
                        segments.add(word.text.substring(start, i + 1))
                        start = i + 1
                    }
                }
                if (start < word.text.length) {
                    segments.add(word.text.substring(start))
                }

                if (segments.size > 1) {
                    val totalDuration = word.endTime - word.startTime
                    val segmentDuration = totalDuration / segments.size
                    segments.mapIndexed { index, segmentText ->
                        WordTimestamp(
                            text = segmentText,
                            startTime = word.startTime + index * segmentDuration,
                            endTime = word.startTime + (index + 1) * segmentDuration,
                            hasTrailingSpace = if (index == segments.size - 1) word.hasTrailingSpace else false
                        ) to originalIdx
                    }
                } else listOf(word to originalIdx)
            } else listOf(word to originalIdx)
        }.let { data -> data.map { it.first } to data.map { it.second } }
    }

    // Break mainText into grapheme clusters so that multi-codepoint glyphs
    // (Devanagari/Bengali matras, Arabic ligatures, emoji, etc.) are treated
    // as single units throughout the animation pipeline.
    val graphemeClusters = remember(mainText) { mainText.toGraphemeClusters() }
    val clusterCount = graphemeClusters.size
    // Glow alpha and radius of each cluster in the last active frame, for the tail after the line ends
    val glowAlphas = remember(graphemeClusters) { FloatArray(clusterCount) }
    val glowRadii = remember(graphemeClusters) { FloatArray(clusterCount) }
    // For each cluster index, the String offset (Char index) of its first character in mainText.
    // Required because TextLayoutResult.getBoundingBox/getLineForOffset take
    // String offsets (UTF-16/Char indices), not cluster indices.
    val clusterCharOffsets = remember(mainText) {
        IntArray(clusterCount).also { offsets ->
            var charOffset = 0
            graphemeClusters.forEachIndexed { i, cluster ->
                offsets[i] = charOffset
                charOffset += cluster.length
            }
        }
    }

    // wordIdxMap / charInWordMap / wordLenMap are now sized and indexed by
    // CLUSTER INDEX (not codepoint index) so that each visual glyph unit is
    // mapped to exactly one word slot.
    val charToWordData = remember(mainText, effectiveWords, isBackground, graphemeClusters, clusterCharOffsets) {
        val wordIdxMap = IntArray(clusterCount) { -1 }
        val charInWordMap = IntArray(clusterCount)
        val wordLenMap = IntArray(clusterCount) { 1 }
        var currentPos = 0
        var clCursor = 0
        effectiveWords.forEachIndexed { wordIdx, word ->
            val rawWordText = word.text.let {
                if (isBackground) {
                    var t = it
                    if (wordIdx == 0) t = t.removePrefix("(")
                    if (wordIdx == effectiveWords.size - 1) t = t.removeSuffix(")")
                    t
                } else it
            }
            val indexInMain = mainText.indexOf(rawWordText, currentPos)
            if (indexInMain != -1) {
                val wordEndInMain = indexInMain + rawWordText.length
                // Advance clCursor to the first cluster at or after indexInMain
                while (clCursor < clusterCount && clusterCharOffsets[clCursor] < indexInMain) {
                    clCursor++
                }
                val firstClIdx = clCursor
                // Collect all clusters in the word range [indexInMain, wordEndInMain)
                val wordClusterIndices = mutableListOf<Int>()
                while (clCursor < clusterCount && clusterCharOffsets[clCursor] < wordEndInMain) {
                    wordClusterIndices.add(clCursor)
                    clCursor++
                }
                val wordClusterLen = wordClusterIndices.size
                wordClusterIndices.forEachIndexed { posInWord, clIdx ->
                    wordIdxMap[clIdx] = wordIdx
                    charInWordMap[clIdx] = posInWord
                    wordLenMap[clIdx] = wordClusterLen
                }
                // Check the cluster at clCursor for a trailing space
                if (clCursor < clusterCount && clusterCharOffsets[clCursor] == wordEndInMain && 
                    wordEndInMain < mainText.length && mainText[wordEndInMain] == ' ') {
                    val spaceClIdx = clCursor
                    wordIdxMap[spaceClIdx] = wordIdx
                    charInWordMap[spaceClIdx] = wordClusterLen
                    wordLenMap[spaceClIdx] = wordClusterLen + 1
                    clCursor++
                }
                currentPos = wordEndInMain
            }
        }
        Triple(wordIdxMap, charInWordMap, wordLenMap)
    }

    val hyphenGroupData = remember(effectiveWords) {
        val map = mutableMapOf<Int, HyphenGroupWord>()
        var currentGroup = mutableListOf<Int>()
        effectiveWords.forEachIndexed { wordIdx, word ->
            currentGroup.add(wordIdx)
            if (!word.text.endsWith("-")) {
                if (currentGroup.size > 1) {
                    val groupSize = currentGroup.size
                    val groupStartMs = (effectiveWords[currentGroup.first()].startTime * 1000).toLong()
                    val groupEndMs = (word.endTime * 1000).toLong()
                    currentGroup.forEachIndexed { pos, idx ->
                        map[idx] = HyphenGroupWord(pos, groupSize, pos == groupSize - 1, groupStartMs, groupEndMs)
                    }
                }
                currentGroup = mutableListOf()
            }
        }
        map
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxWidthPx = constraints.maxWidth
        val layoutResult = remember(mainText, maxWidthPx, lyricStyle) {
            textMeasurer.measure(
                text = mainText,
                style = lyricStyle,
                constraints = Constraints(minWidth = maxWidthPx, maxWidth = maxWidthPx),
                softWrap = true
            )
        }
        
        // Each layout corresponds to one grapheme cluster (the visual unit),
        // not one codepoint. Fixes Devanagari/Bengali matra fragmentation.
        val letterLayouts = remember(mainText, lyricStyle) {
            graphemeClusters.map { cluster -> textMeasurer.measure(cluster, lyricStyle) }
        }
        
        val textBounds = remember(layoutResult) {
            var left = Float.MAX_VALUE
            var right = 0f
            for (line in 0 until layoutResult.lineCount) {
                left = minOf(left, layoutResult.getLineLeft(line))
                right = maxOf(right, layoutResult.getLineRight(line))
            }
            if (layoutResult.lineCount == 0) Rect.Zero else Rect(left, 0f, right, layoutResult.size.height.toFloat())
        }
        val clusterBounds = remember(layoutResult, clusterCharOffsets) { ClusterBounds(layoutResult, clusterCharOffsets) }

        // RTL and joined Indic text is drawn as one shaped line with per-word clips instead of per cluster.
        val drawWholeLine = remember(mainText) { mainText.containsRtl() || mainText.containsJoinedIndic() }
        // Clip rect of each word for drawWholeLine as left, top, right, bottom; left stays MAX_VALUE without clusters
        val wordBounds = remember(drawWholeLine, clusterBounds, charToWordData, effectiveWords.size) {
            if (!drawWholeLine) return@remember null
            val wordIdxMap = charToWordData.first
            FloatArray(effectiveWords.size * 4) { if (it % 4 < 2) Float.MAX_VALUE else Float.MIN_VALUE }.also { rects ->
                for (i in 0 until clusterCount) {
                    val w = wordIdxMap[i]
                    if (w == -1) continue
                    rects[w * 4] = minOf(rects[w * 4], clusterBounds.left[i])
                    rects[w * 4 + 1] = minOf(rects[w * 4 + 1], clusterBounds.top[i])
                    rects[w * 4 + 2] = maxOf(rects[w * 4 + 2], clusterBounds.right[i])
                    rects[w * 4 + 3] = maxOf(rects[w * 4 + 3], clusterBounds.bottom[i])
                }
            }
        }

        Canvas(modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { layoutResult.size.height.toDp() })
            // No offscreen buffer: it would cut the glow and halo off at the text bounds
            .graphicsLayer(clip = false)
        ) {
            if (mainText.isEmpty()) return@Canvas
            // Eases in with the line's own fade when it becomes active instead of popping to focusedAlpha
            val unsungAlpha = minOf(focusedAlpha, lineColor.alpha)
            val halo = haloLevel
            if (halo > 0.01f && textBounds.width > 0f) {
                val center = textBounds.center
                val radiusX = textBounds.width / 2f + HALO_PADDING_X.toPx()
                val radiusY = textBounds.height / 2f + HALO_PADDING_Y.toPx()
                withTransform({
                    translate(center.x, center.y)
                    scale(radiusX, radiusY, pivot = Offset.Zero)
                }) {
                    drawCircle(haloBrush, radius = 1f, center = Offset.Zero, alpha = HALO_ALPHA * halo)
                }
            }
            if (!isActiveLine) {
                val tail = glowTail
                if (glyphGlow != null && !drawWholeLine && tail > 0.01f) {
                    for (i in 0 until clusterCount) {
                        val alpha = glowAlphas[i] * tail
                        if (alpha < 0.004f) continue
                        translate(clusterBounds.left[i], clusterBounds.top[i]) {
                            drawIntoCanvas { canvas ->
                                glyphGlow.draw(
                                    canvas.nativeCanvas,
                                    letterLayouts[i].layoutInput.text.text,
                                    letterLayouts[i].firstBaseline,
                                    expressiveAccent.copy(alpha = alpha).toArgb(),
                                    glowRadii[i],
                                )
                            }
                        }
                    }
                }
                drawText(layoutResult, color = lineColor)
            } else {
                if (drawWholeLine) {
                    val wordFactors = effectiveWords.map { word ->
                        val wStartMs = (word.startTime * 1000).toLong()
                        val wEndMs = (word.endTime * 1000).toLong()
                        val isWordSung = smoothPosition > wEndMs
                        val isWordActive = smoothPosition in wStartMs..wEndMs
                        val sungFactor = if (isWordSung) 1f 
                                        else if (isWordActive) ((smoothPosition - wStartMs).toFloat() / (wEndMs - wStartMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                                        else 0f
                        Triple(sungFactor, isWordSung, isWordActive)
                    }

                    drawText(layoutResult, color = lineColor.copy(alpha = unsungAlpha))

                    effectiveWords.indices.forEach { wIdx ->
                        val (sungFactor, isWordSung, isWordActive) = wordFactors[wIdx]
                        val left = wordBounds!![wIdx * 4]
                        val top = wordBounds[wIdx * 4 + 1]
                        val right = wordBounds[wIdx * 4 + 2]
                        val bottom = wordBounds[wIdx * 4 + 3]

                        if (left != Float.MAX_VALUE) {
                            if (isWordSung) {
                                clipRect(left = left, top = top, right = right, bottom = bottom) {
                                    drawText(layoutResult, color = expressiveAccent)
                                }
                            } else if (isWordActive && sungFactor > 0f) {
                                clipRect(left = left, top = top, right = right, bottom = bottom) {
                                    drawText(layoutResult, color = expressiveAccent.copy(alpha = unsungAlpha + (1f - unsungAlpha) * sungFactor))
                                }
                            }
                        }
                    }
                    return@Canvas
                }

                val (wordIdxMap, charInWordMap, wordLenMap) = charToWordData
                val wordFactors = effectiveWords.map { word ->
                    val wStartMs = (word.startTime * 1000).toLong()
                    val wEndMs = (word.endTime * 1000).toLong()
                    val isWordSung = smoothPosition > wEndMs
                    val isWordActive = smoothPosition in wStartMs..wEndMs
                    val sungFactor = if (isWordSung) 1f 
                                    else if (isWordActive) ((smoothPosition - wStartMs).toFloat() / (wEndMs - wStartMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                                    else 0f
                    Triple(sungFactor, word, isWordSung)
                }

                val wordWobbles = FloatArray(words.size)
                words.forEachIndexed { wordIdx, word ->
                    val startMs = (word.startTime * 1000).toLong()
                    val timeSinceStart = (smoothPosition - startMs).toFloat()
                    val envelope = if (timeSinceStart in 0f..750f) {
                        if (timeSinceStart < 125f) timeSinceStart / 125f
                        else (1f - (timeSinceStart - 125f) / 625f).coerceAtLeast(0f)
                    } else 0f
                    // Smoothstep, so the wobble eases in and out instead of starting and stopping sharply
                    wordWobbles[wordIdx] = envelope * envelope * (3f - 2f * envelope) * motionScale
                }

                val lineCurrentPushes = FloatArray(layoutResult.lineCount)
                val lineTotalPushes = FloatArray(layoutResult.lineCount)
                
                // Pre-calculate total pushes per line to handle alignment correctly.
                // Iterate over cluster indices so each visual glyph unit is one slot.
                for (i in 0 until clusterCount) {
                    val lineIdx = clusterBounds.line[i]
                    val wordIdx = wordIdxMap[i]
                    val originalWordIdx = if (wordIdx != -1) effectiveToOriginalIdx[wordIdx] else -1
                    
                    val (sungFactor, wordItem, isWordSung) = if (wordIdx != -1) wordFactors[wordIdx] else NO_WORD
                    val wobble = if (originalWordIdx != -1) wordWobbles[originalWordIdx] else 0f
                    
                    val groupWord = if (wordIdx != -1) hyphenGroupData[wordIdx] else null
                    val crescendoDeltaX = if (groupWord != null) crescendoScale(groupWord, sungFactor, smoothPosition) else 0f

                    val charLp = if (wordItem != null) {
                        val sMs = wordItem.startTime * 1000
                        val dur = (wordItem.endTime * 1000 - wordItem.startTime * 1000).coerceAtLeast(100.0)
                        val wProg = (smoothPosition.toDouble() - sMs) / dur
                        val cInW = charInWordMap[i].toDouble()
                        val wLen = wordLenMap[i].toDouble()
                        ((wProg - cInW / wLen) * wLen).coerceIn(0.0, 1.0).toFloat()
                    } else 0f

                    val nudgeScale = if (wordItem != null && !isWordSung && sungFactor > 0f) {
                        0.038f * sin(charLp * PI.toFloat()) * exp(-3f * charLp)
                    } else 0f

                    val charScaleX = 1f + (wobble * 0.025f) + crescendoDeltaX + (nudgeScale * 0.3f)
                    lineTotalPushes[lineIdx] += (clusterBounds.right[i] - clusterBounds.left[i]) * (charScaleX - 1f)
                }

                val waveHeightPx = WAVE_HEIGHT.toPx() * motionScale
                // Neutral (0.5) leaves the glow as it is; louder passages brighten and widen it a little
                val level = musicLevel()
                val glowAlphaScale = 0.7f + 0.6f * level
                val glowRadiusScale = 0.85f + 0.3f * level
                val glowMaxRadiusPx = GLOW_MAX_RADIUS.toPx()

                // Main drawing loop: iterate over cluster indices so each visual
                // glyph (including multi-codepoint Devanagari clusters) is one unit.
                for (i in 0 until clusterCount) {
                    val lineIdx = clusterBounds.line[i]
                    val charLeft = clusterBounds.left[i]
                    val charTop = clusterBounds.top[i]
                    val charWidth = clusterBounds.right[i] - charLeft
                    val charHeight = clusterBounds.bottom[i] - charTop
                    val wordIdx = wordIdxMap[i]
                    val originalWordIdx = if (wordIdx != -1) effectiveToOriginalIdx[wordIdx] else -1
                    
                    val alignShift = when(alignment) {
                        TextAlign.Center -> -lineTotalPushes[lineIdx] / 2f
                        TextAlign.Right -> -lineTotalPushes[lineIdx]
                        else -> 0f
                    }
                    
                    val (sungFactor, wordItem, isWordSung) = if (wordIdx != -1) wordFactors[wordIdx] else NO_WORD
                    val wobble = if (originalWordIdx != -1) wordWobbles[originalWordIdx] else 0f
                    val wobbleX = wobble * 0.025f
                    val wobbleY = wobble * 0.015f
                    
                    val charLp = if (wordItem != null) {
                        val sMs = wordItem.startTime * 1000
                        val dur = (wordItem.endTime * 1000 - wordItem.startTime * 1000).coerceAtLeast(100.0)
                        val wProg = (smoothPosition.toDouble() - sMs) / dur
                        val cInW = charInWordMap[i].toDouble()
                        val wLen = wordLenMap[i].toDouble()
                        ((wProg - cInW / wLen) * wLen).coerceIn(0.0, 1.0).toFloat()
                    } else 0f

                    val groupWord = if (wordIdx != -1) hyphenGroupData[wordIdx] else null
                    val crescendoDelta = if (groupWord != null) crescendoScale(groupWord, sungFactor, smoothPosition) else 0f

                    val nudgeStrength = 0.038f
                    val nudgeScale = if (wordItem != null && !isWordSung && sungFactor > 0f) {
                        nudgeStrength * sin(charLp * PI.toFloat()) * exp(-3f * charLp)
                    } else 0f
                    
                    val charScaleX = 1f + wobbleX + crescendoDelta + nudgeScale * 0.3f
                    val charScaleY = 1f + wobbleY + crescendoDelta + nudgeScale

                    withTransform({
                        var waveOffset = 0f
                        if (groupWord != null) {
                            val wallTime = System.currentTimeMillis()
                            val adjSmoothPos = smoothPosition
                            val timeInGroup = (adjSmoothPos - groupWord.groupStartMs).toFloat()
                            val timeToGroupEnd = (groupWord.groupEndMs - adjSmoothPos).toFloat()
                            val waveFade = (timeInGroup / 200f).coerceIn(0f, 1f) * (timeToGroupEnd / 200f).coerceIn(0f, 1f)
                            if (waveFade > 0.01f) {
                                val waveSpeed = 0.006f
                                val phaseOffset = i * 0.4f
                                waveOffset = sin(wallTime * waveSpeed + phaseOffset) * waveHeightPx * waveFade
                            }
                        }

                        translate(left = alignShift + lineCurrentPushes[lineIdx] + charLeft, top = charTop + waveOffset)
                        if (wordIdx != -1) {
                            scale(
                                charScaleX,
                                charScaleY,
                                pivot = Offset(charWidth / 2f, charHeight)
                            )
                        }
                    }) {
                        glowAlphas[i] = 0f
                        if (glyphGlow != null && wordItem != null) {
                            val glowLevel = glyphGlowLevel(smoothPosition, wordItem, charInWordMap[i], wordLenMap[i])
                            val strength = if (glowLevel > 0.01f) wordGlowStrength(wordItem, effects) else 0f
                            if (strength * glowLevel > 0.01f) {
                                val glowAlpha = (GLOW_MAX_ALPHA * strength * glowLevel * glowAlphaScale).coerceAtMost(1f)
                                val glowRadius = glowMaxRadiusPx * (0.4f + 0.6f * strength) * glowRadiusScale
                                glowAlphas[i] = glowAlpha
                                glowRadii[i] = glowRadius
                                drawIntoCanvas { canvas ->
                                    glyphGlow.draw(
                                        canvas.nativeCanvas,
                                        letterLayouts[i].layoutInput.text.text,
                                        letterLayouts[i].firstBaseline,
                                        expressiveAccent.copy(alpha = glowAlpha).toArgb(),
                                        glowRadius,
                                    )
                                }
                            }
                        }
                        val baseAlpha = if (isWordSung || charLp > 0.99f) 1f else (unsungAlpha + (1f - unsungAlpha) * sungFactor)
                        drawText(letterLayouts[i], color = expressiveAccent.copy(alpha = if (wordIdx == -1) unsungAlpha else baseAlpha))
                        if (!isWordSung && charLp > 0f && charLp < 1f) {
                            val fXL = charWidth * charLp
                            val eW = (charWidth * 0.45f).coerceAtLeast(1f)
                            val sWL = (fXL - eW).coerceAtLeast(0f)
                            // Solid up to sWL, then fading out over eW, cut off at the fill position
                            fillEdgeBrush.setEdge(sWL, eW)
                            clipRect(left = 0f, top = 0f, right = fXL, bottom = charHeight) {
                                drawText(letterLayouts[i], brush = fillEdgeBrush, alpha = 1f)
                            }
                        }
                    }
                    lineCurrentPushes[lineIdx] += charWidth * (charScaleX - 1f)
                }
            }
        }
    }
}
