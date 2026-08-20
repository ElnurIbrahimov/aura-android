plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Global resolution: prevent KSP / Hilt pulling duplicate `org.jetbrains.annotations` classes
// from multiple versions (com.intellij:annotations:12 vs org.jetbrains:annotations:23).
// Force the newer one everywhere and exclude the transitive IntelliJ annotations jar.
allprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains" && requested.name == "annotations") {
                useVersion("23.0.0")
                because("Force single annotations version to avoid KSP/Hilt duplicate-class errors.")
            }
        }
        // Exclude the older com.intellij:annotations jar that Hilt's KSP processor pulls in
        // transitively. The newer org.jetbrains:annotations covers everything we need.
        exclude(group = "com.intellij", module = "annotations")
    }
}

// ── Coverage ────────────────────────────────────────────────────────────────
//
// There were 3,445 unit tests over ~125k lines and no way to know what fraction of it
// they execute. That number is the point: it is the one measurement in this repo nobody
// has ever seen, and it is likely to be uncomfortable.
//
//     ./gradlew jacocoTestReport
//     open <module>/build/reports/jacoco/jacocoTestReport/html/index.html
//
// Deliberately not wired into CI or made a gate. A coverage threshold that fails a build
// converts "write a test that proves something" into "write a test that touches a line",
// which is a worse suite than none of the tests being counted at all. Measure, look, act
// on what it shows — do not let a number start authoring the tests.
subprojects {
    plugins.withId("com.android.library") { applyJacoco(project) }
    plugins.withId("com.android.application") { applyJacoco(project) }
}

fun applyJacoco(project: Project) {
    project.plugins.apply("jacoco")
    project.extensions.configure<JacocoPluginExtension>("jacoco") {
        toolVersion = "0.8.12"
    }
    project.tasks.register<JacocoReport>("jacocoTestReport") {
        group = "verification"
        description = "Coverage for the debug unit tests of ${project.name}."
        dependsOn("test${"debug".replaceFirstChar { it.uppercase() }}UnitTest")
        reports {
            html.required.set(true)
            xml.required.set(true)
            csv.required.set(false)
        }
        // Generated code is not code anyone wrote, and counting it moves the number
        // without telling you anything about the suite.
        val filters = listOf(
            "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
            "**/*Test*.*", "**/*_Factory*.*", "**/*_MembersInjector*.*",
            "**/*_HiltModules*.*", "**/Hilt_*.*", "**/*_Impl*.*",
            "**/*$*Lambda$*.*", "**/*ComposableSingletons*.*",
            "**/*_GeneratedInjector*.*", "**/*Dagger*.*",
        )
        classDirectories.setFrom(
            project.files(
                project.fileTree("${project.layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
                    exclude(filters)
                },
            ),
        )
        sourceDirectories.setFrom(project.files("${project.projectDir}/src/main/kotlin"))
        executionData.setFrom(
            project.fileTree(project.layout.buildDirectory) {
                include("**/testDebugUnitTest.exec", "**/jacoco/testDebugUnitTest.exec")
            },
        )
    }
}
