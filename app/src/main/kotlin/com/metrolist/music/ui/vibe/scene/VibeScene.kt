package com.metrolist.music.ui.vibe.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

// Placeholder contract for Vibe mode; the scene engine replaces the body but keeps these names.
@Serializable
data class SceneSpec(
    val sky: Long = 0xFF8E8AC8,
    val horizon: Long = 0xFFF2B8C6,
)

object VibeScenes {
    val winterDusk = SceneSpec()
    val rainyCity = SceneSpec(0xFF1B1D3A, 0xFF5B3C88)
    val starryLake = SceneSpec(0xFF0B1030, 0xFF2C3A70)
    val springBlossoms = SceneSpec(0xFFA9C8F0, 0xFFF7D6E0)
}

@Composable
fun VibeScene(
    spec: SceneSpec,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    Box(modifier.background(Brush.verticalGradient(listOf(Color(spec.sky), Color(spec.horizon)))))
}
