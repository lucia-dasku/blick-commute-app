package se.blick.app.ui.theme

/** User-facing appearance choices. The Premium background is persisted separately so the
 * last regular System/Light/Dark choice remains available as a downgrade fallback. */
enum class AppearanceMode(val useDarkTheme: Boolean?) {
    System(null),
    Light(false),
    Dark(true),
    StockholmNight(true),
    ;

    companion object {
        fun from(
            useDarkTheme: Boolean?,
            useStockholmNightTheme: Boolean = false,
            hasPremiumAccess: Boolean = false,
        ): AppearanceMode = when {
            useStockholmNightTheme && hasPremiumAccess -> StockholmNight
            useDarkTheme == null -> System
            useDarkTheme == false -> Light
            else -> Dark
        }
    }
}

internal fun shouldUseStockholmNightTheme(requested: Boolean, hasPremiumAccess: Boolean): Boolean =
    requested && hasPremiumAccess
