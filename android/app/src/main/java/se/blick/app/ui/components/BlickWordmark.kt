package se.blick.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import se.blick.app.R

private val Manrope = FontFamily(
    Font(R.font.manrope_variable, weight = FontWeight.ExtraBold),
)

@Composable
fun BlickWordmark(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.brand_wordmark),
        color = MaterialTheme.colorScheme.primary,
        fontFamily = Manrope,
        fontWeight = FontWeight.Black,
        fontSize = 38.sp,
        letterSpacing = (-0.7).sp,
        modifier = modifier.testTag("blick_wordmark"),
    )
}
