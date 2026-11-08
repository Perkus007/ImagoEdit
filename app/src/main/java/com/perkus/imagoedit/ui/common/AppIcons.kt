package com.perkus.imagoedit.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private fun svg(d: String, viewBox: Float = 24f, strokeWidth: Float = 0f): ImageVector = ImageVector.Builder("icon", 24.dp, 24.dp, viewBox, viewBox)
    .addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = if (strokeWidth == 0f) SolidColor(Color.Black) else null, stroke = if (strokeWidth > 0f) SolidColor(Color.Black) else null, strokeLineWidth = strokeWidth, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    .build()

object AppIcons {
    /** Кнопки интерфейса */
    val Menu = svg("M4 5h16M4 12h16M4 19h16", strokeWidth = 2f)
    val Cancel = svg("M18 6 6 18M6 6l12 12", strokeWidth = 2f)
    val Check = svg("M20 6 9 17l-5-5", strokeWidth = 2f)
    val ChevronUp = svg("m18 15-6-6-6 6", strokeWidth = 2f)
    val Back = svg("M19 12H5m7 7-7-7 7-7", strokeWidth = 2f)
    val Add = svg("M5 12h14M12 5v14", strokeWidth = 2f)
    val Delete = svg("M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2M10 11v6m4-6v6", strokeWidth = 2f)
    val FileUp = svg("M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2zM14 2v5a1 1 0 0 0 1 1h5M12 12v6M15 15l-3-3-3 3", strokeWidth = 2f)
    val Layers = svg("M12 2L2 7l10 5l10-5zM2 12l10 5l10-5M2 17l10 5l10-5", strokeWidth = 2f)
    val AddImage = svg(d = "M16 5h6m-3-3v6M21 11.5V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7.5M21 15l-3.086-3.086a2 2 0 0 0-2.828 0L6 21M9 7a2 2 0 1 0 0 4 2 2 0 1 0 0-4", strokeWidth = 2f)
    val Backspace = svg("m9 12 6-6v12")
    val Info = svg("M12 16v-4m0-4h.01M12 2a10 10 0 1 0 0 20a10 10 0 0 0 0-20", strokeWidth = 2f)
    val StepBack = svg("M14 4.3A2 2 0 0 1 17 6v12a2 2 0 0 1-3 1.7L4 13.7a2 2 0 0 1 0-3.4zM21 20V4", strokeWidth = 2f)
    val StepForward = svg("M10 4.3A2 2 0 0 0 7 6v12a2 2 0 0 0 3 1.7L20 13.7a2 2 0 0 0 0-3.4zM3 4v16", strokeWidth = 2f)
    val FlatteningLayers = svg("M12 7v15m-3-3 3 3 3-3M2 12a1 1 0 00.6.9l5.1 2.3M22 12a1 1 0 01-.6.9l-5.1 2.3M8 10.4 2.6 7.9a1 1 0 010-1.8l8.6-3.9a2 2 0 011.6 0l8.6 3.9a1 1 0 010 1.8l-5.4 2.5", strokeWidth = 2f)
    val Settings = svg("M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915M12 9a3 3 0 1 0 0 6 3 3 0 1 0 0-6", strokeWidth = 2f)
    val CutSquare = svg(d = "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM10 8a2 2 0 1 1-4 0 2 2 0 0 1 4 0zM9.4 9.4 12 12M17 7 9.4 14.6M10 16a2 2 0 1 1-4 0 2 2 0 0 1 4 0zM13.8 13.8 17 17", strokeWidth = 2f)
    val Scissors = svg(d = "M9 6a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM8.12 8.12 12 12M20 4 8.12 15.88M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM14.8 14.8 20 20", strokeWidth = 2f)
    val Copy = svg(d = "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2zM4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2", strokeWidth = 2f)
    val CopyFromAllLayers = svg("M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2zM7 19c-1.1 0-2-.9-2-2V7c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2", strokeWidth = 2f)
    val Insert = svg(d = "M21 9V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-4M21 12H8M12 8l-4 4 4 4", strokeWidth = 2f)
    val Extract = svg(d = "M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM8 8v8h8V8H8z M2 2h2v2H2zM6 2h2v2H6zM10 2h2v2h-2zM14 2h2v2h-2zM18 2h2v2h-2z M4 4h2v2H4zM8 4h2v2H8zM12 4h2v2h-2zM16 4h2v2h-2zM20 4h2v2h-2z M2 6h2v2H2zM18 6h2v2h-2zM4 8h2v2H4zM20 8h2v2h-2z M2 10h2v2H2zM18 10h2v2h-2zM4 12h2v2H4zM20 12h2v2h-2z M2 14h2v2H2zM18 14h2v2h-2zM4 16h2v2H4zM20 16h2v2h-2z M2 18h2v2H2zM6 18h2v2H6zM10 18h2v2h-2zM14 18h2v2h-2zM18 18h2v2h-2z M4 20h2v2H4zM8 20h2v2H8zM12 20h2v2h-2zM16 20h2v2h-2zM20 20h2v2h-2z", strokeWidth = 0f)
    val Clear = svg(d = "M5 2h14a3 3 0 0 1 3 3v14a3 3 0 0 1-3 3H5a3 3 0 0 1-3-3V5a3 3 0 0 1 3-3zM4 4v16h16V4H4z M4 4h2v2H4zM8 4h2v2H8zM12 4h2v2h-2zM16 4h2v2h-2z M6 6h2v2H6zM10 6h2v2h-2zM14 6h2v2h-2zM18 6h2v2h-2z M4 8h2v2H4zM8 8h2v2H8zM12 8h2v2h-2zM16 8h2v2h-2z M6 10h2v2H6zM10 10h2v2h-2zM14 10h2v2h-2zM18 10h2v2h-2z M4 12h2v2H4zM8 12h2v2H8zM12 12h2v2h-2zM16 12h2v2h-2z M6 14h2v2H6zM10 14h2v2h-2zM14 14h2v2h-2zM18 14h2v2h-2z M4 16h2v2H4zM8 16h2v2H8zM12 16h2v2h-2zM16 16h2v2h-2z M6 18h2v2H6zM10 18h2v2h-2zM14 18h2v2h-2zM18 18h2v2h-2z", strokeWidth = 0f)
    val SquareDashedXCorner = svg(d = "M14 3h1M16 16l5 5M19 3a2 2 0 0 1 2 2M21 16l-5 5M21 9v1M3 14v1M3 9v1M5 21a2 2 0 0 1-2-2M5 3a2 2 0 0 0-2 2M9 21h1M9 3h1", strokeWidth = 2f)
    val FreeformDashed = svg("M6.5 4.5Q8 3.5 9.5 3.5M12.5 4Q14 4.5 15.5 5.5M18 7.5Q19.5 8.8 20.5 10.5M21 13.5Q20.8 15.2 19.5 16.5M17 18.8Q15.5 20 14 20.5M11 20.5Q9.2 20.2 7.8 19.2M5.5 17.2Q4.4 16 3.8 14.2M3.5 11Q3.8 9 5 7.5", strokeWidth = 2f)
    val InvertSelection = svg("M8 3H5a2 2 0 0 0-2 2v14c0 1.1.9 2 2 2h3M16 3h3a2 2 0 0 1 2 2M21 9v1M21 14v1M21 19a2 2 0 0 1-2 2h-3M12 2v2M12 8v2M12 14v2M12 20v2", strokeWidth = 2f)
    // Трансформация выделения
    val Resize = svg("M5 3H3v2M8 3h2M13 3h2v2M15 8v2M15 13v2h-2M8 15h2M5 15H3v-2M3 8v2M19 3v12M16 6l3-3 3 3M16 12l3 3 3-3M3 19h12M6 16l-3 3 3 3M12 16l3 3-3 3", strokeWidth = 2f)
    val DottedLine = svg(d = "M2 12h2M8 12h2M14 12h2M20 12h2", strokeWidth = 2f)
    val WaveToStraightLine = svg(d = "M2 10q1.2-2.5 2.5-2M6.5 10.5l1 3M9.5 16q1.2 1.5 2-2M13.5 12h1.5M17.5 12h1.5M21.5 12h1", strokeWidth = 2f)
    val FrameCrop = svg(d = "M22 6H2M22 18H2M6 2v20M18 2v20", strokeWidth = 2f)
    val Text = svg("M12 4v16M4 7V5a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v2M9 20h6", strokeWidth = 2f)
    val StarFilled = svg("M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z")
    val TextAlignJustifyStart = svg("M3 5h18M3 12h18M3 19h10", strokeWidth = 2f)
    val TextAlignJustifyCenter = svg("M3 5h18M3 12h18M7 19h10", strokeWidth = 2f)
    val TextAlignJustifyEnd = svg("M3 5h18M3 12h18M11 19h10", strokeWidth = 2f)
    // Поворот холста
    val RefreshCcw = svg("M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8M3 3v5h5M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16M16 16h5v5", strokeWidth = 2f)
    // Размер холста
    val Scaling = svg("M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7M14 15H9v-5M16 3h5v5M21 3 9 15", strokeWidth = 2f)
    val PaintBucket = svg("M11 7 6 2M18.992 12H2.041M21.145 18.38A3.34 3.34 0 0 1 20 16.5a3.3 3.3 0 0 1-1.145 1.88c-.575.46-.855 1.02-.855 1.595A2 2 0 0 0 20 22a2 2 0 0 0 2-2.025c0-.58-.285-1.13-.855-1.595M8.5 4.5l2.148-2.148a1.205 1.205 0 0 1 1.704 0l7.296 7.296a1.205 1.205 0 0 1 0 1.704l-7.592 7.592a3.615 3.615 0 0 1-5.112 0l-3.888-3.888a3.615 3.615 0 0 1 0-5.112L5.67 7.33", strokeWidth = 2f)
    val OutlineExternal = svg("M5 2h14a3 3 0 0 1 3 3v14a3 3 0 0 1-3 3H5a3 3 0 0 1-3-3V5a3 3 0 0 1 3-3z M5 7a2 2 0 0 1 2-2M11 5h2M17 5a2 2 0 0 1 2 2M19 11v2M19 17a2 2 0 0 1-2 2M13 19h-2M7 19a2 2 0 0 1-2-2M5 13v-2", strokeWidth = 2f)
    val OutlineInternal = svg("M2 5a3 3 0 0 1 3-3M9 2h1M14 2h1M19 2a3 3 0 0 1 3 3M22 9v1M22 14v1M22 19a3 3 0 0 1-3 3M15 22h-1M10 22H9M5 22a3 3 0 0 1-3-3M2 15v-1M2 10V9 M7 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", strokeWidth = 2f)
    val OutlineCenter = svg("M5 2h14a3 3 0 0 1 3 3v14a3 3 0 0 1-3 3H5a3 3 0 0 1-3-3V5a3 3 0 0 1 3-3z M5 7a2 2 0 0 1 2-2M11 5h2M17 5a2 2 0 0 1 2 2M19 11v2M19 17a2 2 0 0 1-2 2M13 19h-2M7 19a2 2 0 0 1-2-2M5 13v-2 M9.5 8h5a1.5 1.5 0 0 1 1.5 1.5v5a1.5 1.5 0 0 1-1.5 1.5h-5a1.5 1.5 0 0 1-1.5-1.5v-5A1.5 1.5 0 0 1 9.5 8z", strokeWidth = 2f)
    val LinkVertical = svg(d = "M12 14a4 4 0 004-4V6a4 4 0 00-8 0v2M12 10a4 4 0 00-4 4v4a4 4 0 008 0v-2", strokeWidth = 2f)
    val UnlinkVertical = svg(d = "M8 10V6a4 4 0 018 0v4M8 14v4a4 4 0 008 0v-4M2 8l3 2M2 16l3-2M22 8l-3 2M22 16l-3-2", strokeWidth = 2f)
    // Стрелки в 2 противоположные стороны
    val ArrowRightLeft = svg("m16 3 4 4-4 4M20 7H4M8 21l-4-4 4-4M4 17h16", strokeWidth = 2f)
    // Линейный градиент
    val Linear = svg("M4 12h16M3 12a1 1 0 1 0 2 0 1 1 0 1 0 -2 0M19 12a1 1 0 1 0 2 0 1 1 0 1 0 -2 0", strokeWidth = 2f)
    // Радиальный градиент
    val Radial = svg("M3 12a9 9 0 1 0 18 0 9 9 0 1 0 -18 0M11 3a1 1 0 1 0 2 0 1 1 0 1 0 -2 0M12 12h9M11 12a1 1 0 1 0 2 0 1 1 0 1 0 -2 0M20 12a1 1 0 1 0 2 0 1 1 0 1 0 -2 0", strokeWidth = 2f)

