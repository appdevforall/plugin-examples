plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "IconsRepository-Plugin"
}

android {
    namespace = "dev.omar.plugin.iconsrepo"
    compileSdk = 35
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "dev.omar.plugin.iconsrepo"
        minSdk = 26
        targetSdk = 35
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

    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        viewBinding = true
    }
    


}

dependencies {
    compileOnly(files("../libs/plugin-api.jar"))
    
    implementation("com.github.megatronking:svg-generator:1.3.2")
    implementation("com.github.megatronking:svg-support:1.3.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("androidx.fragment:fragment:1.8.8")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.caverock:androidsvg:1.4")
}
tasks.wrapper {
    gradleVersion = "8.14.3"
    distributionType = Wrapper.DistributionType.BIN
}

tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach {
    enabled = false
}
configurations.all {
    resolutionStrategy {
        force("com.google.guava:guava:31.1-android")
    }
}