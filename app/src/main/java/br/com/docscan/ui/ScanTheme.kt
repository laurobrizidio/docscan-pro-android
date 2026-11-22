package br.com.docscan.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val light = lightColorScheme(
    primary = Color(0xFF00664C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0F2E2),
    secondaryContainer = Color(0xFFDBEFE4),
    onSecondaryContainer = Color(0xFF173C30),
    onPrimaryContainer = Color(0xFF073E2E),
    background = Color(0xFFF7F9F6),
    onBackground = Color(0xFF17251F),
    surface = Color(0xFFF7F9F6),
    onSurface = Color(0xFF17251F),
    surfaceVariant = Color(0xFFE8EFE9),
    onSurfaceVariant = Color(0xFF465851),
    surfaceContainer = Color(0xFFEFF4EF),
    surfaceContainerHigh = Color(0xFFE6EEE8),
    outline = Color(0xFF74867D),
    outlineVariant = Color(0xFFCCD9D1),
)

private val dark = darkColorScheme(
    primary = Color(0xFF99F0CD),
    onPrimary = Color(0xFF07382A),
    primaryContainer = Color(0xFF204C3C),
    secondaryContainer = Color(0xFF304A3F),
    onSecondaryContainer = Color(0xFFCBE9DB),
    onPrimaryContainer = Color(0xFFB9F7DB),
    background = Color(0xFF111917),
    onBackground = Color(0xFFF0F5EF),
    surface = Color(0xFF111917),
    onSurface = Color(0xFFF0F5EF),
    surfaceVariant = Color(0xFF263832),
    onSurfaceVariant = Color(0xFFAFBFB5),
    surfaceContainer = Color(0xFF17231E),
    surfaceContainerHigh = Color(0xFF202E27),
    outline = Color(0xFF82968B),
    outlineVariant = Color(0xFF394F45),
)

internal val specStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
)

@Composable
internal fun ScanTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) dark else light,
        typography = Typography(
            headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.9).sp),
            headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 28.sp, letterSpacing = (-0.35).sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp),
            titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp),
        ),
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
