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
private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
private val BLOSSOM = intArrayOf(0xD47BA6, 0xEE98BC, 0xF8BCD3, 0xFFE2EC)
private val NEON = intArrayOf(0xFF6FB5, 0x6FE8FF, 0xB98CFF)
private val BULBS = intArrayOf(0xFFD27A, 0xFF8FB8, 0x8FE8FF, 0xFFB36B)
private const val WINDOW_LIGHT = 0xFFD27A

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
    private val haze = sky.bands[3]
    private val far = mix(sky.land, haze, 0.6f)
    private val mid = mix(sky.land, haze, 0.3f)
    private val near = sky.land
    private val snow = mix(WHITE, sky.bands[1], if (sky.night) 0.55f else 0.16f)
    private val groundTop = if (spec.snowy) snow else sky.ground
    private val lightDir = if (sky.bodyX >= 0.5f) 1 else -1
    private val litP = if (sky.night) 0.42f else if (sky == Sky.Day) 0.04f else 0.28f
    private val bodyCx = (w * sky.bodyX).toInt()
    private val bodyCy = (h * sky.bodyY).toInt()
    private val bodyR = if (sky.body == Body.Sun) (if (sky.bodyY > 0.4f) 14 else 9) else 7

    private val snowFlock = flock(Particle.Snow, w * h / 350)
    private val petalFlock = flock(Particle.Petals, w * h / 900)
    private val rainFlock = flock(Particle.Rain, w * h / 420)
    private val fireflyFlock = flock(Particle.Fireflies, 14 + w * h / 6000)
    private val stars = IntArray(if (Particle.Stars in spec.particles) w * horizon / 220 else 0)
    private var starCount = 0

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
            val i = rnd.nextInt((horizon * 0.85f).toInt().coerceAtLeast(1)) * w + rnd.nextInt(w)
            if (depth[i] == SKY) stars[starCount++] = i
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
        if (x in 0 until w && y in 0 until h) base[y * w + x] = mix(base[y * w + x], c, t)
    }

    private fun rect(x: Int, y: Int, rw: Int, rh: Int, c: Int, d: Byte = LAND) {
        for (yy in y until y + rh) for (xx in x until x + rw) put(xx, yy, c, d)
    }

    private fun line(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        val n = max(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(1)
        for (k in 0..n) put(x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n, c)
    }

    private fun paintSky() {
        val b = sky.bands
        val n = b.size - 1
        for (y in 0 until h) {
            val t = (y.toFloat() / horizon * n).coerceIn(0f, n.toFloat())
            val i = min(t.toInt(), n - 1)
            // Flat bands that dither into the next one over their lower half.
            val f = ((t - i - 0.5f) * 2f).coerceIn(0f, 1f)
            for (x in 0 until w) put(x, y, if (bayer(x, y) < f) b[i + 1] else b[i], SKY)
        }
        if (sky.body == Body.None) return
        val r = bodyR
        val halo = r + 8
        val lowSun = sky.body == Body.Sun && sky.bodyY > 0.4f
        for (y in bodyCy - halo..bodyCy + halo) for (x in bodyCx - halo..bodyCx + halo) {
            val dx = x - bodyCx
            val dy = y - bodyCy
            val d = sqrt((dx * dx + dy * dy).toFloat())
            when {
                d <= r && !(lowSun && dy > 1 && dy % 4 == 0) -> put(x, y, bodyColor(x, y, dx, dy, r), MOON)
                d <= halo && bayer(x, y) < (halo - d) / (halo - r) * 0.85f -> tint(x, y, sky.glow, 0.28f)
            }
        }
    }

    private fun bodyColor(x: Int, y: Int, dx: Int, dy: Int, r: Int): Int {
        val g = sky.glow
        if (sky.body == Body.Sun) {
            val t = (dy + r) / (2f * r)
            return if (bayer(x, y) < t) mix(g, sky.accent, 0.45f) else g
        }
        val ox = dx - r * 0.35f
        val oy = dy + r * 0.3f
        val c = if (ox * ox + oy * oy <= r * r * 1.1f) g else mix(g, sky.bands[2], 0.35f)
        val crater = (dx == -2 && dy in 0..1) || (dx in 2..3 && dy == -2) || (dx == 1 && dy == 3)
        return if (crater) mix(c, sky.bands[2], 0.22f) else c
    }

    private fun ground(top: Int) {
        val shade = mix(groundTop, near, 0.14f)
        val bright = mix(groundTop, WHITE, 0.3f)
        for (y in top until h) for (x in 0 until w) {
            val n = noise2(x * 0.045f, y * 0.11f, seed + 9) + (bayer(x, y) - 0.5f) * 0.2f
            put(x, y, when {
                n > 0.66f -> shade
                spec.snowy && n < 0.22f -> bright
                !spec.snowy && hash(x, y, seed) > 0.96f -> mix(groundTop, near, 0.35f)
                else -> groundTop
            })
        }
    }

    private fun pine(cx: Int, baseY: Int, height: Int, body: Int) {
        val lit = mix(body, sky.glow, if (sky.night) 0.18f else 0.3f)
        val cap = if (spec.snowy) mix(snow, body, 0.2f) else lit
        val shadow = mix(body, 0, 0.12f)
        val trunk = max(1, height / 7)
        val crown = height - trunk
        val tier = max(4, crown / 4)
        val top = baseY - height
        fun half(r: Int) = if (r < 0) -1 else (r * 0.2f + (r % tier) * 0.48f + if (r > 2) hash(cx, r, seed + 7) * 1.4f else 0f).toInt()
        for (r in 0 until crown) {
            val hw = half(r)
            val above = half(r - 1)
            val y = top + r
            for (dx in -hw..hw) {
                val side = dx * lightDir
                put(cx + dx, y, when {
                    // Upward-facing ledges catch snow (or rim light on the lit side).
                    abs(dx) > above - 1 && (spec.snowy || side > 0) -> cap
                    spec.snowy && side >= 0 && hash(cx + dx, y, seed + 5) < 0.05f -> cap
                    side > hw / 3 && hash(cx + dx, y, seed) < 0.85f -> lit
                    side < -hw / 2 -> shadow
                    else -> body
                })
            }
        }
        val tc = mix(body, 0, 0.35f)
        for (y in baseY - trunk until baseY) {
            put(cx, y, tc)
            if (height > 50) put(cx + 1, y, tc)
        }
    }

    private fun ridge(x: Int, baseY: Int, amp: Float, freq: Float, s: Int) = baseY - (fbm(x * freq, seed + s) * amp).toInt()

    private fun hill(baseY: Int, amp: Float, freq: Float, c: Int, s: Int) {
        val rim = if (spec.snowy) mix(snow, c, 0.15f) else mix(c, sky.glow, 0.22f)
        for (x in 0 until w) {
            val top = ridge(x, baseY, amp, freq, s)
            for (y in top until h) put(x, y, c)
            put(x, top, rim)
        }
    }

    private fun forest() {
        ground(horizon)
        var x = -3
        while (x < w + 3) {
            pine(x, horizon + 1, 11 + (hash(x, 1, seed) * 13).toInt(), far)
            x += 3 + (hash(x, 2, seed) * 4).toInt()
        }
        x = -5
        while (x < w + 5) {
            val ht = 20 + (fbm(x * 0.03f, seed) * 30).toInt() + (hash(x, 3, seed) * 8).toInt()
            pine(x, horizon + 4 + (hash(x, 4, seed) * 4).toInt(), ht, mid)
            x += 8 + (hash(x, 5, seed) * 9).toInt()
        }
        pine((w * 0.05f).toInt(), h - 12, 125, near)
        pine((w * 0.16f).toInt(), h - 22, 88, near)
        if (!portrait) pine((w * 0.95f).toInt(), h - 16, 100, near)
    }

    private fun lake() {
        val top = horizon
        fun big(x: Int) = 1f - abs(noise2(x * 0.018f, 0f, seed + 4) * 2f - 1f)
        for (x in 0 until w) {
            val ry = top - 6 - (big(x) * 34 + fbm(x * 0.05f, seed + 5) * 12).toInt()
            val faceLit = (big(x + 1) - big(x)) * lightDir < 0
            for (y in ry..top) {
                val c = when {
                    y < ry + 3 && spec.snowy -> mix(snow, far, 0.2f)
                    y == ry -> mix(far, sky.glow, 0.3f)
                    faceLit && y < ry + 14 -> mix(far, sky.glow, 0.12f)
                    else -> far
                }
                put(x, y, c)
            }
        }
        var x = -2
        while (x < w + 2) {
            pine(x, top + 1, 6 + (hash(x, 6, seed) * 11).toInt(), mid)
            x += 3 + (hash(x, 7, seed) * 4).toInt()
        }
        val tint = mix(sky.bands[1], near, 0.35f)
        for (y in top + 1 until h) {
            val k = (y - top).toFloat() / (h - top)
            val wob = ((noise2(y * 0.9f, 0f, seed + 6) - 0.5f) * 3f).toInt()
            val sy = (2 * top - y + 1).coerceAtLeast(0)
            for (xx in 0 until w) {
                var c = mix(base[sy * w + (xx + wob).coerceIn(0, w - 1)], tint, 0.3f + k * 0.35f)
                if (hash(xx / 5, y, seed) > 0.94f) c = mix(c, sky.bands[4], 0.25f)
                put(xx, y, c, WATER)
            }
        }
    }

    private fun city() {
        val street = h - 26
        var x = -2
        while (x < w) {
            val bw = 7 + (hash(x, 7, seed) * 12).toInt()
            val top = street - 36 - (fbm(x * 0.02f, seed + 7) * 50 + hash(x, 8, seed) * 18).toInt()
            rect(x, top, bw, street - top, far)
            if (hash(x, 9, seed) > 0.8f) line(x + bw / 2, top - 8, x + bw / 2, top, far)
            for (wy in top + 3 until street step 4) for (wx in x + 2 until x + bw - 1 step 3) {
                if (hash(wx, wy, seed + 1) < litP * 0.6f) put(wx, wy, mix(WINDOW_LIGHT, far, 0.5f))
            }
            x += bw
        }
        x = -4
        while (x < w) {
            val bw = 12 + (hash(x, 10, seed) * 16).toInt()
            val top = street - 20 - (hash(x, 11, seed) * h * 0.3f).toInt()
            rect(x, top, bw, street - top, near)
            val edge = if (lightDir > 0) x + bw - 1 else x
            for (y in top until street) put(edge, y, mix(near, sky.accent, 0.22f))
            for (wy in top + 3 until street - 1 step 4) for (wx in x + 2 until x + bw - 2 step 3) {
                val r = hash(wx, wy, seed)
                val c = when {
                    r < litP * 0.12f -> mix(NEON[(r * 1000).toInt() % NEON.size], near, 0.2f)
                    r < litP -> WINDOW_LIGHT
                    else -> mix(near, sky.bands[2], 0.25f)
                }
                put(wx, wy, c)
                put(wx, wy + 1, c)
            }
            if (hash(x, 12, seed) < 0.4f) neonSign(x + bw - 4, top + 5, 8 + (hash(x, 13, seed) * 8).toInt(), NEON[(hash(x, 14, seed) * NEON.size).toInt()])
            if (hash(x, 15, seed) < 0.3f) {
                line(x + 3, top - 9, x + 3, top - 1, near)
                put(x + 3, top - 10, 0xFF4D6D)
            }
            x += bw + (hash(x, 16, seed) * 6).toInt()
        }
        // Wet street mirrors what stands on it.
        val road = mix(near, 0, 0.25f)
        for (y in street until h) for (xx in 0 until w) {
            val src = base[(street - 1 - (y - street) * 2).coerceAtLeast(0) * w + xx]
            put(xx, y, if (bayer(xx, y) < 0.55f) mix(road, src, 0.4f) else road)
        }
    }

    private fun neonSign(x: Int, y: Int, len: Int, c: Int) {
        for (yy in y - 2..y + len + 1) for (xx in x - 2..x + 3) tint(xx, yy, c, 0.18f)
        for (yy in y until y + len) for (xx in x..x + 1) put(xx, yy, if ((yy - y) % 3 == 2) mix(c, near, 0.6f) else c)
    }

    private fun blossomTree(cx: Int, baseY: Int, r: Int, hazed: Float) {
        val trunk = mix(mix(0x6B4B5E, near, 0.3f), haze, hazed)
        val crownY = baseY - r - r / 2
        val tw = max(1, r / 7)
        for (y in crownY until baseY) for (dx in 0 until tw + (y - crownY) * 2 / (baseY - crownY)) put(cx + dx, y, trunk)
        line(cx, crownY + r / 2, cx - r / 2, crownY - r / 6, trunk)
        line(cx + tw, crownY + r / 3, cx + r / 2, crownY - r / 3, trunk)
        for (k in 0 until 6) {
            val px = cx + ((hash(cx, k, seed) - 0.5f) * r * 1.5f).toInt()
            val py = crownY - (hash(cx, k + 10, seed) * r * 0.7f).toInt()
            puff(px, py, max(3, (r * (0.5f + hash(cx, k + 20, seed) * 0.3f)).toInt()), hazed)
        }
    }

    private fun puff(cx: Int, cy: Int, r: Int, hazed: Float) {
        val night = if (sky.night) 0.45f else 0f
        for (dy in -r..r) for (dx in -r..r) {
            val x = cx + dx
            val y = cy + dy
            if (dx * dx + dy * dy > r * r * (0.75f + 0.4f * hash(x, y, seed + 3))) continue
            val tone = 0.55f - dy * 0.4f / r + dx * lightDir * 0.2f / r + (hash(x, y, seed + 4) - 0.5f) * 0.35f
            val c = BLOSSOM[if (tone > 0.85f) 3 else if (tone > 0.55f) 2 else if (tone > 0.3f) 1 else 0]
            put(x, y, mix(mix(c, near, night), haze, hazed))
        }
    }

    private fun blossoms() {
        val g = horizon + 2
        hill(g - 6, 24f, 0.012f, mix(near, haze, 0.68f), 11)
        hill(g, 12f, 0.02f, mix(near, haze, 0.45f), 12)
        ground(g + 6)
        var x = (hash(0, 17, seed) * 20).toInt()
        while (x < w) {
            blossomTree(x, g + 8 + (hash(x, 18, seed) * 6).toInt(), 7 + (hash(x, 19, seed) * 5).toInt(), 0.3f)
            x += 26 + (hash(x, 20, seed) * 22).toInt()
        }
        x = (hash(1, 17, seed) * 30).toInt() + 30
        while (x < w - 20) {
            blossomTree(x, g + (h - g) / 2 + (hash(x, 25, seed) * 8).toInt(), 10 + (hash(x, 26, seed) * 5).toInt(), 0.1f)
            x += 50 + (hash(x, 27, seed) * 40).toInt()
        }
        blossomTree((w * 0.1f).toInt(), h - 8, 26, 0f)
        if (!portrait) blossomTree((w * 0.9f).toInt(), h - 12, 20, 0f)
    }

    private fun hills() {
        val step = (h - horizon) / 4 + 6
        for (k in 0..3) {
            val c = mix(near, haze, 0.7f - k * 0.22f)
            val baseY = horizon - 14 + k * step
            val freq = 0.01f + k * 0.004f
            hill(baseY, 18f, freq, c, 20 + k)
            var x = (hash(k, 21, seed) * 9).toInt()
            while (k in 1..2 && x < w) {
                if (hash(x, 22 + k, seed) < 0.3f) pine(x, ridge(x, baseY, 18f, freq, 20 + k) + 3, 6 + (hash(x, 24, seed) * 5).toInt(), mix(c, 0, 0.15f))
                x += 3
            }
            if (k == 3) for (hx in intArrayOf((w * 0.22f).toInt(), (w * 0.64f).toInt())) {
                house(hx, ridge(hx + 5, baseY, 18f, freq, 23) + 4, mix(c, sky.glow, 0.15f))
            }
        }
    }

    private fun house(x: Int, gy: Int, wall: Int) {
        val roof = if (spec.snowy) snow else mix(wall, 0, 0.35f)
        rect(x, gy - 6, 10, 7, wall)
        for (r in 0..5) for (dx in 4 - r..5 + r) put(x + dx, gy - 12 + r, roof)
        rect(x + 7, gy - 13, 1, 3, mix(wall, 0, 0.3f))
        val lit = sky.night || sky == Sky.Dusk || sky == Sky.Sunset
        for (yy in gy - 6..gy - 1) for (xx in x..x + 5) if (lit) tint(xx, yy, WINDOW_LIGHT, 0.12f)
        rect(x + 2, gy - 4, 2, 2, if (lit) WINDOW_LIGHT else mix(wall, sky.bands[2], 0.4f))
        rect(x + 6, gy - 4, 2, 5, mix(wall, 0, 0.4f))
    }

    // endregion
    // region foregrounds

    private fun rails() {
        val top = horizon + 3
        val farX = w * 0.68f
        val nearX = w * if (portrait) 0.4f else 0.34f
        val gauge = min(w, h) * 0.3f
        val rail = mix(near, 0, 0.3f)
        val tie = mix(groundTop, near, 0.3f)
        var pl = -1
        var pr = -1
        for (y in top until h) {
            val t = (y - top + 1f) / (h - top)
            // sqrt bends the far end sideways, so the track curves into the trees.
            val cx = farX + (nearX - farX) * sqrt(t)
            val half = 1f + gauge * t / 2
            val l = (cx - half).roundToInt()
            val r = (cx + half).roundToInt()
            if (t > 0.12f && (1.6f / (t + 0.06f)) % 1f < 0.3f) {
                for (x in (l - half * 0.2f).toInt()..(r + half * 0.2f).toInt()) if (bayer(x, y) < 0.7f) put(x, y, tie)
            }
            val thick = 1 + (t * 2.2f).toInt()
            for (x in min(if (pl < 0) l else pl, l)..max(if (pl < 0) l else pl, l) + thick - 1) put(x, y, rail)
            for (x in min(if (pr < 0) r else pr, r)..max(if (pr < 0) r else pr, r) + thick - 1) put(x, y, rail)
            pl = l
            pr = r
        }
    }

    private fun shore() {
        val c = mix(near, 0, 0.25f)
        val rim = mix(c, sky.glow, 0.18f)
        val cattail = mix(0x5A3A30, near, 0.5f)
        for (x in 0 until w) {
            val ry = h - 20 + (fbm(x * 0.04f, seed + 30) * 12).toInt()
            for (y in ry until h) put(x, y, c)
            put(x, ry, rim)
            if (hash(x, 31, seed) < 0.22f) {
                val rh = 3 + (hash(x, 32, seed) * 10).toInt()
                for (y in ry - rh until ry) put(x, y, c)
                if (hash(x, 33, seed) < 0.35f) rect(x, ry - rh - 1, 1, 3, cattail)
            }
        }
    }

    private fun rooftops() {
        val c = mix(near, 0, 0.35f)
        val ledge = mix(c, sky.accent, 0.3f)
        var x = 0
        while (x < w) {
            val rw = 22 + (hash(x, 40, seed) * 30).toInt()
            val top = h - 12 - (hash(x, 41, seed) * 16).toInt()
            rect(x, top, rw, h - top, c)
            for (dx in 0 until rw) put(x + dx, top, ledge)
            if (hash(x, 42, seed) < 0.5f) rect(x + 4, top - 5, 7, 5, c)
            if (hash(x, 43, seed) < 0.35f) {
                val ax = x + rw - 5
                line(ax, top - 14, ax, top, c)
                line(ax - 2, top - 11, ax + 2, top - 11, c)
                put(ax, top - 15, 0xFF4D6D)
            }
            x += rw
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
                for (dy in 0..3) for (dx in -2..2) tint(lx + dx, ly + dy, bulb, 0.15f)
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
            pixels[i] = mix(pixels[i], c, 0.3f + 0.65f * tw)
            if (k % 7 == 0 && tw > 0.7f) {
                val x = i % w
                val y = i / w
                val a = (tw - 0.7f) * 1.8f
                plot(x - 1, y, c, a, true)
                plot(x + 1, y, c, a, true)
                plot(x, y - 1, c, a, true)
                plot(x, y + 1, c, a, true)
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
                y >= ch - 3 -> 1
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

    private fun snow(f: Flock, t: Double) {
        val soft = mix(WHITE, sky.bands[0], 0.12f)
        for (i in 0 until f.n) {
            val v = f.v[i]
            val y = wrap(f.y[i] + t * (6 + v * 16), h + 4) - 2
            val x = wrap(f.x[i] + t * (2 + v * 3) + sin(t * (0.6 + v) + f.p[i]) * 3, w)
            val c = if (i % 3 == 0) sky.accent else soft
            val a = 0.55f + v * 0.4f
            plot(x, y, c, a)
            if (v > 0.85f) {
                plot(x + 1, y, c, a)
                plot(x, y + 1, c, a)
                plot(x + 1, y + 1, c, a)
            }
        }
    }

    private fun petals(f: Flock, t: Double) {
        for (i in 0 until f.n) {
            val v = f.v[i]
            val y = wrap(f.y[i] + t * (7 + v * 9), h + 4) - 2
            val x = wrap(f.x[i] + t * (6 + v * 8) + sin(t * 1.3 + f.p[i]) * 4, w)
            val c = BLOSSOM[1 + i % 3]
            plot(x, y, c, 0.9f)
            if (sin(t * (2 + v * 2) + f.p[i]) > 0) plot(x + 1, y, c, 0.9f) else plot(x, y + 1, BLOSSOM[0], 0.7f)
        }
    }

    private fun rain(f: Flock, t: Double) {
        val c = mix(sky.bands[4], WHITE, 0.35f)
        for (i in 0 until f.n) {
            val v = f.v[i]
            val speed = 140 + v * 100
            val len = 3 + (v * 4).toInt()
            val y = wrap(f.y[i] + t * speed, h + len)
            val x = wrap(f.x[i] - t * speed * 0.25, w)
            for (k in 0 until len) plot(x + k / 4, y - k, c, 0.55f - k * 0.06f)
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
            plot(x, y, c, a)
            plot(x - 1, y, c, a * 0.35f)
            plot(x + 1, y, c, a * 0.35f)
            plot(x, y - 1, c, a * 0.35f)
            plot(x, y + 1, c, a * 0.35f)
        }
    }

    // endregion
}
