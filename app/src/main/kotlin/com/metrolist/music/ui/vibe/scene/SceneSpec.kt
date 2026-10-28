package com.metrolist.music.ui.vibe.scene

import kotlinx.serialization.Serializable

/** Everything needed to draw a vibe scene. Stored as JSON for custom vibes, so every field keeps a default. */
@Serializable
data class SceneSpec(
    val sky: Sky = Sky.Dusk,
    val layout: Layout = Layout.Forest,
    val foreground: Foreground = Foreground.Rails,
    val particles: Set<Particle> = setOf(Particle.Snow),
    val snowy: Boolean = true,
    val seed: Int = 1,
)

/** Time of day + palette. Colours are 0xRRGGBB; [bands] run from the top of the sky down to the horizon. */
@Serializable
enum class Sky(
    internal val bands: IntArray,
    internal val land: Int,
    internal val ground: Int,
    internal val accent: Int,
    internal val glow: Int,
    internal val body: Body,
    internal val bodyX: Float,
    internal val bodyY: Float,
    internal val night: Boolean,
) {
    Dusk(intArrayOf(0x8C8ED6, 0x9C97DA, 0xB09FD9, 0xC9A7D6, 0xE0B0CF, 0xF3BFC6), 0x5D5A90, 0x8E86B4, 0xF7B3C7, 0xF8DDE8, Body.Moon, 0.76f, 0.15f, false),
    Sunset(intArrayOf(0x3E3A78, 0x6A4790, 0xA0559C, 0xD8699A, 0xF39089, 0xFBC08F), 0x3B2D5E, 0x5C4A6E, 0xFFB38A, 0xFFE2A8, Body.Sun, 0.62f, 0.5f, false),
    Day(intArrayOf(0x8EC2EE, 0xA3CDF2, 0xBAD8F4, 0xD2E2F4, 0xEAE6F2, 0xF8E3EC), 0x5E9C7E, 0x9CCB8E, 0xF7B6CF, 0xFFF8DC, Body.Sun, 0.22f, 0.13f, false),
    Night(intArrayOf(0x0A0E2C, 0x10163A, 0x171F4A, 0x212A5C, 0x2F376C, 0x45467E), 0x0E1230, 0x1C2244, 0xFFE9A8, 0xF4F0D8, Body.Moon, 0.7f, 0.14f, true),
    NeonNight(intArrayOf(0x110D26, 0x1A1538, 0x271C4C, 0x3D2462, 0x5E2D74, 0x8C3C82), 0x140F2A, 0x1E1838, 0xFF6FB5, 0xF2E6F0, Body.None, 0.5f, 0.2f, true),
}

internal enum class Body { None, Moon, Sun }

@Serializable
enum class Layout { Forest, Lake, City, Blossoms, Hills }

@Serializable
enum class Foreground { None, Rails, Shore, Rooftops, Window }

@Serializable
enum class Particle { Snow, Petals, Rain, Fireflies, Stars, ShootingStars, Clouds, Lightning }

object VibeScenes {
    val winterDusk = SceneSpec()
    val rainyCity = SceneSpec(
        sky = Sky.NeonNight,
        layout = Layout.City,
        foreground = Foreground.Rooftops,
        particles = setOf(Particle.Rain, Particle.Lightning),
        snowy = false,
        seed = 3,
    )
    val starryLake = SceneSpec(
        sky = Sky.Night,
        layout = Layout.Lake,
        foreground = Foreground.Shore,
        particles = setOf(Particle.Stars, Particle.ShootingStars, Particle.Fireflies),
        snowy = false,
        seed = 5,
    )
    val springBlossoms = SceneSpec(
        sky = Sky.Day,
        layout = Layout.Blossoms,
        foreground = Foreground.None,
        particles = setOf(Particle.Petals, Particle.Clouds),
        snowy = false,
        seed = 2,
    )
}
