package se.blick.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.blick.app.R
import se.blick.app.ui.theme.BlickBrandMint

private val Manrope = FontFamily(
    Font(R.font.manrope_variable, weight = FontWeight.Medium),
)

@Composable
fun BlickWordmark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.testTag("blick_wordmark"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(width = 23.dp, height = 30.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                colorFilter = ColorFilter.tint(BlickBrandMint),
                modifier = Modifier.requiredSize(58.dp),
            )
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text = stringResource(R.string.brand_wordmark),
            color = BlickBrandMint,
            fontFamily = Manrope,
            fontWeight = FontWeight.Medium,
            fontSize = 31.sp,
            lineHeight = 38.sp,
            letterSpacing = (-0.4).sp,
        )
    }
}
