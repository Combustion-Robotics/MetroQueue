package com.metrolist.music.ui.vibe.scene

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val SKY: Byte = 0
private const val MOON: Byte = 1
private const val WATER: Byte = 2
private const val LAND: Byte = 3
private const val FRONT: Byte = 4
private const val OPAQUE = -0x1000000
private const val WHITE = 0xFFFFFF
private const val INK = 0x1B1530
private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
private val BLOSSOM = intArrayOf(0xB45A8C, 0xD67AA5, 0xEC9CBF, 0xF7BED4, 0xFFE3EE)
private val NEON = intArrayOf(0xFF6FB5, 0x6FE8FF, 0xB98CFF)
private val BULBS = intArrayOf(0xFFD27A, 0xFF8FB8, 0x8FE8FF, 0xFFB36B)
private val WINDOWS = intArrayOf(0xFFD27A, 0xFFB86B, 0xFFE8B0, 0xFFD27A, 0x9FD3FF)
private const val WINDOW_LIGHT = 0xFFD27A
private const val BEACON = 0xFF4D6D
private val CAT = arrayOf("1.1....", "111....", "212....", "111....", ".11...1", "1111..1", "11111.1", "1111111", "111111.")

internal fun hash(x: Int, y: Int, seed: Int): Float {
    var h = x * 374761393 + y * 668265263 + seed * 1442695041
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777216f
}

private fun smooth(f: Float) = f * f * (3 - 2 * f)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

internal fun noise2(x: Float, y: Float, seed: Int): Float {
    val i = floor(x).toInt()
    val j = floor(y).toInt()
    val u = smooth(x - i)
    val a = lerp(hash(i, j, seed), hash(i + 1, j, seed), u)
    val b = lerp(hash(i, j + 1, seed), hash(i + 1, j + 1, seed), u)
    return lerp(a, b, smooth(y - j))
}

internal fun fbm(x: Float, seed: Int) =
    noise2(x, 0f, seed) * 0.6f + noise2(x * 2.3f, 0f, seed + 1) * 0.28f + noise2(x * 5.1f, 0f, seed + 2) * 0.12f

/** Blends two 0xRRGGBB colours; the result is always opaque. */
internal fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = ((a shr s and 255) + ((b shr s and 255) - (a shr s and 255)) * t).toInt() shl s
    return OPAQUE or ch(16) or ch(8) or ch(0)
}

