package se.blick.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.core.view.WindowCompat
import se.blick.app.R

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

/** Opaque foreground layers used over the decorative Stockholm Night artwork. */
internal object StockholmNightSurfaces {
    val Card = Color(0xFF09172F)
    val Control = Color(0xFF07142B)
    val SelectedControl = Color(0xFF253852)
    val Border = Color(0xFF2A4260)
    val Divider = Color(0xFF263B58)
}

private val StockholmNightColors = DarkColors.copy(
    background = Color.Transparent,
    surface = StockholmNightSurfaces.Card,
    surfaceVariant = StockholmNightSurfaces.Control,
    surfaceContainerLowest = StockholmNightSurfaces.Control,
    surfaceContainerLow = StockholmNightSurfaces.Control,
    surfaceContainer = StockholmNightSurfaces.Card,
    surfaceContainerHigh = Color(0xFF10213B),
    surfaceContainerHighest = StockholmNightSurfaces.SelectedControl,
    outline = Color(0xFF3B5574),
    outlineVariant = StockholmNightSurfaces.Divider,
    surfaceTint = Color.Transparent,
)

internal const val STOCKHOLM_NIGHT_BACKGROUND_TAG = "stockholm_night_background"
internal val LocalStockholmNightTheme = staticCompositionLocalOf { false }

@Composable
fun BlickTheme(
    useDarkTheme: Boolean? = null,
    useStockholmNightTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = useStockholmNightTheme || (useDarkTheme ?: isSystemInDarkTheme())
    val colorScheme = when {
        useStockholmNightTheme -> StockholmNightColors
        darkTheme -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = BlickTypography,
    ) {
        CompositionLocalProvider(LocalStockholmNightTheme provides useStockholmNightTheme) {
            if (useStockholmNightTheme) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkColors.background),
                ) {
                    Image(
                        painter = painterResource(R.drawable.premium_stockholm_night_background),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.BottomCenter,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(STOCKHOLM_NIGHT_BACKGROUND_TAG),
                    )
                    content()
                }
            } else {
                content()
            }
        }
    }
}
