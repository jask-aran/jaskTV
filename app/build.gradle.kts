import java.util.Properties
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.opentv"
    compileSdk = 35

    defaultConfig {
        // Its own id so it installs next to upstream OpenTV instead of replacing it.
        applicationId = "app.jasktv"
        minSdk = 23
        targetSdk = 35
        // Set by the release workflow from the tag (v1.2.3 → 1.2.3 / 10203); local builds stay 1 / 0.1.0.
        versionCode = providers.gradleProperty("jasktv.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("jasktv.versionName").orNull ?: "0.1.0"
        // Account server and Google sign-in, from the ignored local.properties (or -P/environment in CI).
        // Either blank: sign-in is hidden and the app works without an account.
        fun setting(key: String) = (providers.gradleProperty(key).orNull ?: localProperties.getProperty(key) ?: "").replace("\"", "")
        buildConfigField("String", "ACCOUNT_SERVER", "\"${setting("jasktv.accountServer")}\"")
        // Where in-app updates come from (GitHub Releases of this public repository).
        buildConfigField("String", "UPDATE_REPO", "\"jask-aran/jaskTV\"")
        // The account server's host, so its /pair links (the TV's QR code) open in the app.
        manifestPlaceholders["accountHost"] = runCatching { URI(setting("jasktv.accountServer")).host }.getOrNull() ?: "localhost"
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${setting("jasktv.googleWebClientId")}\"")
    }

    signingConfigs {
        create("upload") {
            // Populated from environment variables in CI; the release workflow requires them.
            val storePath = providers.environmentVariable("KEYSTORE_PATH").orNull
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            // Local-only fallback for testing release optimizations, not for distribution.
            // CI must supply the existing production key; see docs/RELEASING.md.
            signingConfig =
                if (providers.environmentVariable("KEYSTORE_PATH").orNull != null) {
                    signingConfigs.getByName("upload")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            // `-PjaskPerf`: same package/signature/data, but non-debuggable so ART optimises it.
            // For on-device scroll measurements only; debuggable builds overstate Compose jank.
            if (providers.gradleProperty("jaskPerf").isPresent) isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

}


dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    // The platform-neutral player contract, shared with the desktop target.
    implementation(project(":player-core"))
    // Data model, database, catalogue and settings shared with the desktop app.
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.okhttp)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.zxing.core)
    implementation(libs.gms.code.scanner)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    // Storage Access Framework helpers - write recordings to a plugged-in USB / external
    // drive via a user-granted tree URI, with no storage permission.
    implementation(libs.androidx.documentfile)
    implementation(libs.coil.compose)

    // SMB/CIFS client for recording to a NAS (Synology etc.) and playing those recordings
    // back in-app over the network. Pure-Java SMB2/3, no native bits.
    implementation("com.hierynomus:smbj:0.12.2")

}
