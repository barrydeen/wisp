package com.wisp.app.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlin.random.Random

enum class ThemeEffect {
    NONE,
    CRT,
    GLITCH
}

@Composable
fun ThemeEffectOverlay(
    effect: ThemeEffect,
    modifier: Modifier = Modifier
) {
    when (effect) {
        ThemeEffect.CRT -> CrtEffect(modifier)
        ThemeEffect.GLITCH -> GlitchEffect(modifier)
        ThemeEffect.NONE -> {}
    }
}

@Composable
private fun CrtEffect(modifier: Modifier = Modifier) {
    var verticalShift by remember { mutableStateOf(0f) }
    var horizontalShift by remember { mutableStateOf(0f) }
    var brightness by remember { mutableStateOf(1f) }
    
    LaunchedEffect(Unit) {
        while (true) {
            verticalShift = (Random.nextFloat() - 0.5f) * 5f
            horizontalShift = (Random.nextFloat() - 0.5f) * 2f
            brightness = 0.96f + Random.nextFloat() * 0.04f
            delay(50 + (Random.nextFloat() * 80).toLong())
            verticalShift = (Random.nextFloat() - 0.5f) * 4f
            horizontalShift = (Random.nextFloat() - 0.5f) * 1.5f
            brightness = 0.97f + Random.nextFloat() * 0.03f
            delay(80 + (Random.nextFloat() * 120).toLong())
        }
    }
    
    Canvas(modifier = modifier.fillMaxSize()) {
        val lineHeight = 2f
        var y = 0f
        while (y < size.height) {
            drawRect(
                color = Color.Black.copy(alpha = 0.25f * brightness),
                topLeft = Offset(horizontalShift, y + verticalShift),
                size = androidx.compose.ui.geometry.Size(size.width, lineHeight)
            )
            y += lineHeight * 2
        }
        
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf<Color>(
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.06f * brightness),
                    Color.Black.copy(alpha = 0.15f * brightness)
                ),
                center = Offset(size.width / 2, size.height / 2),
                radius = size.width * 1.3f
            )
        )
    }
}

@Composable
private fun GlitchEffect(modifier: Modifier = Modifier) {
    var offset by remember { mutableStateOf(0f) }
    var showGlitch by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay((2000..5000).random().toLong())
            offset = (Random.nextFloat() - 0.5f) * 10f
            showGlitch = true
            kotlinx.coroutines.delay((100..250).random().toLong())
            offset = 0f
            showGlitch = false
        }
    }
    
    if (showGlitch) {
        val primaryColor = MaterialTheme.colorScheme.primary
        val secondaryColor = MaterialTheme.colorScheme.secondary
        
        Canvas(modifier = modifier.fillMaxSize()) {
            drawRect(
                color = primaryColor.copy(alpha = 0.12f),
                topLeft = Offset(offset * 1.5f, 0f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.3f, size.height)
            )
            drawRect(
                color = secondaryColor.copy(alpha = 0.12f),
                topLeft = Offset(-offset, 0f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.25f, size.height)
            )
            
            if (Random.nextFloat() > 0.5f) {
                val bandY = Random.nextFloat() * size.height
                val bandHeight = Random.nextFloat() * 30f + 10f
                drawRect(
                    color = primaryColor.copy(alpha = 0.2f),
                    topLeft = Offset(0f, bandY),
                    size = androidx.compose.ui.geometry.Size(size.width, bandHeight)
                )
            }
        }
    }
}
