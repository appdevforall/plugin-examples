plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "ai-assistant"
}

android {
    namespace = "com.itsaky.androidide.plugins.aiassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.itsaky.androidide.plugins.aiassistant"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "2.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            // Disable minification to avoid lambda obfuscation issues with ClassLoader isolation
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
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
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    compileOnly(project(":plugin-api"))

    // Use 'implementation' (not 'compileOnly') for androidx libraries.
    // This is required for XML layouts: AAPT2 needs these dependencies at compile-time to process
    // resource attributes and resolve xmlns declarations. This is standard across all CoGo plugins
    // with XML layouts (random-xkcd, sketch-to-ui-plugin, Beepy). See investigation in Task 4.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Markdown rendering - plugin-specific library
    implementation("io.noties.markwon:core:4.6.2")

    // JSON serialization for session persistence
    implementation("com.google.code.gson:gson:2.10.1")

    // Plugin dependencies are loaded at runtime by the plugin manager
    // No explicit compile-time dependency on ai-core-plugin needed
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
}
