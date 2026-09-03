package se.blick.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import se.blick.app.R

private val LightColors = lightColorScheme(
    primary = CalmBlue40,
    secondary = Neutral40,
    tertiary = AttentionAmber40,
    background = Color(0xFFFAF4F3),
)

/** The normal Dark Material 3 `surfaceContainerHigh` used by default AlertDialog containers.
 * Named here so non-Compose surfaces such as the Glance widget can match the dialog exactly. */
internal val BasicDarkDialogSurface = Color(0xFF2B2930)

private val DarkColors = darkColorScheme(
    primary = CalmBlue80,
    secondary = Neutral80,
    tertiary = AttentionAmber40,
    surfaceContainerHigh = BasicDarkDialogSurface,
)

/** Opaque foreground layers used over the decorative Stockholm Night artwork. */
internal object StockholmNightSurfaces {
    val Card = Color(0xFF09172F)
    val Control = Color(0xFF07142B)
    val SelectedControl = Color(0xFF253852)
    val CardBorder = Color(0xFF14243B)
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
internal const val LIGHT_CITY_BACKGROUND_TAG = "light_city_background"
internal val LocalStockholmNightTheme = staticCompositionLocalOf { false }
internal val LocalLightCityTheme = staticCompositionLocalOf { false }

/** Page-level containers are transparent only while the shared light-city canvas is present.
 * Dark keeps its existing opaque Material background, while Stockholm Night keeps its existing
 * transparent background over the premium artwork. */
@Composable
internal fun themedScreenContainerColor(): Color =
    if (LocalLightCityTheme.current) Color.Transparent else MaterialTheme.colorScheme.background

internal data class StickyActionVisuals(
    val containerColor: Color,
    val shadowElevation: Dp,
)

/** Sticky save actions float directly over the shared city canvas in normal Light. Normal Dark
 * and Stockholm Night retain their existing opaque Material surface and elevation. */
@Composable
internal fun themedStickyActionVisuals(): StickyActionVisuals =
    if (LocalLightCityTheme.current) {
        StickyActionVisuals(Color.Transparent, 0.dp)
    } else {
        StickyActionVisuals(MaterialTheme.colorScheme.surface, 8.dp)
    }

/** The single normal-light decorative layer. Production places this inside
 * [se.blick.app.ads.BannerAwareContent]'s weighted content region, so its bottom edge can never
 * extend beneath an eligible banner. The source artwork's 1447x1087 proportions are preserved;
 * wide/short windows may therefore show background-colour space beside it rather than cropping
 * or stretching any buildings. */
@Composable
fun BlickLightBackground(content: @Composable () -> Unit) {
    if (!LocalLightCityTheme.current) {
        content()
        return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Image(
            painter = painterResource(R.drawable.light_city_background),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomCenter,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1447f / 1087f)
                .align(Alignment.BottomCenter)
                .testTag(LIGHT_CITY_BACKGROUND_TAG),
        )
        content()
    }
}

@Composable
fun BlickTheme(
    useDarkTheme: Boolean? = null,
    useStockholmNightTheme: Boolean = false,
    systemDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val darkTheme = useStockholmNightTheme || (useDarkTheme ?: systemDarkTheme)
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
        CompositionLocalProvider(
            LocalStockholmNightTheme provides useStockholmNightTheme,
            LocalLightCityTheme provides (!darkTheme && !useStockholmNightTheme),
        ) {
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
