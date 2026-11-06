package com.perkus.imagoedit.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80, background = ScreenBg, surface = BarBg)

val SmallShape = RoundedCornerShape(8.dp) // кнопки, поля, плашки
val CardShape = RoundedCornerShape(12.dp) // карточки диалогов

@Composable
fun ImagoEditTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = DarkColorScheme, content = content)
