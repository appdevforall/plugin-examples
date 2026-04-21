import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "snippets"
}

android {
    namespace = "com.codeonthego.snippets"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.codeonthego.snippets"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(files("../libs/plugin-api.jar"))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("androidx.fragment:fragment:1.8.8")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    implementation("com.google.code.gson:gson:2.10.1")
}