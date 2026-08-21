import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "se.blick.app"

    val productionBackendBaseUrl = "https://blick-backend.vercel.app/"
    val debugBackendBaseUrl = (project.findProperty("BLICK_BACKEND_BASE_URL") as String?)
        ?: productionBackendBaseUrl

    // Pinned to concrete current-stable numbers verified during scaffolding
    // (2026-07-27) — see android/README.md for sources. Not left as "latest stable".
    compileSdk = 36

    defaultConfig {
        applicationId = "se.blick.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // BACKEND_BASE_URL is assigned per build type below. Debug may use the documented
        // local Gradle-property override; release is always pinned to production.
    }

    buildTypes {
        debug {
            // Local/staging endpoint overrides are intentionally debug-only. The release
            // variant below is pinned to the production verification backend.
            buildConfigField("String", "BACKEND_BASE_URL", "\"$debugBackendBaseUrl\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "BACKEND_BASE_URL", "\"$productionBackendBaseUrl\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Per-app language milestone -- auto-derives the generated locale-config resource
        // (and its android:localeConfig manifest attribute) from the locales this app actually
        // has resources for (this default values/ set = en, plus values-sv/), so Android 13+'s
        // own per-app language system picker lists exactly English/Svenska with no hand-authored
        // res/xml/locales_config.xml to keep in sync by hand.
        generateLocaleConfig = true
    }

    bundle {
        language {
            // Required companion setting for AppCompatDelegate.setApplicationLocales-based
            // in-app language switching (see locale/AppLocale.kt) -- confirmed by a real Lint
            // AppBundleLocaleChanges warning during this milestone. Without this, a Play-
            // distributed App Bundle install ships resources for only the DEVICE's own
            // language by default; a user switching Blick's own language to the OTHER one
            // could pick a language whose resources were never installed at all. Only affects
            // .aab (Play Store) builds -- a plain assembleDebug/assembleRelease APK already
            // always includes every locale regardless of this setting.
            enableSplit = false
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Without this, any Android SDK stub called from JVM unit tests (e.g.
        // android.util.Log.e, used in RoutineCreateViewModel's search-error path) throws
        // "Method ... not mocked" instead of running — this makes such stubs return a
        // harmless default (0/null/false) instead, matching the standard Android testing
        // guidance for plain unit tests that don't use Robolectric.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        // Room's exported schema JSON (see the `ksp { room.schemaLocation }` block below) is
        // written to disk, but is NOT on the androidTest APK's classpath/assets by default --
        // androidx.room.testing.MigrationTestHelper reads a prior version's schema from its
        // assets at runtime (see Migration4To5Test/Migration5To6Test), so without this it fails
        // on-device with "Cannot find the schema file in the assets folder", not a compile error,
        // meaning this gap was invisible until these tests actually ran on a real device/emulator.
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    // Exports Room's generated schema JSON on every build, so schema changes are
    // reviewable in version control and Room can validate migrations against a
    // concrete schema history instead of only the current @Database definition.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    // Per-app language support (see locale/AppLocale.kt) -- AppCompatDelegate.setApplicationLocales/
    // getApplicationLocales, MainActivity extending AppCompatActivity, and the
    // AppLocalesMetadataHolderService manifest declaration below it all come from this artifact.
    implementation(libs.androidx.appcompat)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.google.play.billing)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Active-window scheduling milestone -- see libs.versions.toml's "work"/"hiltWork"
    // entries and scheduling/RoutineActiveWindowWorker.kt. androidx.hilt:hilt-compiler is
    // REQUIRED here (distinct from the com.google.dagger:hilt-android-compiler already
    // applied above) -- see libs.versions.toml's androidx-hilt-compiler entry for the real
    // on-device bug its absence caused.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    // Home-screen widget milestone -- see widget/BlickRoutineWidget.kt.
    implementation(libs.androidx.glance.appwidget)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    // See libs.versions.toml's robolectric entry for why this is pinned to 4.16.1 and why
    // RoutineNotificationBuilderTest targets @Config(sdk = [34]) rather than this project's
    // actual compileSdk/targetSdk of 36.
    testImplementation(libs.robolectric)
    // TestListenableWorkerBuilder/WorkManagerTestInitHelper (RoutineActiveWindowWorkerTest,
    // WorkManagerRoutineSchedulerTest) -- deterministic worker/scheduler tests, no real
    // device or foreground-service execution needed.
    testImplementation(libs.androidx.work.testing)
    // runGlanceAppWidgetUnitTest/hasText()/onNode() against BlickRoutineWidget's own real
    // composables (BlickRoutineWidgetRenderTest) -- see libs.versions.toml's own comment on
    // these two entries for why both are declared explicitly.
    testImplementation(libs.androidx.glance.testing)
    testImplementation(libs.androidx.glance.appwidget.testing)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // RoutineListScreenTest (Add-routine FAB regression coverage) -- exercises the plain,
    // Hilt-free RoutineListContent composable directly, so no Hilt test application/runner
    // changes are needed alongside this.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    // Declared explicitly alongside ui-test-junit4 (not left as only a transitive of it) --
    // see this artifact's own comment in libs.versions.toml for why: the same AGP-9
    // variant-aware-resolution gap that previously hit okhttp/material-icons-core/the
    // Retrofit converter in this project also kept assertExists()/assertDoesNotExist() off
    // the real compile classpath when only ui-test-junit4 was declared.
    androidTestImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
