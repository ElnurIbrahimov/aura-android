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
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
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
