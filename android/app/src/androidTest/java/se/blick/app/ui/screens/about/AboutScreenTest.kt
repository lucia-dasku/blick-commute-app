package se.blick.app.ui.screens.about

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.blick.app.R
import se.blick.app.billing.EntitlementState
import se.blick.app.notification.NotificationAvailability
import se.blick.app.ui.theme.AppearanceMode
import se.blick.app.ui.theme.BlickLightBackground
import se.blick.app.ui.theme.BlickTheme

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun lightSettingsHeaderPixelsStayUnchangedWhileContentScrolls() {
        assertSettingsHeaderDoesNotChangeDuringScroll(useDarkTheme = false)
    }

    @Test
    fun stockholmNightSettingsHeaderPixelsStayUnchangedWhileContentScrolls() {
        assertSettingsHeaderDoesNotChangeDuringScroll(useDarkTheme = true, useStockholmNightTheme = true)
    }

    @Test
    fun darkSettingsHeaderKeepsItsExistingScrollBehavior() {
        assertSettingsHeaderDoesNotChangeDuringScroll(useDarkTheme = true)
    }

    private fun assertSettingsHeaderDoesNotChangeDuringScroll(
        useDarkTheme: Boolean,
        useStockholmNightTheme: Boolean = false,
    ) {
        composeRule.setContent {
            Box(Modifier.size(width = 360.dp, height = 480.dp)) {
                BlickTheme(useDarkTheme = useDarkTheme, useStockholmNightTheme = useStockholmNightTheme) {
                    BlickLightBackground {
                        AboutContent(onBack = {}, onLanguageSelected = {})
                    }
                }
            }
        }

        val title = composeRule.onNodeWithText(composeRule.activity.getString(R.string.about_title))
        val back = composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.action_back))
        val titleBounds = title.fetchSemanticsNode().boundsInRoot
        val backBounds = back.fetchSemanticsNode().boundsInRoot
        val before = composeRule.onRoot().captureToImage().toPixelMap()
        val headerBottom = maxOf(titleBounds.bottom, backBounds.bottom).toInt().coerceAtMost(before.height)
        val content = composeRule.onNode(hasScrollAction())
        var previousScroll = 0f

        repeat(3) {
            content.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, 150f) }
            val currentScroll = content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("The Settings content must actually scroll", currentScroll > previousScroll)
            previousScroll = currentScroll
            assertEquals(titleBounds, title.fetchSemanticsNode().boundsInRoot)
            assertEquals(backBounds, back.fetchSemanticsNode().boundsInRoot)

            val after = composeRule.onRoot().captureToImage().toPixelMap()
            var changedPixels = 0
            for (y in 0 until headerBottom) {
                for (x in 0 until before.width) {
                    if (before[x, y] != after[x, y]) changedPixels++
                }
            }
            assertEquals("Scrolling content must not change the header/status-inset pixels", 0, changedPixels)
        }
    }

    @Test
    fun backButtonInvokesOnBack() {
        var backInvoked = false
        composeRule.setContent { AboutContent(onBack = { backInvoked = true }, onLanguageSelected = {}) }

        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.action_back)).performClick()

        assertEquals(true, backInvoked)
    }

    @Test
    fun languageRowShowsCurrentLanguageAndKeepsEnglishSwedishSelection() {
        var selected: String? = null
        composeRule.setContent { AboutContent(onBack = {}, onLanguageSelected = { selected = it }) }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_language_label)).performClick()
        composeRule.onNodeWithTag(LANGUAGE_OPTION_EN_TAG).assertExists()
        composeRule.onNodeWithTag(LANGUAGE_OPTION_SV_TAG).performClick()

        assertEquals("sv", selected)
    }

    @Test
    fun appearanceRowShowsStoredModeAndReturnsDarkSelection() {
        var selected: AppearanceMode? = null
        composeRule.setContent {
            AboutContent(
                state = AboutUiState(appearanceMode = AppearanceMode.System),
                onBack = {},
                onLanguageSelected = {},
                onAppearanceSelected = { selected = it },
            )
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_appearance_label)).performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_appearance_dark)).performClick()

        assertEquals(AppearanceMode.Dark, selected)
    }

    @Test
    fun premiumUserCanSelectStockholmNightAppearance() {
        var selected: AppearanceMode? = null
        composeRule.setContent {
            AboutContent(
                state = AboutUiState(entitlement = EntitlementState.Premium),
                onBack = {},
                onLanguageSelected = {},
                onAppearanceSelected = { selected = it },
            )
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_appearance_label)).performClick()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.settings_appearance_stockholm_night),
        ).performClick()

        assertEquals(AppearanceMode.StockholmNight, selected)
    }

    @Test
    fun freeUserSelectingStockholmNightOpensPremiumInsteadOfChangingAppearance() {
        var selected: AppearanceMode? = null
        var premiumOpened = false
        composeRule.setContent {
            AboutContent(
                state = AboutUiState(entitlement = EntitlementState.Free),
                onBack = {},
                onLanguageSelected = {},
                onAppearanceSelected = { selected = it },
                onOpenPremium = { premiumOpened = true },
            )
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_appearance_label)).performClick()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.settings_appearance_stockholm_night),
        ).performClick()

        assertEquals(null, selected)
        assertEquals(true, premiumOpened)
    }

    @Test
    fun premiumStateComesFromEntitlementAndRowOpensPremium() {
        var opened = false
        composeRule.setContent {
            AboutContent(
                state = AboutUiState(entitlement = EntitlementState.Premium),
                onBack = {},
                onLanguageSelected = {},
                onOpenPremium = { opened = true },
            )
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_premium_label))
            .assertTextContains(composeRule.activity.getString(R.string.settings_premium_status_premium))
            .performScrollTo()
            .performClick()

        assertEquals(true, opened)
    }

    @Test
    fun notificationPermissionStateIsShownAndSystemSettingsRowIsClickable() {
        var opened = false
        composeRule.setContent {
            AboutContent(
                state = AboutUiState(notificationAvailability = NotificationAvailability.PermissionMissing),
                onBack = {},
                onLanguageSelected = {},
                onOpenNotifications = { opened = true },
            )
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_notifications_permission_required))
            .assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_notifications_label)).performClick()

        assertEquals(true, opened)
    }

    @Test
    fun LiveUpdatesRowShowsOnWhenPromotedNotificationsAreEnabled() {
        composeRule.setContent {
            AboutContent(
                state = AboutUiState(liveUpdatesEnabled = true),
                sdkInt = 36,
                onBack = {},
                onLanguageSelected = {},
            )
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_live_updates_label))
            .assertTextContains(composeRule.activity.getString(R.string.settings_notifications_on))
    }

    @Test
    fun LiveUpdatesRowShowsOffWhenPromotedNotificationsAreDisabled() {
        composeRule.setContent {
            AboutContent(
                state = AboutUiState(liveUpdatesEnabled = false),
                sdkInt = 36,
                onBack = {},
                onLanguageSelected = {},
            )
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_live_updates_label))
            .assertTextContains(composeRule.activity.getString(R.string.settings_notifications_off))
    }

    @Test
    fun LiveUpdatesRowShowsAndroid16RequirementOnOlderVersions() {
        composeRule.setContent {
            AboutContent(
                state = AboutUiState(liveUpdatesEnabled = false),
                sdkInt = 35,
                onBack = {},
                onLanguageSelected = {},
            )
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_live_updates_label))
            .assertTextContains(
                composeRule.activity.getString(R.string.settings_live_updates_requires_android_16),
            )
    }

    @Test
    fun legalRowsAreCompactNavigationActions() {
        var privacy = false
        var data = false
        var licences = false
        composeRule.setContent {
            AboutContent(
                onBack = {},
                onLanguageSelected = {},
                onOpenPrivacyPolicy = { privacy = true },
                onOpenDataAttribution = { data = true },
                onOpenOpenSourceLicences = { licences = true },
            )
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.about_section_privacy_policy))
            .performScrollTo().assertHasClickAction().performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.about_section_data_attribution))
            .performScrollTo().assertHasClickAction().performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.about_section_open_source_licences))
            .performScrollTo().assertHasClickAction().performClick()

        assertEquals(true, privacy)
        assertEquals(true, data)
        assertEquals(true, licences)
    }

    @Test
    fun privacyChoicesRowIsVisibleAndInvokesCallbackWhenRequired() {
        var opened = false
        composeRule.setContent {
            AboutContent(
                onBack = {},
                onLanguageSelected = {},
                privacyOptionsRequired = true,
                onOpenPrivacyOptions = { opened = true },
            )
        }

        composeRule.onNodeWithTag(PRIVACY_CHOICES_TAG)
            .performScrollTo()
            .assertHasClickAction()
            .performClick()

        assertEquals(true, opened)
    }

    @Test
    fun privacyChoicesRowIsAbsentWhenNotRequired() {
        composeRule.setContent {
            AboutContent(
                onBack = {},
                onLanguageSelected = {},
                privacyOptionsRequired = false,
            )
        }

        composeRule.onNodeWithTag(PRIVACY_CHOICES_TAG).assertDoesNotExist()
    }

    @Test
    fun dataAttributionDestinationStillShowsRequiredAttributionAndDisclaimer() {
        composeRule.setContent { DataAttributionScreen(onBack = {}) }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.attribution_text)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.about_disclaimer)).assertExists()
    }

    @Test
    fun privacyDestinationStillShowsExistingPolicy() {
        composeRule.setContent { PrivacyPolicyScreen(onBack = {}) }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.about_privacy_last_updated)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.about_privacy_no_account)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.about_privacy_advertising))
            .performScrollTo().assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.about_privacy_contact)).performScrollTo().assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.about_privacy_read_more))
            .performScrollTo().assertExists()
        composeRule.onNodeWithTag(PRIVACY_POLICY_LINK_TAG).assertHasClickAction()
    }

    @Test
    fun licencesDestinationStillShowsExistingContentAndLink() {
        composeRule.setContent { OpenSourceLicencesScreen(onBack = {}) }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.about_open_source_licences_body)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.about_open_source_licences_action))
            .assertHasClickAction()
    }

    @Test
    fun legalDestinationsUseDifferentExpectedUrls() {
        assertEquals("https://blick-labs.vercel.app/blick-privacy", PRIVACY_POLICY_URL)
        assertEquals("https://blick-labs.vercel.app/licenses.html", OPEN_SOURCE_LICENCES_URL)
        assertNotEquals(PRIVACY_POLICY_URL, OPEN_SOURCE_LICENCES_URL)
    }
}