    /** Кнопки инструмента "Move" */
    val Move = svg("M12 2v20M2 12h20M9 5l3-3 3 3M15 19l-3 3-3-3M5 9l-3 3 3 3M19 9l3 3-3 3", strokeWidth = 2f)
    val Inversion = svg("M2 12h2m4 0h2m4 0h2m4 0h2M8 2h8l-4 6zM8 22h8l-4-6z", strokeWidth = 2f)
    val Arrows = svg("M7 7l-5 5 5 5M17 7l5 5-5 5M8 10l4-4 4 4M8 14l4 4 4-4", strokeWidth = 2f)
    val Square = svg("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", strokeWidth = 2f)
    val MagnetOn = svg(d = "M2 1v22 M3 5.5h9a6.5 6.5 0 0 1 0 13H3v-3.5h9a3 3 0 0 0 0-6H3z M6.5 5.5v3.5m0 6v3.5", strokeWidth = 2f)
    val MagnetOff = svg(d = "M2 1v22 M7 5.5h9a6.5 6.5 0 0 1 0 13H7v-3.5h9a3 3 0 0 0 0-6H7z M10.5 5.5v3.5m0 6v3.5 M4.5 5.5l4-4 M4.5 18.5l4 4", strokeWidth = 2f)
    val Angle = svg("M21 19H4L18 5M14 19a10 10 0 0 0-3-7", strokeWidth = 2f)
    val CurveArrow = svg("M3.2 10.1A9 9 0 0 1 17 4.5l-1.8 2.7A5.8 5.8 0 0 0 6.3 10.8zm16.3-2.5l-5.8 1.2 3.5-6.7zM20.8 13.9A9 9 0 0 1 7 19.5l1.8-2.7A5.8 5.8 0 0 0 17.7 13.2zm-16.3 2.5l5.8-1.2-3.5 6.7z")
    val Opacity = svg("M2.85 3.2a0.35 0.35 0 1 0 0.7 0 0.35 0.35 0 1 0 -0.7 0M2.85 7.6a0.35 0.35 0 1 0 0.7 0 0.35 0.35 0 1 0 -0.7 0M2.85 12a0.35 0.35 0 1 0 0.7 0 0.35 0.35 0 1 0 -0.7 0M2.85 16.4a0.35 0.35 0 1 0 0.7 0 0.35 0.35 0 1 0 -0.7 0M2.85 20.8a0.35 0.35 0 1 0 0.7 0 0.35 0.35 0 1 0 -0.7 0M6.95 3.2a0.65 0.65 0 1 0 1.3 0 0.65 0.65 0 1 0 -1.3 0M6.95 7.6a0.65 0.65 0 1 0 1.3 0 0.65 0.65 0 1 0 -1.3 0M6.95 12a0.65 0.65 0 1 0 1.3 0 0.65 0.65 0 1 0 -1.3 0M6.95 16.4a0.65 0.65 0 1 0 1.3 0 0.65 0.65 0 1 0 -1.3 0M6.95 20.8a0.65 0.65 0 1 0 1.3 0 0.65 0.65 0 1 0 -1.3 0M11 3.2a1 1 0 1 0 2 0 1 1 0 1 0 -2 0M11 7.6a1 1 0 1 0 2 0 1 1 0 1 0 -2 0M11 12a1 1 0 1 0 2 0 1 1 0 1 0 -2 0M11 16.4a1 1 0 1 0 2 0 1 1 0 1 0 -2 0M11 20.8a1 1 0 1 0 2 0 1 1 0 1 0 -2 0M15 3.2a1.4 1.4 0 1 0 2.8 0 1.4 1.4 0 1 0 -2.8 0M15 7.6a1.4 1.4 0 1 0 2.8 0 1.4 1.4 0 1 0 -2.8 0M15 12a1.4 1.4 0 1 0 2.8 0 1.4 1.4 0 1 0 -2.8 0M15 16.4a1.4 1.4 0 1 0 2.8 0 1.4 1.4 0 1 0 -2.8 0M15 20.8a1.4 1.4 0 1 0 2.8 0 1.4 1.4 0 1 0 -2.8 0M19 3.2a1.8 1.8 0 1 0 3.6 0 1.8 1.8 0 1 0 -3.6 0M19 7.6a1.8 1.8 0 1 0 3.6 0 1.8 1.8 0 1 0 -3.6 0M19 12a1.8 1.8 0 1 0 3.6 0 1.8 1.8 0 1 0 -3.6 0M19 16.4a1.8 1.8 0 1 0 3.6 0 1.8 1.8 0 1 0 -3.6 0M19 20.8a1.8 1.8 0 1 0 3.6 0 1.8 1.8 0 1 0 -3.6 0")

