package fr.alerteresidents.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Couleurs de statut adaptées au thème clair / sombre (jamais de fond clair codé en dur). */
@Immutable
data class StatusColors(
    val danger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val safe: Color,
    val safeContainer: Color,
    val onSafeContainer: Color,
    val neutral: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    /** Couleurs « pleines » pour les fonds avec texte blanc : identiques en clair et en sombre. */
    val dangerSolid: Color = AlertRed,
    val warningSolid: Color = Color(0xFFB45309),
    val safeSolid: Color = Color(0xFF047857),
    val neutralSolid: Color = Color(0xFF475569),
    val infoSolid: Color = Color(0xFF6D28D9)
)

private val LightStatus = StatusColors(
    danger = AlertRed, dangerContainer = AlertRedContainer, onDangerContainer = OnAlertRedContainer,
    warning = WarningAmber, warningContainer = WarningAmberContainer, onWarningContainer = Color(0xFF7C2D12),
    safe = Color(0xFF047857), safeContainer = SafeGreenContainer, onSafeContainer = OnSafeGreenContainer,
    neutral = Color(0xFF475569), neutralContainer = Color(0xFFE2E8F0), onNeutralContainer = Color(0xFF1E293B),
    info = Color(0xFF6D28D9), infoContainer = Color(0xFFEDE9FE), onInfoContainer = Color(0xFF4C1D95)
)

private val DarkStatus = StatusColors(
    danger = Color(0xFFF87171), dangerContainer = Color(0xFF4C1515), onDangerContainer = Color(0xFFFECACA),
    warning = Color(0xFFFBBF24), warningContainer = Color(0xFF45300A), onWarningContainer = Color(0xFFFDE68A),
    safe = Color(0xFF34D399), safeContainer = Color(0xFF0B3B2C), onSafeContainer = Color(0xFFA7F3D0),
    neutral = Color(0xFF94A3B8), neutralContainer = Color(0xFF334155), onNeutralContainer = Color(0xFFE2E8F0),
    info = Color(0xFFC4B5FD), infoContainer = Color(0xFF2E1065), onInfoContainer = Color(0xFFEDE9FE)
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlueDark,
    onPrimary = Color.Black,
    primaryContainer = SafeNavy,
    onPrimaryContainer = Color.White,
    secondary = DeepTeal,
    onSecondary = Color.White,
    background = SurfaceDark,
    surface = CardDark,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFF475569),
    error = Color(0xFFF87171),
    onError = Color.Black,
    errorContainer = Color(0xFF4C1515),
    onErrorContainer = Color(0xFFFECACA)
)

private val LightColorScheme = lightColorScheme(
    primary = SafeNavy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = SafeNavy,
    secondary = DeepTeal,
    onSecondary = Color.White,
    background = SurfaceLight,
    surface = CardLight,
    onSurface = Color(0xFF1E293B),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outlineVariant = Color(0xFFCBD5E1),
    error = AlertRed,
    onError = Color.White,
    errorContainer = AlertRedContainer,
    onErrorContainer = OnAlertRedContainer
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            content = content
        )
    }
}

/** Raccourci : `AppStatusColors.danger` dans un composable. */
val AppStatusColors: StatusColors
    @Composable get() = LocalStatusColors.current
