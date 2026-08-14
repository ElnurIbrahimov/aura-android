import java.time.Duration

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.aura.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            // Never shrink the library on its own: the app's R8 run does
            // whole-program shrinking with full visibility of what the app
            // actually uses. A standalone library pass only has
            // consumer-rules.pro to go by (which keeps just com.aura.core.**,
            // i.e. BuildConfig) and strips the com.aura.* classes the app
            // needs — under AGP 9 the app consumes those stripped classes.
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // The library artifact itself needs only the line above; these
            // matter for the androidTest APK, which packages the module's full
            // runtime classpath. javax.mail (android-mail + android-activation)
            // and jakarta.inject each ship META-INF/NOTICE.md and LICENSE.md,
            // so mergeDebugAndroidTestJavaResource failed with "3 files found
            // with path 'META-INF/NOTICE.md'" and :aura-core:connectedAndroid-
            // Test could not build at all. :app already carried these excludes;
            // :aura-core never did, which is why the migration-chain tests —
            // the only coverage the Room upgrade path has — were unrunnable
            // rather than merely unrun.
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/{NOTICE,LICENSE}*"
        }
    }
    sourceSets {
        // Adds exported Room schemas to the test assets for migration tests.
        getByName("androidTest").assets.srcDir("schemas")
        getByName("test").assets.srcDir("schemas")
    }
    testOptions {
        // Robolectric tests verify packaged configuration assets (for example,
        // moa_presets.json) rather than silently exercising fallback code.
        unitTests.isIncludeAndroidResources = true
        // android.util.Log calls in production code (e.g. ConversationStore
        // runCatching logging) return default values (0, null, false) in
        // JVM unit tests instead of throwing RuntimeException.
        unitTests.isReturnDefaultValues = true
    }
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.datastore.preferences)

    api(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Workers live in this module; this compiler generates the bindings
    // consumed by HiltWorkerFactory at runtime.
    ksp(libs.androidx.hilt.compiler)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.biometric)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.documentfile)
    implementation(libs.mail.android)
    implementation(libs.mail.android.activation)

    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.room.testing)
}

// A hang has to become a failure, and then say where.
//
// On 2026-08-12 this task printed nothing for 86 minutes and CI killed the whole
// job at its 90-minute ceiling: nothing reported, nothing uploaded, the run
// reading as cancelled rather than broken. The timeout below fixed that half —
// on 2026-08-13 it failed the task at 40 minutes and the `if: failure()` step
// uploaded partial results showing 261 classes had run before the stall.
//
// That evidence also disproved the original diagnosis. The Robolectric
// android-all download was blamed and is now cached anyway (see
// .github/workflows/ci.yml), but it was never the cause: the runs that PASSED
// paid the same cold download and finished the whole job in 15 minutes. The real
// pattern is bimodal — about 10 minutes, or forever — which is a deadlock, not a
// slow transfer.
//
// 40 minutes, not 25, so a genuinely cold run is not cut short. The suite takes
// ~4 locally.
//
// `testLogging` names each test as it starts, CI only. The partial results could
// only name the last class to *finish*; the hanging one is by definition the one
// that never got written, so the log has to record what started. Three thousand
// STARTED lines is what a stalled run needs and what a local run does not.
// `providers.environmentVariable` rather than `System.getenv` so the
// configuration cache tracks it.
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
