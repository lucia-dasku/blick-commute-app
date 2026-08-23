package se.blick.app.locale

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards against future drift between `res/values/strings.xml` (English, the complete/default
 * resource set) and `res/values-sv/strings.xml` (Swedish) — a plain JVM test reading both files
 * directly as text, not through Android resource resolution (which needs a real or Robolectric
 * [android.content.Context]), so this runs fast with no Android dependency at all.
 *
 * Reads the actual source files via a path relative to the `app` module directory — Gradle's
 * `Test` task working directory — rather than via the classpath; [assertTrue] on [File.exists]
 * below fails loudly with the exact path if that assumption is ever wrong, rather than silently
 * skipping real coverage.
 */
class StringResourceParityTest {

    // Deliberately never translated: the app name and brand-lockup strings are fixed names.
    // Blick's two language-picker option labels also always name themselves in their own
    // language, so an "English"/"Svenska" entry must stay identical everywhere.
    private val intentionallyUntranslated = setOf(
        "app_name",
        "brand_wordmark",
        "brand_home_title",
        "brand_stockholm_night_subtitle",
        "settings_language_option_english",
        "settings_language_option_swedish",
    )

    private val stringPattern = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholderPattern = Regex("""%\d\$[sd]""")

    private fun parse(relativePath: String): Map<String, String> {
        val file = File(relativePath)
        assertTrue(
            "expected to find $relativePath relative to the app module directory (${file.absolutePath})",
            file.exists(),
        )
        val content = file.readText(Charsets.UTF_8)
        return stringPattern.findAll(content).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val english by lazy { parse("src/main/res/values/strings.xml") }
    private val swedish by lazy { parse("src/main/res/values-sv/strings.xml") }

    @Test
    fun `every translatable English string has a Swedish counterpart, and nothing else`() {
        val expectedSwedishKeys = english.keys - intentionallyUntranslated

        val missing = expectedSwedishKeys - swedish.keys
        assertTrue("missing Swedish translations for: $missing", missing.isEmpty())

        val unexpectedExtra = swedish.keys - english.keys
        assertTrue("values-sv/strings.xml has keys with no English counterpart: $unexpectedExtra", unexpectedExtra.isEmpty())

        val wronglyOverridden = swedish.keys intersect intentionallyUntranslated
        assertTrue(
            "these must stay identical in every locale and should not be overridden in values-sv/strings.xml: $wronglyOverridden",
            wronglyOverridden.isEmpty(),
        )
    }

    @Test
    fun `every format placeholder in an English string has the exact same placeholders in its Swedish counterpart`() {
        val sharedKeys = english.keys intersect swedish.keys

        val mismatches = sharedKeys.filter { key ->
            val englishPlaceholders = placeholderPattern.findAll(english.getValue(key)).map { it.value }.sorted().toList()
            val swedishPlaceholders = placeholderPattern.findAll(swedish.getValue(key)).map { it.value }.sorted().toList()
            englishPlaceholders != swedishPlaceholders
        }
        assertTrue("placeholder mismatch between English and Swedish for: $mismatches", mismatches.isEmpty())
    }

    @Test
    fun `neither resource file is suspiciously small -- a parsing regression would not just report zero strings`() {
        assertTrue("expected a substantial number of English strings, parsed ${english.size}", english.size > 100)
        assertTrue("expected a substantial number of Swedish strings, parsed ${swedish.size}", swedish.size > 100)
    }
}
