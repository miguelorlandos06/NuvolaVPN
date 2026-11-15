package com.nuvola.vpn.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NuvolaBlue  = Color(0xFF1E6FD9)
private val NuvolaBlueD = Color(0xFF8AB4F8)
private val NuvolaTeal  = Color(0xFF2E7D32)
private val NuvolaError = Color(0xFFB3261E)

private val Light = lightColorScheme(
    primary = NuvolaBlue,
    secondary = NuvolaTeal,
    error = NuvolaError,
)

private val Dark = darkColorScheme(
    primary = NuvolaBlueD,
    secondary = NuvolaTeal,
    error = NuvolaError,
)

@Composable
fun NuvolaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        content = content,
    )
}
