package se.blick.app.ui.theme

/** User-facing representation of the existing nullable dark-theme preference. */
enum class AppearanceMode(val useDarkTheme: Boolean?) {
    System(null),
    Light(false),
    Dark(true),
    ;

    companion object {
        fun from(useDarkTheme: Boolean?): AppearanceMode = when (useDarkTheme) {
            null -> System
            false -> Light
            true -> Dark
        }
    }
}
