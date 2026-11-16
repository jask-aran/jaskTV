import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":player-core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    // libmpv is a C library; JNA binds it without generating JNI code.
    implementation(libs.jna)
}

compose.desktop {
    application {
        mainClass = "app.opentv.desktop.MainKt"

        // Produces a runnable app image (`createDistributable`) and installers where the host
        // tooling exists. The MSI needs WiX on Windows; the DMG can only be built on macOS.
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "jaskTV"
            // DMG requires a major version >= 1; the Android app's 0.1.0 is not valid here.
            packageVersion = "1.0.0"

            // Copied into the packaged app's `resources` directory, so a portable build carries
            // its own libmpv instead of depending on an installed mpv.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("libs/mpv"))
        }
    }
}