private fun bayer(x: Int, y: Int) = (BAYER[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f

/** Ordered-dithers a 0..1 amount to sixths, so soft blends still read as crisp pixel steps. */
private fun dq(a: Float, x: Int, y: Int) = min(floor(a.coerceIn(0f, 1f) * 6 + bayer(x, y)), 6f) / 6f

/** Dithered gradient through [c] (t = 0..1) with three in-between tones per step. */
private fun ramp(c: IntArray, t: Float, x: Int, y: Int): Int {
    val n = c.size - 1
    val k = min((t.coerceIn(0f, 1f) * n * 4 + bayer(x, y)).toInt(), n * 4)
    val i = min(k / 4, n - 1)
    return mix(c[i], c[i + 1], (k - i * 4) / 4f)
}

private fun wrap(v: Double, m: Int): Int {
    val r = v % m
    return (if (r < 0) r + m else r).toInt()
}

/**
 * Paints a [SceneSpec] into a low-res ARGB buffer. Static layers are painted once into [base];
 * [render] copies them into [pixels] and adds the animated bits for [time] without allocating.
 */
internal class SceneRenderer(private val spec: SceneSpec, val w: Int, val h: Int) {
    val pixels = IntArray(w * h)
    var time = 0.0

    private val base = IntArray(w * h)
    private val depth = ByteArray(w * h)
    private val sky = spec.sky
    private val seed = spec.seed
    private val rnd = Random(seed)
    private val portrait = h > w

    // Sizes are in scene pixels; the short side is ~180 so things keep their size in both orientations.
    private val horizon = h - max(60, (h * 0.3f).toInt())
    private val haze = sky.bands[4]
    private val near = sky.land
    private val snow = mix(WHITE, sky.bands[1], if (sky.night) 0.55f else 0.12f)
    private val snowHi = mix(snow, sky.glow, if (sky.night) 0.12f else 0.4f)
    private val snowShade = mix(snow, sky.bands[0], if (sky.night) 0.35f else 0.45f)
    private val lightDir = if (sky.bodyX >= 0.5f) 1 else -1
    private val lightsOn = sky != Sky.Day
    private val litP = if (sky.night) 0.42f else if (sky == Sky.Day) 0.04f else 0.28f
    private val bodyCx = (w * sky.bodyX).toInt()
    private val bodyCy = (h * sky.bodyY).toInt()
    private val bodyR = if (sky.body == Body.Sun) (if (sky.bodyY > 0.4f) 15 else 9) else 9
    private var stamp = 0

    private val snowFlock = flock(Particle.Snow, w * h / 300)
    private val petalFlock = flock(Particle.Petals, w * h / 800)
    private val rainFlock = flock(Particle.Rain, w * h / 380)
    private val fireflyFlock = flock(Particle.Fireflies, 14 + w * h / 6000)
    private val stars = IntArray(if (Particle.Stars in spec.particles) w * horizon / 200 else 0)
    private val starA = FloatArray(stars.size)
    private var starCount = 0
    private val beacons = IntArray(16)
    private var beaconCount = 0
    private val beaconDim = mix(BEACON, near, 0.5f)
    private val chimneys = IntArray(4)
    private var chimneyCount = 0

    private val cloudN = if (Particle.Clouds in spec.particles) 4 else 0
    private val cloudW = IntArray(cloudN) { 30 + rnd.nextInt(34) }
    private val cloudH = IntArray(cloudN) { 12 + rnd.nextInt(6) }
    private val cloudX = FloatArray(cloudN) { rnd.nextFloat() * w }
    private val cloudY = IntArray(cloudN) { (h * 0.05f + rnd.nextFloat() * horizon * 0.4f).toInt() }
    private val cloudV = FloatArray(cloudN) { 1.2f + rnd.nextFloat() * 2.5f }
    private val cloudPix = Array(cloudN) { cloudSprite(cloudW[it], cloudH[it]) }
    private val cloudTones = run {
        val body = mix(sky.bands[1], WHITE, if (sky.night) 0.12f else 0.6f)
        intArrayOf(0, mix(body, sky.bands[0], 0.3f), body, mix(body, if (sky.night) sky.glow else WHITE, 0.45f))
    }

    private var shootAt = 3.0 + rnd.nextDouble() * 6
    private var shootX = 0f
    private var shootY = 0f
    private var shootDx = 0f
    private var shootDy = 0f
    private var boltAt = 6.0 + rnd.nextDouble() * 10
    private val bolt = IntArray(h)
    private var boltLen = 0

    init {
        paintSky()
        when (spec.layout) {
            Layout.Forest -> forest()
            Layout.Lake -> lake()
            Layout.City -> city()
            Layout.Blossoms -> blossoms()
            Layout.Hills -> hills()
        }
        when (spec.foreground) {
            Foreground.None -> Unit
            Foreground.Rails -> rails()
            Foreground.Shore -> shore()
            Foreground.Rooftops -> rooftops()
            Foreground.Window -> window()
        }
        repeat(stars.size * 3) {
            if (starCount == stars.size) return@repeat
            val y = rnd.nextInt((horizon * 0.85f).toInt().coerceAtLeast(1))
            val i = y * w + rnd.nextInt(w)
            if (depth[i] != SKY) return@repeat
            // Fainter towards the brighter sky near the horizon; a few bright ones.
            starA[starCount] = (1f - y.toFloat() / horizon) * (if (rnd.nextFloat() < 0.15f) 1f else 0.6f)
            stars[starCount++] = i
        }
        aimShootingStar()
        render()
    }

    fun render() {
        val t = time
        base.copyInto(pixels)
        if (spec.layout == Layout.Lake) shimmer(t)
        drawStars(t)
        if (Particle.ShootingStars in spec.particles) shootingStar(t)
        drawClouds(t)
        blink(t)
        smoke(t)
        if (Particle.Lightning in spec.particles) lightning(t)
        snowFlock?.let { snow(it, t) }
        petalFlock?.let { petals(it, t) }
        rainFlock?.let { rain(it, t) }
        fireflyFlock?.let { fireflies(it, t) }
    }

    // region static painting

    private fun put(x: Int, y: Int, c: Int, d: Byte = LAND) {
        if (x !in 0 until w || y !in 0 until h) return
        val i = y * w + x
        base[i] = c or OPAQUE
        depth[i] = d
    }

    private fun tint(x: Int, y: Int, c: Int, t: Float) {
        if (t > 0f && x in 0 until w && y in 0 until h) base[y * w + x] = mix(base[y * w + x], c, t)
    }

    private fun rect(x: Int, y: Int, rw: Int, rh: Int, c: Int, d: Byte = LAND) {
        for (yy in y until y + rh) for (xx in x until x + rw) put(xx, yy, c, d)
    }

    private fun line(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        val n = max(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(1)
        for (k in 0..n) put(x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n, c)
    }

    /** Soft round light (window glow, lamp halo) faded with ordered dithering. */
    private fun halo(cx: Int, cy: Int, r: Int, c: Int, a: Float) {
        for (dy in -r..r) for (dx in -r..r) {
            val d = 1f - sqrt((dx * dx + dy * dy).toFloat()) / r
            if (d > 0f) tint(cx + dx, cy + dy, c, dq(d * d * a, cx + dx, cy + dy))
        }
    }

    /** A band of ground mist tinting everything towards the horizon colour. */
    private fun mist(cy: Int, half: Int, a: Float) {
        for (y in cy - half..cy + half) {
            val k = a * (1f - abs(y - cy).toFloat() / (half + 1))
            for (x in 0 until w) tint(x, y, haze, dq(k * (0.6f + 0.8f * noise2(x * 0.04f, y * 0.2f, seed + 77)), x, y))
        }
    }

    private fun paintSky() {
        val lowSun = sky.body == Body.Sun && sky.bodyY > 0.4f
        val glowR = if (lowSun) w * 0.6f else if (sky.night) 24f + bodyR * 2 else 34f + bodyR * 3
        val glowA = when {
            sky.body == Body.None -> 0f
            sky.night -> 0.18f
            lowSun -> 0.75f
            else -> 0.5f
        }
        for (y in 0 until h) for (x in 0 until w) {
            var c = ramp(sky.bands, y.toFloat() / horizon, x, y)
            if (glowA > 0f) {
                val dx = (x - bodyCx).toFloat()
                val dy = (y - bodyCy) * if (lowSun) 2.2f else 1f
                val g = 1f - sqrt(dx * dx + dy * dy) / glowR
                if (g > 0f) c = mix(c, sky.glow, dq(g * g * glowA, x, y))
            }
            put(x, y, c, SKY)
        }
        if (sky.body != Body.None) celestial()
        if (!sky.night) wisps()
    }

    private fun celestial() {
        val r = bodyR
        val g = sky.glow
        for (dy in -r..r) for (dx in -r..r) {
            val d2 = dx * dx + dy * dy
            if (d2 > r * r + r) continue
            val x = bodyCx + dx
            val y = bodyCy + dy
            val c = if (sky.body == Body.Sun) {
                mix(mix(g, WHITE, 0.5f), mix(g, sky.accent, 0.6f), dq((dy + r) / (2f * r), x, y))
            } else {
                // Lit from the upper right, with a soft terminator, a few darker maria and a bright limb.
                val light = (dx * 0.55f - dy * 0.65f) / r
                var m = mix(g, sky.bands[2], dq(-0.15f - light, x, y) * 0.6f)
                if (noise2(dx * 0.42f + 3f, dy * 0.42f + 3f, seed + 50) > 0.62f) m = mix(m, sky.bands[2], 0.22f)
                if (d2 >= (r - 1) * (r - 1) && light > 0.2f) m = mix(m, WHITE, 0.45f)
                m
            }
            put(x, y, c, MOON)
        }
    }

    /** Thin streaks of cloud lit by the low sun, like brush strokes across the sky. */
    private fun wisps() {
        val c = if (sky == Sky.Day) WHITE else mix(sky.glow, sky.accent, 0.4f)
        val y0 = (horizon * 0.1f).toInt()
        for (y in y0 until horizon - 2) {
            val env = sin(PI.toFloat() * (y - y0) / (horizon - y0))
            for (x in 0 until w) {
                val n = noise2(x * 0.012f, y * 0.17f, seed + 40) * 0.7f + noise2(x * 0.05f, y * 0.33f, seed + 41) * 0.3f
                val a = (n - 0.56f) * 3f * env
                if (a > 0f) tint(x, y, c, dq(a * 0.5f, x, y))
            }
        }
    }

    private fun ground(top: Int) {
        val tones = if (spec.snowy) {
            intArrayOf(snowShade, snow, snowHi)
        } else {
            intArrayOf(mix(sky.ground, near, 0.45f), sky.ground, mix(sky.ground, sky.glow, 0.3f))
        }
        for (y in top until h) {
            val k = (y - top).toFloat() / (h - top)
            for (x in 0 until w) {
                val n = noise2(x * 0.03f, y * 0.09f, seed + 9) * 0.7f + noise2(x * 0.11f, y * 0.25f, seed + 10) * 0.3f
                var c = ramp(tones, 0.5f + (n - 0.5f) * 1.7f, x, y)
                c = mix(c, haze, dq((1f - k) * (1f - k) * 0.4f, x, y))
                val sparkle = hash(x, y, seed + 11)
                if (spec.snowy && sparkle > 0.996f) c = mix(c, WHITE, 0.7f)
                if (!spec.snowy && sparkle > 0.985f) c = mix(c, if (sparkle > 0.995f && !sky.night) BLOSSOM[3] else near, 0.4f)
                put(x, y, c)
            }
        }
    }

    /**
     * A fir tree: overlapping, drooping tiers that widen downwards, snow (or rim light) along their upper
     * side, a darker side away from the light and a shadow line under each tier. [hz] fades it into the haze.
     */
    private fun pine(cx: Int, baseY: Int, height: Int, hz: Float) {
        val body = mix(near, haze, hz)
        val dark = mix(mix(near, INK, 0.32f), haze, hz)
        val lit = mix(mix(near, sky.glow, if (sky.night) 0.22f else 0.42f), haze, hz)
        val capLit = mix(snowHi, haze, hz * 0.6f)
        val capShade = mix(mix(snowShade, near, 0.22f), haze, hz)
        val bark = mix(mix(0x3E2A3A, near, 0.45f), haze, hz)
        // pixels is unused until the first render(), so it doubles as a "painted by this tree" mask.
        val id = ++stamp
        val trunk = max(2, height / 10)
        val crown = height - trunk
        val tiers = (crown / 11).coerceIn(2, 8)
        val th = crown / (tiers * 0.5f + 0.6f)
        val maxHalf = height * (0.27f + hash(cx, height, seed + 70) * 0.08f)
        val top = baseY - height
        val tw = max(1, height / 40)
        rect(cx - tw / 2, baseY - trunk - min(4, height / 8), tw, trunk + min(4, height / 8), bark)
        for (i in tiers - 1 downTo 0) {
            // Each tier is a chevron: snowy upper slope from the stem out to thin, drooping branch tips.
            val ty = top + 2 + (i * th * 0.5f).toInt()
            val grow = 0.18f + 0.82f * (i + 1f) / tiers
            val hl = maxHalf * grow * (0.85f + hash(cx, i, seed + 71) * 0.3f)
            val hr = maxHalf * grow * (0.85f + hash(cx, i, seed + 76) * 0.3f)
            for (dx in -hl.toInt()..hr.toInt()) {
                val x = cx + dx
                val hw = if (dx < 0) hl else hr
                val u = abs(dx) / (hw + 0.5f)
                val bump = if (u > 0.3f && hash(x, ty, seed + 73) < 0.2f) 1 else 0
                val finger = if (height >= 24 && u > 0.5f && (abs(dx) + i) % 3 == 0) 2 else 0
                val y0 = ty + (th * (0.3f * u + 0.7f * u * u)).toInt() + bump
                val y1 = max(y0, ty + (th * (0.5f + 0.55f * u)).toInt() - finger)
                val side = dx * lightDir / hw
                val cap = when {
                    !spec.snowy -> 0
                    height < 16 || hz > 0.6f -> 1
                    else -> 1 + (if (u < 0.8f) 1 else 0) + (if (hash(x, i, seed + 75) > 0.5f) 1 else 0) + (if (height > 80 && u < 0.5f) 1 else 0)
                }
                for (y in y0..y1) {
                    val fromTop = y - y0
                    val under = fromTop.toFloat() / max(1, y1 - y0)
                    val c = when {
                        fromTop < cap -> if (side > -0.35f) capLit else capShade
                        !spec.snowy && fromTop == 0 && side > -0.2f -> lit
                        side > 0.45f && fromTop < 3 -> lit
                        height >= 20 && under > 0.6f && u < 0.85f -> dark
                        side < -0.2f && (height < 30 || bayer(x, y) < -side * 1.3f) -> dark
                        spec.snowy && fromTop < cap + 3 && hash(x, y, seed + 74) < 0.14f -> if (side > -0.35f) capLit else capShade
                        hash(x, y, seed + 78) < 0.07f -> dark
                        else -> body
                    }
                    put(x, y, c)
                    if (x in 0 until w && y in 0 until h) pixels[y * w + x] = id
                }
                // Shadow line where this tier overhangs the one below it.
                val sy = y1 + 1
                if (u < 0.9f && x in 0 until w && sy in 0 until h && pixels[sy * w + x] == id) put(x, sy, dark)
            }
        }
        put(cx, top, if (spec.snowy) capLit else lit)
        put(cx, top + 1, if (spec.snowy) capLit else body)
        if (hz < 0.5f && spec.snowy) {
            val sw = maxHalf * 0.9f
            for (dy in 0..2) for (dx in -sw.toInt()..sw.toInt()) {
                val x = cx + dx - lightDir * (sw / 3).toInt()
                val y = baseY - 1 + dy
                val a = 0.55f * (1f - abs(dx) / sw - dy / 3f)
                if (a > 0f && x in 0 until w && y in 0 until h && pixels[y * w + x] != id) tint(x, y, snowShade, dq(a, x, y))
            }
        }
    }

    private fun pineRow(baseY: Int, minH: Int, varH: Int, gap: Int, varGap: Int, hz: Float, salt: Int, clearX: Int, clearW: Int) {
        var x = -4 + (hash(salt, 0, seed) * gap).toInt()
        while (x < w + 4) {
            val ht = minH + (varH * (0.45f * hash(x, salt, seed) + 0.55f * fbm(x * 0.03f, seed + salt))).toInt()
            if (abs(x - clearX) > clearW + ht / 4) pine(x, baseY + (hash(x, salt + 1, seed) * 3).toInt(), ht, hz)
            x += gap + (hash(x, salt + 2, seed) * varGap).toInt()
        }
    }

    private fun ridge(x: Int, baseY: Int, amp: Float, freq: Float, s: Int) = baseY - (fbm(x * freq, seed + s) * amp).toInt()

    /** Rolling hill with a lit crest that fades down its face. */
    private fun hill(baseY: Int, amp: Float, freq: Float, c: Int, s: Int) {
        val body = if (spec.snowy) mix(snow, c, 0.35f) else c
        val crest = if (spec.snowy) mix(snowHi, c, 0.2f) else mix(c, sky.glow, 0.3f)
        val tones = intArrayOf(mix(body, INK, 0.12f), body, crest)
        for (x in 0 until w) {
            val top = ridge(x, baseY, amp, freq, s)
            val face = (ridge(x - 3, baseY, amp, freq, s) - ridge(x + 3, baseY, amp, freq, s)) * -lightDir * 0.06f
            for (y in top until h) {
                val d = (y - top).toFloat()
                put(x, y, ramp(tones, 0.45f + face + max(0f, 1f - d / 5f) * 0.5f - min(d / 40f, 0.25f), x, y))
            }
        }
    }

    /** Ridged peaks with sunlit faces, a dithered snowline and mist pooling in the valleys. */
    private fun mountains(baseY: Int, amp: Float, freq: Float, s: Int, hz: Float) {
        val rock = mix(near, haze, hz)
        val litRock = mix(rock, sky.glow, 0.24f)
        val cap = mix(snowHi, haze, hz * 0.5f)
        val capShade = mix(snowShade, rock, 0.45f)
        fun peak(x: Int): Float {
            val r = 1f - abs(noise2(x * freq, 0f, seed + s) * 2f - 1f)
            return baseY - amp * (0.2f + 0.8f * r * r) - fbm(x * freq * 4f, seed + s + 1) * amp * 0.2f
        }
        val rocks = intArrayOf(mix(rock, INK, 0.2f), rock, litRock)
        val snows = intArrayOf(mix(capShade, INK, 0.1f), capShade, cap)
        for (x in 0 until w) {
            val py = peak(x).toInt()
            val face = ((peak(x + 4) - peak(x - 4)) * lightDir * 0.12f).coerceIn(-0.5f, 0.5f)
            for (y in py until baseY) {
                val alt = (baseY - y) / amp
                val snowLine = 0.68f + (noise2(x * 0.15f, y * 0.25f, seed + s + 2) - 0.5f) * 0.3f
                // Faces turned to the light stay bright near the ridge; erosion streaks run down the slopes.
                var tone = 0.5f + face * max(0.3f, 1f - (y - py) / 16f)
                if (noise2(x * 0.4f, y * 0.07f, seed + s + 3) > 0.68f) tone -= 0.3f
                var c = ramp(if (alt > snowLine) snows else rocks, tone, x, y)
                if (y == py) c = mix(c, sky.glow, 0.3f)
                put(x, y, mix(c, haze, dq((0.4f - alt) * 1.2f, x, y)))
            }
        }
    }

    private fun forest() {
        ground(horizon)
        val railX = if (spec.foreground == Foreground.Rails) (w * 0.68f).toInt() else -999
        val cabinX = (w * if (portrait) 0.5f else 0.43f).toInt()
        pineRow(horizon + 1, 6, 10, 2, 3, 0.74f, 1, -999, 0)
        mist(horizon, 4, 0.35f)
        pineRow(horizon + 3, 12, 18, 4, 5, 0.5f, 2, railX, 4)
        mist(horizon + 4, 4, 0.25f)
        house(cabinX, horizon + 10, 0.2f)
        pineRow(horizon + 8, 22, 30, 9, 12, 0.22f, 3, cabinX + 7, 12)
        if (portrait) {
            pine((w * 0.93f).toInt(), h - 58, 80, 0.1f)
            pine((w * 0.3f).toInt(), h - 46, 96, 0.06f)
            pine((w * 0.08f).toInt(), h - 12, 160, 0f)
        } else {
            pine((w * 0.86f).toInt(), h - 36, 68, 0.1f)
            pine((w * 0.17f).toInt(), h - 24, 94, 0.06f)
            pine((w * 0.95f).toInt(), h - 12, 112, 0f)
            pine((w * 0.05f).toInt(), h - 6, 134, 0f)
        }
    }

    private fun lake() {
        val top = horizon
        mountains(top - 2, if (portrait) 72f else 60f, 0.012f, 4, 0.6f)
        mountains(top + 1, 30f, 0.022f, 5, 0.36f)
        mist(top - 2, 5, 0.3f)
        val cabinX = (w * if (portrait) 0.18f else 0.24f).toInt()
        var x = -2
        while (x < w + 2) {
            if (x !in cabinX - 4..cabinX + 18) pine(x, top + 1, 7 + (hash(x, 6, seed) * 12).toInt(), 0.32f)
            x += 2 + (hash(x, 7, seed) * 4).toInt()
        }
        house(cabinX, top + 1, 0.15f)
        // The water mirrors everything above the shoreline, broken up by ripples.
        val tint = mix(sky.bands[1], near, 0.4f)
        for (y in top + 1 until h) {
            val k = (y - top).toFloat() / (h - top)
            val wob = ((noise2(y * 0.6f, 0f, seed + 6) - 0.5f) * (2f + k * 5f)).toInt()
            val sy = (2 * top - y + 1).coerceIn(0, h - 1)
            for (xx in 0 until w) {
                var c = mix(base[sy * w + (xx + wob).coerceIn(0, w - 1)], tint, 0.25f + k * 0.4f)
                val rip = noise2(xx * 0.05f, y * 0.7f, seed + 8)
                if (rip > 0.7f) c = mix(c, sky.bands[4], 0.2f) else if (rip < 0.28f) c = mix(c, INK, 0.12f)
                put(xx, y, c, WATER)
            }
        }
    }

    private fun city() {
        val street = h - 26
        val tall = if (portrait) 1.7f else 1f
        val farC = mix(near, haze, 0.5f)
        var x = -2
        while (x < w) {
            val bw = 6 + (hash(x, 7, seed) * 11).toInt()
            val top = street - 34 - ((fbm(x * 0.02f, seed + 7) * 56 + hash(x, 8, seed) * 18) * tall).toInt()
            building(x, top, bw, street, farC, true)
            x += bw
        }
        // City glow rising behind the nearer blocks.
        for (y in street - 40 until street) for (xx in 0 until w) tint(xx, y, sky.bands[5], dq((y - street + 40) / 40f * 0.4f, xx, y))
        x = -4
        while (x < w) {
            val bw = 12 + (hash(x, 10, seed) * 16).toInt()
            val top = street - 20 - (hash(x, 11, seed) * h * 0.3f * tall).toInt()
            building(x, top, bw, street, near, false)
            if (hash(x, 12, seed) < 0.4f) neonSign(x + bw - 4, top + 5, 8 + (hash(x, 13, seed) * 8).toInt(), NEON[(hash(x, 14, seed) * NEON.size).toInt()])
            x += bw + (hash(x, 16, seed) * 6).toInt()
        }
        var lx = 10 + (hash(2, 17, seed) * 30).toInt()
        while (lx < w) {
            lamp(lx, street)
            lx += 48 + (hash(lx, 18, seed) * 30).toInt()
        }
        // Wet street mirrors what stands on it.
        val road = mix(near, INK, 0.3f)
        for (y in street until h) for (xx in 0 until w) {
            val src = base[(street - 1 - (y - street) * 2).coerceAtLeast(0) * w + xx]
            put(xx, y, if (bayer(xx, y) < 0.6f) mix(road, src, 0.45f) else road)
        }
        for (xx in 0 until w) put(xx, street, mix(road, sky.accent, 0.2f))
    }

    private fun building(x: Int, top: Int, bw: Int, bottom: Int, c: Int, far: Boolean) {
        val r = hash(x, top, seed + 80)
        val rim = mix(c, sky.accent, if (far) 0.12f else 0.25f)
        rect(x, top, bw, bottom - top, c)
        val ex = if (lightDir > 0) x + bw - 1 else x
        for (y in top until bottom) put(ex, y, rim)
        when {
            r < 0.2f -> rect(x - 1, top - 1, bw + 2, 1, rim)
            r < 0.4f && bw > 9 -> {
                rect(x + 2, top - 6, bw - 4, 6, c)
                rect(x + 2, top - 6, bw - 4, 1, rim)
            }
            r < 0.52f && !far && bw > 8 -> waterTower(x + bw / 2 - 2, top, c, rim)
            r < 0.66f -> {
                line(x + bw / 2, top - 9, x + bw / 2, top - 1, c)
                beacon(x + bw / 2, top - 10)
            }
            !far -> {
                rect(x + 2, top - 2, 3, 2, c)
                if (bw > 10) rect(x + bw - 5, top - 3, 2, 3, c)
            }
        }
        if (far) {
            for (wy in top + 3 until bottom - 1 step 3) for (wx in x + 1 until x + bw - 1 step 2) {
                val k = hash(wx, wy, seed + 1)
                if (k < litP * 0.5f) put(wx, wy, mix(WINDOWS[(k * 97).toInt() % WINDOWS.size], c, 0.45f))
            }
            return
        }
        val dim = mix(c, sky.bands[2], 0.3f)
        for (wy in top + 3 until bottom - 7 step 4) {
            // Whole floors tend to be awake together, like real buildings at night.
            val awake = hash(x, wy, seed + 81) < litP * 1.1f
            for (wx in x + 2 until x + bw - 3 step 4) {
                val k = hash(wx, wy, seed)
                val on = k < if (awake) 0.75f else litP * 0.5f
                val wc = if (on) WINDOWS[(k * 97).toInt() % WINDOWS.size] else dim
                put(wx, wy, wc)
                put(wx + 1, wy, wc)
                put(wx, wy + 1, if (on) mix(wc, c, 0.3f) else wc)
                put(wx + 1, wy + 1, if (on) mix(wc, c, 0.3f) else wc)
            }
        }
        // Shopfront with an awning at street level.
        if (r > 0.45f && bw > 10) {
            val awning = mix(NEON[(r * 31).toInt() % NEON.size], c, 0.35f)
            for (xx in x + 1 until x + bw - 1) put(xx, bottom - 6, if (xx % 2 == 0) awning else mix(awning, WHITE, 0.3f))
            val glass = if (lightsOn) mix(WINDOW_LIGHT, c, 0.4f) else dim
            for (xx in x + 2 until x + bw - 2) for (yy in bottom - 5 until bottom - 1) put(xx, yy, if ((xx - x) % 4 == 1) c else if (yy == bottom - 5) mix(glass, WHITE, 0.15f) else glass)
        }
    }

    private fun waterTower(x: Int, roof: Int, c: Int, rim: Int) {
        rect(x, roof - 3, 1, 3, c)
        rect(x + 4, roof - 3, 1, 3, c)
        rect(x, roof - 4, 5, 1, c)
        rect(x, roof - 9, 5, 5, c)
        for (y in roof - 9 until roof - 4) put(if (lightDir > 0) x + 4 else x, y, rim)
        rect(x, roof - 7, 5, 1, mix(c, INK, 0.3f))
        rect(x - 1, roof - 10, 7, 1, rim)
        rect(x + 1, roof - 11, 3, 1, c)
        put(x + 2, roof - 12, c)
    }

    private fun beacon(x: Int, y: Int) {
        put(x, y, beaconDim)
        if (beaconCount < beacons.size && x in 0 until w && y in 0 until h) beacons[beaconCount++] = y * w + x
    }

    private fun lamp(x: Int, gy: Int) {
        val pole = mix(near, INK, 0.4f)
        halo(x + 2, gy - 13, 5, WINDOW_LIGHT, 0.6f)
        halo(x + 2, gy, 6, WINDOW_LIGHT, 0.35f)
        rect(x, gy - 15, 1, 15, pole)
        rect(x, gy - 15, 3, 1, pole)
        put(x + 2, gy - 14, mix(WINDOW_LIGHT, WHITE, 0.4f))
    }

    private fun neonSign(x: Int, y: Int, len: Int, c: Int) {
        for (yy in y - 2..y + len + 1) for (xx in x - 2..x + 3) tint(xx, yy, c, 0.18f)
        for (yy in y until y + len) for (xx in x..x + 1) put(xx, yy, if ((yy - y) % 3 == 2) mix(c, near, 0.6f) else c)
    }

    private fun blossomTree(cx: Int, baseY: Int, r: Int, hz: Float) {
        val bark = mix(mix(0x5A3C4E, near, 0.25f), haze, hz)
        val barkLit = mix(bark, sky.glow, 0.3f)
        val crownY = baseY - r - r / 2
        val tw = max(1, r / 6)
        for (y in crownY until baseY) {
            val k = (y - crownY).toFloat() / (baseY - crownY)
            val wd = tw + (k * k * tw * 1.5f).toInt()
            val off = (sin(k * 1.8f + cx) * r * 0.06f).roundToInt() - wd / 2
            for (dx in 0 until wd) put(cx + off + dx, y, if (wd > 1 && dx == (if (lightDir > 0) wd - 1 else 0)) barkLit else bark)
        }
        for (k in 0 until 4) {
            val a = -PI / 2 + (k - 1.5) * 0.6 + (hash(cx, k + 30, seed) - 0.5) * 0.3
            line(cx, crownY + r / 3, cx + (cos(a) * r * 0.9).toInt(), crownY + r / 3 + (sin(a) * r * 0.7).toInt(), bark)
        }
        // A wide dome of overlapping blossom puffs, painted from the rim inwards so the sunlit top sits in front.
        val n = 9 + r / 2
        for (m in 0 until n) {
            val k = if (m % 2 == 0) m / 2 else n - 1 - m / 2
            val u = (k + hash(cx, k, seed)) / n
            val dome = sin(u * PI).toFloat()
            val px = cx + ((u - 0.5f) * r * 2.1f).toInt()
            val py = crownY + r / 4 - (dome * r * 0.75f + hash(cx, k + 10, seed) * r * 0.3f).toInt()
            val pr = max(2, (r * (0.36f + 0.22f * dome + hash(cx, k + 20, seed) * 0.15f)).toInt())
            puff(px, py, pr, hz, (dome - 0.5f) * 0.25f)
        }
        if (hz < 0.3f) {
            val rx = r * 1.3f
            for (dy in 0..3) for (dx in -rx.toInt()..rx.toInt()) {
                if (hash(cx + dx, baseY + dy, seed + 5) < 0.22f * (1f - abs(dx) / rx)) put(cx + dx, baseY + dy - 1, BLOSSOM[2 + (dx and 1)])
            }
        }
    }

    private fun puff(cx: Int, cy: Int, r: Int, hz: Float, bias: Float) {
        val night = if (sky.night) 0.45f else 0f
        for (dy in -r..r) for (dx in -r..r) {
            val x = cx + dx
            val y = cy + dy
            if (dx * dx + dy * dy > r * r * (0.7f + 0.45f * hash(x, y, seed + 3))) continue
            val tone = 0.6f + bias - dy * 0.32f / r + dx * lightDir * 0.12f / r + (hash(x, y, seed + 4) - 0.5f) * 0.18f
            put(x, y, mix(mix(ramp(BLOSSOM, tone, x, y), near, night), haze, hz))
        }
    }

    private fun blossoms() {
        val g = horizon + 2
        hill(g - 8, 26f, 0.011f, mix(near, haze, 0.72f), 11)
        // Pink haze of distant groves along the second ridge.
        var x = 0
        while (x < w) {
            if (fbm(x * 0.04f, seed + 13) > 0.5f) puff(x, ridge(x, g, 12f, 0.02f, 12) - 1, 2 + (hash(x, 14, seed) * 2).toInt(), 0.55f, 0f)
            x += 3
        }
        hill(g, 12f, 0.02f, mix(near, haze, 0.48f), 12)
        ground(g + 6)
        x = (hash(0, 17, seed) * 20).toInt()
        while (x < w) {
            blossomTree(x, g + 8 + (hash(x, 18, seed) * 6).toInt(), 7 + (hash(x, 19, seed) * 5).toInt(), 0.32f)
            x += 26 + (hash(x, 20, seed) * 22).toInt()
        }
        x = (hash(1, 17, seed) * 30).toInt() + if (portrait) 50 else 30
        while (x < w - 20) {
            blossomTree(x, g + (h - g) / 2 + (hash(x, 25, seed) * 8).toInt(), 10 + (hash(x, 26, seed) * 5).toInt(), 0.12f)
            x += (if (portrait) 34 else 50) + (hash(x, 27, seed) * 40).toInt()
        }
        if (!portrait) blossomTree((w * 0.9f).toInt(), h - 12, 22, 0f)
        blossomTree((w * 0.1f).toInt(), h - 8, if (portrait) 32 else 28, 0f)
    }

    private fun hills() {
        val step = (h - horizon) / 4 + 6
        for (k in 0..3) {
            val hz = 0.72f - k * 0.24f
            val baseY = horizon - 14 + k * step
            val freq = 0.01f + k * 0.004f
            hill(baseY, 18f, freq, mix(near, haze, hz), 20 + k)
            var x = (hash(k, 21, seed) * 9).toInt()
            while (k in 1..2 && x < w) {
                if (fbm(x * 0.05f, seed + 22 + k) > 0.5f) {
                    pine(x, ridge(x, baseY, 18f, freq, 20 + k) + 3 + (hash(x, 23, seed) * 3).toInt(), 6 + k * 3 + (hash(x, 24, seed) * 6).toInt(), hz)
                }
                x += 3 + (hash(x, 25, seed) * 3).toInt()
            }
            if (k == 3) for (hx in intArrayOf((w * 0.22f).toInt(), (w * 0.64f).toInt())) house(hx, ridge(hx + 7, baseY, 18f, freq, 23) + 5, 0f)
            if (k < 3) mist(baseY + step / 2, 3, 0.18f)
        }
    }

    /** Little cottage: lit gable front, receding side wall, chimney and warm windows after dark. */
    private fun house(x: Int, gy: Int, hz: Float) {
        val wall = mix(mix(0x8A6A78, near, 0.45f), haze, hz)
        val front = mix(wall, sky.glow, 0.18f)
        val side = mix(wall, INK, 0.25f)
        val roof = if (spec.snowy) mix(snowHi, haze, hz * 0.5f) else mix(mix(0x7A3F4C, near, 0.35f), haze, hz)
        val glass = if (lightsOn) WINDOW_LIGHT else mix(wall, sky.bands[2], 0.5f)
        if (lightsOn) halo(x + 6, gy - 4, if (sky.night) 9 else 12, WINDOW_LIGHT, if (sky.night) 0.28f else 0.4f)
        rect(x, gy - 7, 8, 7, front)
        rect(x + 8, gy - 7, 6, 7, side)
        for (r in 0..4) {
            for (dx in 3 - r..4 + r) put(x + dx, gy - 12 + r, if (dx == 3 - r || dx == 4 + r) roof else front)
            for (dx in 5 + r..14) put(x + dx, gy - 12 + r, if (r == 4) mix(roof, INK, 0.2f) else roof)
        }
        rect(x + 10, gy - 15, 2, 4, side)
        if (spec.snowy) rect(x + 10, gy - 15, 2, 1, roof)
        if (chimneyCount < chimneys.size && x + 10 in 0 until w && gy - 16 in 0 until h) chimneys[chimneyCount++] = (gy - 16) * w + x + 10
        rect(x + 1, gy - 5, 2, 2, glass)
        rect(x + 9, gy - 5, 3, 2, glass)
        rect(x + 5, gy - 4, 2, 4, mix(wall, INK, 0.45f))
        if (lightsOn) for (dy in 0..1) for (dx in 0..4) tint(x + dx, gy + dy, WINDOW_LIGHT, 0.2f)
    }

    // endregion
    // region foregrounds

    private fun rails() {
        val top = horizon + 3
        val farX = w * 0.68f
        val nearX = w * if (portrait) 0.4f else 0.34f
        val gauge = min(w, h) * 0.3f
        val rail = mix(near, INK, 0.45f)
        val sheen = mix(rail, sky.glow, 0.5f)
        val tie = mix(rail, if (spec.snowy) snowShade else sky.ground, 0.7f)
        val trough = if (spec.snowy) snowShade else mix(sky.ground, near, 0.35f)
        var pl = -1
        var pr = -1
        var wasTie = false
        for (y in top until h) {
            val t = (y - top + 1f) / (h - top)
            // sqrt bends the far end sideways, so the track curves into the trees.
            val cx = farX + (nearX - farX) * sqrt(t)
            val half = 1f + gauge * t / 2
            val l = (cx - half).roundToInt()
            val r = (cx + half).roundToInt()
            val bw = half * 1.4f + 1
            for (x in (cx - bw).toInt()..(cx + bw).toInt()) {
                val e = abs(x - cx) / bw
                tint(x, y, trough, dq(0.6f * (1f - e * e), x, y))
            }
            val isTie = t > 0.1f && (3.2f / (t + 0.06f)) % 1f < 0.16f
            if (isTie) {
                val c = if (!wasTie && spec.snowy && t > 0.35f) mix(tie, snow, 0.5f) else tie
                for (x in (l - half * 0.18f).toInt()..(r + half * 0.18f).toInt()) put(x, y, c)
            }
            wasTie = isTie
            val thick = 1 + (t * 2.2f).toInt()
            val a0 = min(if (pl < 0) l else pl, l)
            val b0 = min(if (pr < 0) r else pr, r)
            for (x in a0..max(if (pl < 0) l else pl, l) + thick - 1) put(x, y, if (thick > 1 && x == a0) sheen else rail)
            for (x in b0..max(if (pr < 0) r else pr, r) + thick - 1) put(x, y, if (thick > 1 && x == b0) sheen else rail)
            pl = l
            pr = r
        }
    }

    private fun shore() {
        val c = mix(near, INK, 0.3f)
        val rim = if (spec.snowy) snow else mix(c, sky.glow, 0.2f)
        val reed = mix(c, sky.ground, 0.25f)
        val cattail = mix(0x5A3A30, near, 0.4f)
        for (x in 0 until w) {
            val ry = h - 18 + (fbm(x * 0.035f, seed + 30) * 12).toInt()
            for (y in ry until h) put(x, y, if (y < ry + 3 && bayer(x, y) < 0.5f) mix(c, rim, 0.3f) else c)
            put(x, ry, rim)
            if (hash(x, 31, seed) < 0.3f) {
                val rh = 3 + (hash(x, 32, seed) * 12).toInt()
                val lean = if (hash(x, 34, seed) < 0.5f) -1 else 1
                for (k in 1..rh) put(x + if (k > rh * 2 / 3) lean else 0, ry - k, reed)
                if (rh > 8 && hash(x, 33, seed) < 0.45f) rect(x + lean, ry - rh - 2, 1, 3, cattail)
            }
        }
        for (k in 0..1) {
            val rx = (w * (0.62f + k * 0.25f)).toInt()
            val ry = h - 14 + (fbm(rx * 0.035f, seed + 30) * 12).toInt() - 4
            val rw = 7 - k * 2
            for (dy in -rw / 2..rw / 2) for (dx in -rw..rw) {
                if (dx * dx / (rw * rw.toFloat()) + dy * dy / (rw * rw / 4f) > 1f) continue
                put(rx + dx, ry + dy, if (dy < 0 && dx * lightDir > -1) mix(c, sky.glow, 0.22f) else mix(c, INK, 0.1f))
            }
        }
    }

    private fun rooftops() {
        val c = mix(near, INK, 0.35f)
        val ledge = mix(c, sky.accent, 0.3f)
        var x = 0
        var catX = -1
        var catY = 0
        while (x < w) {
            val rw = 22 + (hash(x, 40, seed) * 30).toInt()
            val top = h - 12 - (hash(x, 41, seed) * 16).toInt()
            rect(x, top, rw, h - top, c)
            for (dx in 0 until rw) put(x + dx, top, ledge)
            if (hash(x, 42, seed) < 0.5f) {
                rect(x + 4, top - 5, 7, 5, c)
                rect(x + 4, top - 5, 7, 1, ledge)
                if (lightsOn) rect(x + 6, top - 3, 3, 2, mix(WINDOW_LIGHT, c, 0.2f))
            }
            if (hash(x, 43, seed) < 0.35f) {
                val ax = x + rw - 5
                line(ax, top - 14, ax, top, c)
                line(ax - 2, top - 11, ax + 2, top - 11, c)
                beacon(ax, top - 15)
            } else if (catX < 0 && x > w * 0.55f && rw > 26) {
                catX = x + rw - 12
                catY = top
            }
            x += rw
        }
        if (catX >= 0) {
            val eye = mix(0xE8F27A, c, 0.15f)
            for (r in CAT.indices) for (k in CAT[r].indices) {
                val ch = CAT[r][k]
                if (ch != '.') put(catX + k, catY - CAT.size + r, if (ch == '2' && lightsOn) eye else if (r == 0 || CAT[r - 1][k] == '.') ledge else c)
            }
        }
        // A string of fairy lights between two poles.
        val x1 = (w * 0.08f).toInt()
        val x2 = (w * 0.62f).toInt()
        val y1 = h - 36
        val y2 = h - 42
        line(x1, y1, x1, h, c)
        line(x2, y2, x2, h, c)
        for (lx in x1..x2) {
            val u = (lx - x1).toFloat() / (x2 - x1)
            val ly = (y1 + (y2 - y1) * u + 8 * sin(PI * u)).toInt()
            put(lx, ly, c)
            if ((lx - x1) % 5 == 2) {
                val bulb = BULBS[(lx - x1) / 5 % BULBS.size]
                halo(lx, ly + 1, 3, bulb, 0.5f)
                put(lx, ly + 1, bulb)
            }
        }
    }

    private fun window() {
        val t = 7
        val wood = mix(0x4A3640, near, 0.3f)
        val bevel = mix(wood, sky.glow, 0.28f)
        fun frame(x: Int, y: Int) = x < t || x >= w - t || y < t || y >= h - t * 2 ||
            abs(x - w / 2) < t / 2 + 1 || (portrait && abs(y - h * 9 / 20) < t / 2 + 1)
        for (y in 0 until h) for (x in 0 until w) {
            if (frame(x, y)) put(x, y, if (!frame(x, y - 1) || !frame(x - 1, y)) bevel else wood, FRONT)
        }
        // Potted plant on the sill.
        val sill = h - t * 2
        val px = w - t - 22
        rect(px, sill - 7, 9, 7, mix(0xC0705A, near, 0.3f), FRONT)
        rect(px - 1, sill - 8, 11, 2, mix(0xD88A6E, near, 0.3f), FRONT)
        val leaf = mix(0x6FA07A, near, 0.35f)
        val leafDark = mix(0x3F6E55, near, 0.35f)
        for (k in 0..6) {
            val ex = px + 4 + (k - 3) * 3
            val ey = sill - 14 - (3 - abs(k - 3)) * 2
            for (s in 0..6) put(px + 4 + (ex - px - 4) * s / 6, sill - 8 + (ey - sill + 8) * s / 6, if (k % 2 == 0) leaf else leafDark, FRONT)
        }
    }

    // endregion
    // region animation

    private class Flock(val n: Int, rnd: Random, w: Int, h: Int) {
        val x = FloatArray(n) { rnd.nextFloat() * w }
        val y = FloatArray(n) { rnd.nextFloat() * h }
        val v = FloatArray(n) { rnd.nextFloat() }
        val p = FloatArray(n) { rnd.nextFloat() * 6.2832f }
    }

    private fun flock(type: Particle, n: Int) = if (type in spec.particles) Flock(n, rnd, w, h) else null

    private fun plot(x: Int, y: Int, c: Int, a: Float, skyOnly: Boolean = false) {
        if (x !in 0 until w || y !in 0 until h) return
        val i = y * w + x
        val d = depth[i]
        if (d == FRONT || (skyOnly && d != SKY)) return
        pixels[i] = mix(pixels[i], c, a)
    }

    private fun shimmer(t: Double) {
        val top = horizon
        val glow = if (sky.body == Body.None) sky.bands[4] else sky.glow
        val cx = if (sky.body == Body.None) w / 2 else bodyCx
        for (y in top + 2 until h) {
            val half = 3 + (y - top) * 16 / (h - top)
            val phase = hash(y, 60, seed) * 6.28f
            val speed = 0.8f + hash(y, 61, seed) * 1.6f
            for (x in cx - half..cx + half) {
                if (x !in 0 until w || depth[y * w + x] != WATER) continue
                val edge = 1f - abs(x - cx).toFloat() / (half + 1)
                if (sin(x * 0.45 + phase + t * speed) > 0.6 - edge * 0.6) plot(x, y, glow, 0.15f + 0.5f * edge)
            }
        }
        for (k in 0 until w / 6) {
            val step = (t * 1.5 + hash(k, 62, seed) * 10).toInt()
            val x = (hash(k, step, seed + 63) * w).toInt()
            val y = top + 2 + (hash(k, step, seed + 64) * (h - top - 2)).toInt()
            if (y < h && depth[y * w + x] == WATER) {
                plot(x, y, sky.bands[5], 0.35f)
                plot(x + 1, y, sky.bands[5], 0.35f)
            }
        }
    }

    private fun drawStars(t: Double) {
        val c = mix(WHITE, sky.accent, 0.15f)
        for (k in 0 until starCount) {
            val i = stars[k]
            val tw = (0.5 + 0.5 * sin(t * (1.2 + k % 5 * 0.45) + hash(k, 50, seed) * 6.28)).toFloat()
            val a = starA[k]
            val sc = if (k % 6 == 0) 0xBFD4FF else if (k % 9 == 0) 0xFFE3B0 else c
            pixels[i] = mix(pixels[i], sc, a * (0.35f + 0.65f * tw))
            if (k % 7 == 0 && tw > 0.7f && a > 0.5f) {
                val x = i % w
                val y = i / w
                val g = (tw - 0.7f) * 1.8f * a
                plot(x - 1, y, sc, g, true)
                plot(x + 1, y, sc, g, true)
                plot(x, y - 1, sc, g, true)
                plot(x, y + 1, sc, g, true)
            }
        }
    }

    private fun blink(t: Double) {
        for (k in 0 until beaconCount) {
            val i = beacons[k]
            // Skip beacons that something nearer was painted over.
            if (base[i] != beaconDim || sin(t * 1.9 + k * 2.3) < 0.35) continue
            pixels[i] = BEACON or OPAQUE
            val x = i % w
            val y = i / w
            plot(x - 1, y, BEACON, 0.35f, true)
            plot(x + 1, y, BEACON, 0.35f, true)
            plot(x, y - 1, BEACON, 0.35f, true)
        }
    }

    private fun smoke(t: Double) {
        val c = mix(sky.bands[3], WHITE, 0.45f)
        for (k in 0 until chimneyCount) {
            val cx = chimneys[k] % w
            val cy = chimneys[k] / w
            for (j in 0 until 6) {
                val ph = (t * 0.16 + j / 6.0 + k * 0.37) % 1.0
                val x = (cx + ph * 8 * -lightDir + sin(ph * 7 + j + k) * 1.2).toInt()
                val y = (cy - ph * 18).toInt()
                val a = (0.5 * (1 - ph)).toFloat()
                plot(x, y, c, a)
                if (ph > 0.3) plot(x + 1, y, c, a * 0.7f)
            }
        }
    }

    private fun aimShootingStar() {
        val dir = if (rnd.nextBoolean()) 1f else -1f
        val angle = 0.3f + rnd.nextFloat() * 0.35f
        shootX = w * (0.2f + rnd.nextFloat() * 0.6f)
        shootY = h * 0.04f + rnd.nextFloat() * horizon * 0.3f
        shootDx = dir * cos(angle)
        shootDy = sin(angle)
    }

    private fun shootingStar(t: Double) {
        val e = t - shootAt
        if (e > 0.9) {
            shootAt = t + 8 + rnd.nextDouble() * 17
            aimShootingStar()
            return
        }
        if (e < 0) return
        val fade = sin(PI * e / 0.9).toFloat()
        val hx = shootX + shootDx * 140f * e.toFloat()
        val hy = shootY + shootDy * 140f * e.toFloat()
        for (k in 0 until 14) {
            plot((hx - shootDx * k).toInt(), (hy - shootDy * k).toInt(), WHITE, fade * (1f - k / 14f), true)
        }
    }

    private fun drawClouds(t: Double) {
        for (k in 0 until cloudN) {
            val cw = cloudW[k]
            val ox = wrap(cloudX[k] + cloudV[k] * t, w + cw) - cw
            val sprite = cloudPix[k]
            for (y in 0 until cloudH[k]) for (x in 0 until cw) {
                val tone = sprite[y * cw + x].toInt()
                val px = ox + x
                val py = cloudY[k] + y
                if (tone == 0 || px !in 0 until w || py !in 0 until h) continue
                val i = py * w + px
                if (depth[i] <= MOON) pixels[i] = cloudTones[tone]
            }
        }
    }

    /** 0 = empty, 1 = shade, 2 = body, 3 = lit top edge. */
    private fun cloudSprite(cw: Int, ch: Int): ByteArray {
        val puffs = 2 + cw / 9
        val rMax = (ch - 2) / 1.6f
        val pr = FloatArray(puffs) { rMax * (0.55f + 0.45f * sin(PI.toFloat() * (it + 0.5f) / puffs)) * (0.85f + rnd.nextFloat() * 0.15f) }
        val px = FloatArray(puffs) { pr[0] + (cw - pr[0] * 2) * it / (puffs - 1) }
        fun inside(x: Int, y: Int) = y in 0 until ch - 1 && (0 until puffs).any {
            val dx = x - px[it]
            val dy = y - (ch - 2 - pr[it] * 0.55f)
            dx * dx + dy * dy <= pr[it] * pr[it]
        }
        return ByteArray(cw * ch) { i ->
            val x = i % cw
            val y = i / cw
            when {
                !inside(x, y) -> 0
                !inside(x, y - 1) -> 3
                y >= ch - 2 || (y >= ch - 4 && bayer(x, y) < 0.5f) -> 1
                else -> 2
            }
        }
    }

    private fun lightning(t: Double) {
        val e = t - boltAt
        if (e > 0.6) {
            boltAt = t + 12 + rnd.nextDouble() * 20
            var x = (w * (0.15f + rnd.nextFloat() * 0.7f)).toInt()
            boltLen = (horizon * (0.5f + rnd.nextFloat() * 0.4f)).toInt()
            for (y in 0 until boltLen) {
                x += rnd.nextInt(-1, 2) + if (rnd.nextFloat() < 0.08f) rnd.nextInt(-3, 4) else 0
                bolt[y] = x
            }
            return
        }
        if (e < 0) return
        val k = when {
            e < 0.07 -> 1f
            e < 0.14 -> 0.25f
            e < 0.22 -> 0.8f
            else -> (0.8 * (1 - (e - 0.22) / 0.38)).toFloat()
        }
        val flash = mix(WHITE, sky.bands[4], 0.3f)
        for (i in pixels.indices) pixels[i] = mix(pixels[i], flash, if (depth[i] == SKY) k * 0.45f else k * 0.15f)
        if (k > 0.5f) for (y in 0 until boltLen) plot(bolt[y], y, WHITE, 1f, true)
    }

    /** Three depths of flakes: faint distant specks, single pixels and soft 2x2 flakes close by. */
    private fun snow(f: Flock, t: Double) {
        val soft = mix(WHITE, sky.bands[0], 0.1f)
        val pink = mix(sky.accent, WHITE, 0.25f)
        for (i in 0 until f.n) {
            val v = f.v[i]
            val layer = i % 3
            val y = wrap(f.y[i] + t * (5 + layer * 7 + v * 5), h + 4) - 2
            val sway = sin(t * (0.5 + v * 0.6) + f.p[i]) * (1.5 + layer * 1.5)
            val x = wrap(f.x[i] + t * (1.5 + layer) + sway, w)
            val c = if (i % 4 == 1) pink else soft
            when (layer) {
                0 -> plot(x, y, c, 0.4f)
                1 -> plot(x, y, c, 0.8f)
                else -> {
                    plot(x, y, c, 0.95f)
                    plot(x + 1, y, c, 0.55f)
                    plot(x, y + 1, c, 0.55f)
                    plot(x + 1, y + 1, c, 0.3f)
                }
            }
        }
    }

    /** Petals tumble through four orientations as they drift on the breeze. */
    private fun petals(f: Flock, t: Double) {
        for (i in 0 until f.n) {
            val v = f.v[i]
            val close = i % 3 == 0
            val y = wrap(f.y[i] + t * (8 + v * 10 + if (close) 6 else 0), h + 4) - 2
            val x = wrap(f.x[i] + t * (7 + v * 9) + sin(t * 1.3 + f.p[i]) * 5, w)
            val c = BLOSSOM[2 + i % 3]
            val d = BLOSSOM[1]
            when (((t * (2.5 + v * 3) + f.p[i]) % 4).toInt()) {
                0 -> {
                    plot(x, y, c, 0.95f)
                    plot(x + 1, y, d, 0.85f)
                    if (close) plot(x - 1, y, c, 0.6f)
                }
                1 -> {
                    plot(x, y, c, 0.95f)
                    plot(x + 1, y + 1, d, 0.8f)
                }
                2 -> plot(x, y, d, 0.9f)
                else -> {
                    plot(x, y, c, 0.95f)
                    plot(x, y + 1, d, 0.85f)
                    if (close) plot(x, y - 1, c, 0.6f)
                }
            }
        }
    }

    /** Faint short streaks far away, long bright ones up close, and splashes where drops land. */
    private fun rain(f: Flock, t: Double) {
        val farC = mix(sky.bands[3], WHITE, 0.2f)
        val nearC = mix(sky.bands[4], WHITE, 0.5f)
        for (i in 0 until f.n) {
            val v = f.v[i]
            val close = i % 3 == 0
            val speed = if (close) 230 + v * 60 else 120 + v * 50
            val len = if (close) 6 + (v * 3).toInt() else 2 + (v * 2).toInt()
            val c = if (close) nearC else farC
            val a = if (close) 0.6f else 0.3f
            val y = wrap(f.y[i] + t * speed, h + len)
            val x = wrap(f.x[i] - t * speed * 0.22, w)
            for (k in 0 until len) plot(x + k / 4, y - k, c, a * (1f - k * 0.6f / len))
        }
        val step = (t * 12).toInt()
        for (k in 0 until w / 8) {
            val sx = (hash(k, step, seed + 90) * w).toInt()
            val sy = horizon + (hash(k, step, seed + 91) * (h - horizon)).toInt()
            if (sy >= h || depth[sy * w + sx] <= MOON) continue
            plot(sx - 1, sy - 1, nearC, 0.45f)
            plot(sx + 1, sy - 1, nearC, 0.45f)
            plot(sx, sy, nearC, 0.3f)
        }
    }

    private fun fireflies(f: Flock, t: Double) {
        val c = sky.accent
        for (i in 0 until f.n) {
            val v = f.v[i]
            val p = f.p[i]
            val b = sin(t * (1.1 + v) + p * 3).toFloat()
            if (b <= 0f) continue
            val x = (f.x[i] + sin(t * 0.37 * (1 + v) + p) * 8).toInt()
            val y = (horizon - 24 + f.y[i] / h * (h - horizon + 16) + sin(t * 0.53 + p * 1.7) * 5).toInt()
            val a = b * b
            plot(x, y, mix(c, WHITE, 0.3f), a)
            plot(x - 1, y, c, a * 0.4f)
            plot(x + 1, y, c, a * 0.4f)
            plot(x, y - 1, c, a * 0.4f)
            plot(x, y + 1, c, a * 0.4f)
        }
    }

    // endregion
}
