plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "code-suggestions-plugin"
}

android {
    namespace = "com.itsaky.androidide.plugins.codesuggestions"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.itsaky.androidide.plugins.codesuggestions"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(files("../libs/plugin-api.jar"))

    // Coroutines for async LLM calls and debouncing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Material Design
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
}

tasks.matching {
    it.name.contains("checkDebugAarMetadata") || it.name.contains("checkReleaseAarMetadata")
}.configureEach { enabled = false }
