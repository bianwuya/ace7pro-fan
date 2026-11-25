plugins {
    id("com.android.application") version "9.5.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

val apkContract = groovy.json.JsonSlurper().parseText(
    providers.fileContents(layout.projectDirectory.file("project.json")).asText.get()
) as Map<*, *>

android {
    namespace = "dev.eta.acefan"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        applicationId = "dev.eta.acefan"
        // AGP 9 forbids these attributes in the source manifest. Reuse the checked contract (28/35).
        minSdk = apkContract["min_sdk"] as Int
        targetSdk = apkContract["target_sdk"] as Int
        // versionCode and versionName come from AndroidManifest.xml; project.py checks BuildInfo.
    }
    sourceSets.named("main") {
        manifest.srcFile("AndroidManifest.xml")
        java.directories.apply { clear(); add("src") }
        kotlin.directories.apply { clear(); add("frontend/src") }
        res.directories.apply { clear(); add("res") }
        assets.directories.apply { clear(); add("assets") }
    }
    compileOptions {
        // Activity Compose's inline setContent is built for JVM 11. tests/run.sh still uses Java 8.
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
    buildTypes.named("release") {
        // Keep Xposed's reflective entry intact. Only build.sh/CI may apply the existing signer.
        isMinifyEnabled = false
        signingConfig = null
    }
    packaging {
        jniLibs {
            // Extract the AndroidX graphics path library at install time. The existing Linux ARM64
            // zipalign handles 4-byte ZIP alignment, not 16 KiB alignment of in-place native libraries.
            useLegacyPackaging = true
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
        }
    }
}

val miuixVersion = "0.9.5-beta01"
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("top.yukonga.miuix.kmp:miuix-preference:$miuixVersion"))
            .using(module("top.yukonga.miuix.kmp:miuix-preference-android:$miuixVersion"))
            .because("Use the same release's published Android AAR from the verified build graph")
    }
}

dependencies {
    compileOnly(files("build-tools/lib/xposed-api-82.jar"))
    implementation("top.yukonga.miuix.kmp:miuix-ui:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-preference:$miuixVersion")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Align the Android artifacts used by Miuix's Compose Multiplatform graph and Activity Compose
    // to the same compatible dependency set as the verified Linux ARM64 cache. No local stubs.
    implementation(platform("androidx.compose:compose-bom-alpha:2026.08.01"))
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigationevent:navigationevent-compose:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.13.0")
    constraints {
        implementation("androidx.core:core:1.19.1")
        implementation("androidx.startup:startup-runtime:1.2.0")
        implementation("androidx.tracing:tracing:2.0.1")
        implementation("androidx.collection:collection-ktx:1.6.0")
    }
}
