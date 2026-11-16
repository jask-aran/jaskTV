import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Everything jaskTV shares between the Android app (phone, tablet, TV) and the desktop app:
 * the data model and Room database, catalogue logic, provider/TMDB clients and settings.
 * Targets are Android and the JVM only, so common code may use JVM libraries (OkHttp, java.io).
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            api(libs.room.runtime)
            api(libs.room.paging)
            api(libs.androidx.paging.common)
            implementation(libs.sqlite.bundled)
            api(libs.okhttp)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
        }
    }
}

android {
    namespace = "app.opentv.shared"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}
