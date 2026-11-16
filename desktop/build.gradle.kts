import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileOutputStream

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
    // Database, catalogue and settings shared with the Android app.
    implementation(project(":shared"))
    implementation(libs.coil3.compose)
    implementation(libs.coil3.network.okhttp)
    implementation(libs.sqlite.bundled)
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

/**
 * Engine-level checks with no UI: load, render, pause, seek, volume, tracks.
 *
 *     gradlew :desktop:smoke -PsmokeUrl=C:/path/sample.mp4
 *
 * Exits non-zero if any check fails, so it can gate a build script.
 */
tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Runs the libmpv engine against -PsmokeUrl without the Compose UI."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.opentv.desktop.mpv.MpvSmokeKt")
    systemProperty("smoke.url", providers.gradleProperty("smokeUrl").getOrElse(""))
    // Gradle and Windows shells both like to swallow a child process's console output, which is
    // exactly what a smoke run is for. Write the transcript to a file as well.
    val transcript = layout.buildDirectory.file("smoke.log").get().asFile
    doFirst {
        transcript.parentFile.mkdirs()
        transcript.writeText("")
    }
    standardOutput = FileOutputStream(transcript, true)
    errorOutput = FileOutputStream(transcript, true)
}

