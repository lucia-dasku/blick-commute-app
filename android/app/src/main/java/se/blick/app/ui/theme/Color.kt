package se.blick.app.ui.theme

import androidx.compose.ui.graphics.Color

// Deliberately calm, low-saturation palette — matches the product principle of a
// "calm information display" (no alarm reds, no urgent oranges for ordinary states).
val CalmBlue40 = Color(0xFF3A5A78)
val CalmBlue80 = Color(0xFFA9C7E0)
val Neutral40 = Color(0xFF5C6068)
val Neutral80 = Color(0xFFC5C8CF)
val AttentionAmber40 = Color(0xFF8A5A00) // reserved for genuine disruptions only
val RoutineDestructiveRed = Color(0xFFCE3134)
val BlickBrandMint = Color(0xFF33E4A1)

// Dedicated normal-Light layers for One-time Event details. These deliberately do not alter the
// Material surface palette used by the rest of the application.
internal val LightOneTimeEventCardSurface = Color(0xFFFCF8F7)
internal val LightOneTimeEventCardDivider = Color(0xFFE7DEDC)

// Dedicated normal-Light treatment for the Premium offer in routine creation.
internal val LightPremiumUpsellCardSurface = Color(0xFFFCF8F7)
internal val LightPremiumUpsellCardBorder = Color(0xFFE7DEDC)

internal val LightJourneyFilterSurface = Color(0xFFF4ECEA)
internal val LightJourneyFilterSelectedSurface = Color(0xFFEDE4E2)
internal val LightJourneyFilterBorder = Color(0xFFB6A29D)