    /** Инструменты левой панели редактора */
    val RectangleDashed = svg("M3 3a2 2 0 0 0-2 2m0 4v1m0 4v1m0 4a2 2 0 0 0 2 2m3 0h2m3 0h2m3 0h2m3 0a2 2 0 0 0 2-2m0-4v-1m0-4v-1m0-4a2 2 0 0 0-2-2m-3 0h-2m-3 0h-2m-3 0h-2", strokeWidth = 2f)
    val Brush = svg("m11 10 3 3M6.5 21A3.5 3.5 0 1 0 3 17.5a2.6 2.6 0 0 1-.7 1.8A1 1 0 0 0 3 22zM10 17 21.4 5.6a1 1 0 0 0-3-3L7 14", strokeWidth = 2f)
    val Stamp = svg("M14.5 14V9c0-2 .5-2.5.5-5a3 3 0 0 0-6 0c0 2.5.5 3 .5 5v5M22 16a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v2a1 1 0 0 0 1 1h18a1 1 0 0 0 1-1zM1 23h22", strokeWidth = 2f)
    val Eraser = svg("M22 22H7a2 2 0 0 1-1.42-.59l-4.24-4.24a2 2 0 0 1 0-2.83l11-11a2 2 0 0 1 2.83 0l6.24 6.24a2 2 0 0 1 0 2.83L13.16 22M4.34 11.34l9.07 9.07", strokeWidth = 2f)
    val Droplet = svg("M12 23a8 8 0 0 0 8-8c0-2.3-1.2-4.5-3.5-6.4s-4.1-4.7-4.5-7.6c-.4 2.9-2.4 5.7-4.5 7.6S4 12.7 4 15a8 8 0 0 0 8 8z", strokeWidth = 2f)
    val SquareDashed = svg("M5 3a2 2 0 0 0-2 2M19 3a2 2 0 0 1 2 2M21 19a2 2 0 0 1-2 2M5 21a2 2 0 0 1-2-2M9 3h1M9 21h1M14 3h1M14 21h1M3 9v1M21 9v1M3 14v1M21 14v1", strokeWidth = 2f)
    val CircleDashed = svg("M10.1 2.182a10 10 0 0 1 3.8 0M13.9 21.818a10 10 0 0 1-3.8 0M17.609 3.721a10 10 0 0 1 2.69 2.7M2.182 13.9a10 10 0 0 1 0-3.8M20.279 17.609a10 10 0 0 1-2.7 2.69M21.818 10.1a10 10 0 0 1 0 3.8M3.721 6.391a10 10 0 0 1 2.7-2.69M6.391 20.279a10 10 0 0 1-2.69-2.7", strokeWidth = 2f)
    val Lasso = svg("M3.704 14.467a10 8 0 1 1 3.115 2.375M7 22a5 5 0 0 1-2-3.994M3 16a2 2 0 1 0 4 0a2 2 0 1 0-4 0", strokeWidth = 2f)
    val VectorPolygon = svg("M12.828 4.813l5.344 2.375M15.769 18.153l3.461-8.306M5.687 14.074l7.625 4.852M9.772 5.579L5.228 11.42M9 4a2 2 0 1 0 4 0 2 2 0 1 0-4 0M13 20a2 2 0 1 0 4 0 2 2 0 1 0-4 0M18 8a2 2 0 1 0 4 0 2 2 0 1 0-4 0M2 13a2 2 0 1 0 4 0 2 2 0 1 0-4 0", strokeWidth = 2f)
    val Wand = svg("M15 4V2M15 16v-2M8 9h2M20 9h2M17.8 11.8 19 13M15 9h.01M17.8 6.2 19 5M3 21l9-9M12.2 6.2 11 5", strokeWidth = 2f)
    val SquareDashedPlus = svg("M5 3a2 2 0 0 0-2 2M19 3a2 2 0 0 1 2 2M21 19a2 2 0 0 1-2 2M5 21a2 2 0 0 1-2-2M9 3h1M9 21h1M14 3h1M14 21h1M3 9v1M21 9v1M3 14v1M21 14v1M8 12h8M12 8v8", strokeWidth = 2f)
    val SquareDashedMinus = svg("M5 3a2 2 0 0 0-2 2M19 3a2 2 0 0 1 2 2M21 19a2 2 0 0 1-2 2M5 21a2 2 0 0 1-2-2M9 3h1M9 21h1M14 3h1M14 21h1M3 9v1M21 9v1M3 14v1M21 14v1M8 12h8", strokeWidth = 2f)
    val CornerRadius = svg("M4 20V12a8 8 0 0 1 8-8h8", strokeWidth = 2f)
    val Slider = svg("M14 17H5M19 7h-9M14 17a3 3 0 1 0 6 0 3 3 0 1 0-6 0M4 7a3 3 0 1 0 6 0 3 3 0 1 0-6 0", strokeWidth = 2f)
    val Circle = svg("M12 1A11 11 0 1 0 12 23A11 11 0 1 0 12 1", strokeWidth = 2f)
    val Pointer = svg("M22 14a8 8 0 0 1-8 8M18 11v-1a2 2 0 0 0-2-2a2 2 0 0 0-2 2M14 10V9a2 2 0 0 0-2-2a2 2 0 0 0-2 2v1M10 9.5V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v10M18 11a2 2 0 1 1 4 0v3a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15", strokeWidth = 2f)
    val HealingBrush = svg("M14.5 6.5l3.9-3.9a1 1 0 0 1 3 3L17.5 9.5 M6.5 21A3.5 3.5 0 1 0 3 17.5a2.62 2.62 0 0 1-.708 1.792A1 1 0 0 0 3 21z M10.5 3.5l10 10a4.95 4.95 0 0 1-7 7l-10-10a4.95 4.95 0 0 1 7-7z M6 13.5l7-7M10.5 18l7-7 M10.5 12h.01M13.5 12h.01M12 10.5h.01M12 13.5h.01", strokeWidth = 2f)
    val Locate = svg("M2 12h3M19 12h3M12 2v3M12 19v3M12 5a7 7 0 0 1 0 14a7 7 0 0 1 0 -14", strokeWidth = 2f)

    /** Панель выбора цвета */
    val Pipette = svg("m12 9-8.414 8.414A2 2 0 0 0 3 18.828v1.344a2 2 0 0 1-.586 1.414A2 2 0 0 1 3.828 21h1.344a2 2 0 0 0 1.414-.586L15 12 M18 9l.4.4a1 1 0 1 1-3 3l-3.8-3.8a1 1 0 1 1 3-3l.4.4 3.4-3.4a1 1 0 1 1 3 3z M2 22l.414-.414", strokeWidth = 2f)
    val Grip = svg("M4 4h2v2H4zM11 4h2v2h-2zM18 4h2v2h-2z M4 11h2v2H4zM11 11h2v2h-2zM18 11h2v2h-2z M4 18h2v2H4zM11 18h2v2h-2zM18 18h2v2h-2z",strokeWidth = 2f)
    val SavePlus = svg("M12.5 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h10.2a2 2 0 0 1 1.4.6l3.8 3.8a2 2 0 0 1 .6 1.4V12M16 13H8a1 1 0 0 0-1 1v7M19 22v-6M22 19h-6M7 3v4a1 1 0 0 0 1 1h7", strokeWidth = 2f)
}