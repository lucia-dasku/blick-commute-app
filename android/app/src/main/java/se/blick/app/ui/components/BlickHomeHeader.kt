package se.blick.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.blick.app.R
import se.blick.app.ui.theme.BlickBrandMint

private val DarkBrandTitle = Color(0xFFF5F7FB)
private val LightBrandTitle = Color(0xFF747C87)
private val SettingsBorder = Color(0xFF123264)
private val SettingsIcon = Color(0xFF5579B3)

private val BlickHomeBrandFont = FontFamily(
    Font(R.font.manrope_variable, weight = FontWeight.Medium),
    Font(R.font.manrope_variable, weight = FontWeight.SemiBold),
    Font(R.font.manrope_variable, weight = FontWeight.Bold),
)

internal const val BLICK_HOME_HEADER_TAG = "blick_home_header"
internal const val BLICK_HOME_LOGO_TAG = "blick_home_logo"
internal const val BLICK_HOME_TITLE_TAG = "blick_home_title"
internal const val BLICK_HOME_SUBTITLE_TAG = "blick_home_subtitle"
internal const val BLICK_HOME_SETTINGS_TAG = "blick_home_settings"
internal const val BLICK_HOME_SETTINGS_CIRCLE_TAG = "blick_home_settings_circle"

/** Shared home lockup. Stockholm Night changes only its subtitle and keeps the same geometry. */
@Composable
fun BlickHomeHeader(
    useStockholmNightBranding: Boolean,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val useDarkTitle = useStockholmNightBranding || MaterialTheme.colorScheme.background.luminance() < 0.5f
    val titleColor = if (useDarkTitle) DarkBrandTitle else LightBrandTitle
    val subtitle = if (useStockholmNightBranding) {
        R.string.brand_stockholm_night_subtitle
    } else {
        R.string.brand_home_subtitle
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(70.dp)
            // The settings touch target is 48dp around a 42dp visual circle. Its 3dp
            // internal inset makes the visible circle land at the requested 24dp edge.
            .padding(start = 24.dp, top = 8.dp, end = 21.dp, bottom = 12.dp)
            .testTag(BLICK_HOME_HEADER_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 37.dp, height = 48.dp)
                .testTag(BLICK_HOME_LOGO_TAG),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                colorFilter = ColorFilter.tint(BlickBrandMint),
                modifier = Modifier.requiredSize(92.dp),
            )
        }

        Spacer(Modifier.width(9.dp))

        Column(
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.brand_home_title),
                color = titleColor,
                style = TextStyle(
                    fontFamily = BlickHomeBrandFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    lineHeight = 30.sp,
                    letterSpacing = 0.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                modifier = Modifier.testTag(BLICK_HOME_TITLE_TAG),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(subtitle),
                color = BlickBrandMint,
                style = TextStyle(
                    fontFamily = BlickHomeBrandFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    letterSpacing = 2.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                modifier = Modifier
                    .padding(end = 3.dp)
                    .testTag(BLICK_HOME_SUBTITLE_TAG),
            )
        }

        Spacer(Modifier.weight(1f))

        IconButton(
            onClick = onOpenAbout,
            modifier = Modifier
                .size(48.dp)
                .testTag(BLICK_HOME_SETTINGS_TAG),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .border(width = 1.dp, color = SettingsBorder, shape = CircleShape)
                    .testTag(BLICK_HOME_SETTINGS_CIRCLE_TAG),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.about_action),
                    tint = SettingsIcon,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
