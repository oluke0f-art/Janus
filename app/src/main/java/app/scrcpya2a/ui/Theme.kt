package app.scrcpya2a.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Paleta do Janus: preto com violeta escuro e vivo. */
object JanusColors {
    val Bg = Color(0xFF07040E)
    val Bg2 = Color(0xFF110626)
    val Card = Color(0xFF150C2D)
    val CardHigh = Color(0xFF1E1146)
    val Violet = Color(0xFF5A14F0) // roxo escuro e vivo: botões e destaques
    val VioletDeep = Color(0xFF34098F) // sombras duras
    val VioletBright = Color(0xFF9468FF) // bordas e realces
    val Lilac = Color(0xFFD2BFFF) // textos de destaque
    val Border = Color(0xFF3A1F86)
    val Shadow = Color(0xFF22104E)
    val Ok = Color(0xFF3DDC84)
}

private val Colors = darkColorScheme(
    primary = JanusColors.Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B0F73),
    onPrimaryContainer = JanusColors.Lilac,
    secondary = JanusColors.VioletBright,
    onSecondary = Color(0xFF1A0750),
    secondaryContainer = JanusColors.CardHigh,
    onSecondaryContainer = JanusColors.Lilac,
    tertiary = JanusColors.Lilac,
    background = JanusColors.Bg,
    onBackground = Color(0xFFEDE6FF),
    surface = JanusColors.Bg,
    onSurface = Color(0xFFEDE6FF),
    surfaceVariant = JanusColors.Card,
    onSurfaceVariant = Color(0xFFBDB0DD),
    outline = Color(0xFF6A4BC4),
    outlineVariant = JanusColors.Border,
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A0000),
    errorContainer = Color(0xFF5C0F1B),
    onErrorContainer = Color(0xFFFFD9D6),
)

/** Tema único e escuro (identidade do app). */
@Composable
fun JanusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
