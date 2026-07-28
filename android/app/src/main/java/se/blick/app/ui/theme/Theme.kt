package se.blick.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = CalmBlue40,
    secondary = Neutral40,
    tertiary = AttentionAmber40,
)

private val DarkColors = darkColorScheme(
    primary = CalmBlue80,
    secondary = Neutral80,
    tertiary = AttentionAmber40,
)

@Composable
fun BlickTheme(
    useDarkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val darkTheme = useDarkTheme ?: isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = BlickTypography,
        content = content,
    )
}
