plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "ai-core"
}

android {
    namespace = "com.itsaky.androidide.plugins.aicore"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.itsaky.androidide.plugins.aicore"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // Disable minification to prevent JNI method stripping (IntVar.getValue)
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    compileOnly(files("../libs/plugin-api.jar"))

    implementation(files("libs/v8/llama-v8-release.aar"))
    implementation(files("libs/llama-api.jar"))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Google Generative AI SDK for Gemini API
    implementation("com.google.genai:google-genai:1.16.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
}

// AAR metadata checks are disabled by convention for these application-as-library
// plugins. The prebuilt llama .aar carries a "core library desugaring required"
// flag, but this module's minSdk (33) makes desugaring unnecessary at runtime,
// so the check is a false positive here.
tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach { enabled = false }
