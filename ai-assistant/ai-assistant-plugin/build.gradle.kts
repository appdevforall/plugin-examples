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

    // Use app's androidx libraries to avoid ClassLoader conflicts
    compileOnly("androidx.appcompat:appcompat:1.6.1")
    compileOnly("androidx.fragment:fragment-ktx:1.6.2")
    compileOnly("com.google.android.material:material:1.10.0")
    compileOnly("androidx.recyclerview:recyclerview:1.3.2")

    // Markdown rendering - plugin-specific library
    implementation("io.noties.markwon:core:4.6.2")

    // Plugin dependencies are loaded at runtime by the plugin manager
    // No explicit compile-time dependency on ai-core-plugin needed
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
}
