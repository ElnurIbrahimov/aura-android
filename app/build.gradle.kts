import java.time.Duration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Compose compiler configuration.
 *
 * `stabilityConfigurationFile` is the lever with the widest reach in this app. Strong
 * skipping compares an unstable parameter by *identity*, and every `*UiState` here is
 * republished as a fresh object on each change — so a screen taking one held every
 * recomposition it could have skipped. The classes themselves are all-`val` and their
 * collections are replaced through `copy()`, never mutated, so the promise the file makes
 * is one the code already keeps.
 *
 * Metrics and reports are written only when `-PcomposeMetrics` is passed. They are how the
 * stability claims here were checked rather than assumed, and they cost a slower build, so
 * they stay off by default:
 *
 *     ./gradlew :app:assembleDebug -PcomposeMetrics
 *     # then read build/compose-reports/app_release-classes.txt
 */
composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
    if (project.hasProperty("composeMetrics")) {
        metricsDestination = layout.buildDirectory.dir("compose-metrics")
        reportsDestination = layout.buildDirectory.dir("compose-reports")
    }
}

// Release signing configuration is read from local.properties (gitignored),
// falling back to environment variables of the same name. Neither the keystore
// nor its passwords ever live in the repo.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(name: String): String? =
    localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }

val auraKeystorePath = signingValue("AURA_KEYSTORE_PATH")
val hasReleaseSigning = auraKeystorePath != null && file(auraKeystorePath).exists()

android {
    namespace = "com.aura"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aura"
        minSdk = 26
        targetSdk = 35
        versionCode = 81
        versionName = "0.66.0"
        testInstrumentationRunner = "com.aura.testing.HiltTestRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(auraKeystorePath!!)
                storePassword = signingValue("AURA_KEYSTORE_PASSWORD")
                keyAlias = signingValue("AURA_KEY_ALIAS") ?: "aura-upload"
                keyPassword = signingValue("AURA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Real upload keystore when AURA_KEYSTORE_* values are present in
            // local.properties or the environment; otherwise fall back to the
            // debug key so CI and keyless checkouts can still run
            // assembleRelease (R8 coverage), with a warning so nobody ships it.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "WARNING: release build is using the DEBUG signing key. " +
                        "Set AURA_KEYSTORE_PATH/AURA_KEYSTORE_PASSWORD/" +
                        "AURA_KEY_ALIAS/AURA_KEY_PASSWORD in local.properties " +
                        "or the environment to sign with the upload keystore. " +
                        "Do NOT distribute this APK."
                )
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            // Coverage instrumentation for the unit-test JVM. Off in release, and it
            // slows the debug test run slightly, which is the price of ever knowing what
            // fraction of the code the suite executes.
            //
            // Run:  ./gradlew jacocoTestReport
            // Read: <module>/build/reports/jacoco/jacocoTestReport/html/index.html
            enableUnitTestCoverage = true
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/{NOTICE,LICENSE}*"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Interface methods get default implementations directly (was -Xjvm-default=all).
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    implementation(project(":aura-core"))

    constraints {
        // pdfbox-android requests the bc*-jdk15to18 trio at 1.72 (Sept 2022),
        // where CVE-2023-33202 (ASN.1 OID parsing -> OOM) is plausibly
        // reachable through PDF import.
        implementation("org.bouncycastle:bcprov-jdk15to18:1.80")
        implementation("org.bouncycastle:bcpkix-jdk15to18:1.80")
        implementation("org.bouncycastle:bcutil-jdk15to18:1.80")
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    // No androidx.hilt.compiler here: every @HiltWorker lives in :aura-core,
    // which runs it through ksp. Declaring it as `implementation` put the KSP
    // processor and its codegen chain (javapoet, kotlinpoet, auto-common) on
    // the release runtime classpath and fed them to R8.
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.biometric)
    implementation(libs.pdfbox.android)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.media3.common)
    implementation(libs.mail.android)
    implementation(libs.mail.android.activation)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.okhttp.mockwebserver)
    kspAndroidTest(libs.hilt.compiler)
}

// See the matching block in aura-core/build.gradle.kts for the reasoning. Both
// modules run Robolectric and real-socket tests, so both can stall the same way.
val ciBuild = providers.environmentVariable("CI").isPresent

tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(40))
    if (ciBuild) {
        testLogging {
            events("started", "failed")
            showStandardStreams = false
        }
    }
}
