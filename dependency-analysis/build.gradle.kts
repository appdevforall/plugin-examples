import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.itsaky.androidide.plugins.build")
    jacoco
}

pluginBuilder {
    pluginName = "dependency-analysis"
}

android {
    namespace = "org.appdevforall.dependencyanalysis"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.appdevforall.dependencyanalysis"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // A release .cgp must be signed or the host silently rejects it after a
    // restart (CLAUDE.md "silent-failure gotchas"). We sign the release with the
    // standard Android debug keystore — these are teaching-example plugins, not
    // Play Store artifacts, so the debug key is the intended signing identity.
    // The config is only wired when the keystore actually exists, so a clean
    // checkout that has never run an Android build still configures.
    val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
    val haveDebugKeystore = debugKeystore.exists()
    if (haveDebugKeystore) {
        signingConfigs {
            create("releaseDebugKey") {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (haveDebugKeystore) {
                signingConfig = signingConfigs.getByName("releaseDebugKey")
            }
        }
        debug {
            enableUnitTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The plugin-api jar is the canonical contract for plugins. Available at
    // compile time; the IDE provides it at runtime.
    compileOnly(files("../libs/plugin-api.jar"))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// --- JaCoCo coverage for the non-UI core (domain/ + data/) ---
//
// REVIEW.md requires >=90% line+branch coverage on non-UI code. The domain
// layer is pure Kotlin (advice parsing/classification) and the data layer's
// pure logic (init-script generation, report path resolution) is unit-tested;
// both run on the JVM unit-test classpath. Android UI classes (ui/*, generated
// R / Manifest / BuildConfig) are excluded — they require an instrumented
// device and are out of scope for the line/branch gate.
jacoco {
    toolVersion = "0.8.12"
}

val coverageExclusions = listOf(
    "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
    "**/*Test*.*", "android/**/*.*",
    // UI layer is Android-framework-bound; excluded from the line/branch gate.
    "**/ui/**"
)

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "Generates JaCoCo coverage for the non-UI (domain + data) core."

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val kotlinClasses = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
        exclude(coverageExclusions)
    }
    classDirectories.setFrom(kotlinClasses)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get()) {
            include("**/testDebugUnitTest.exec", "jacoco/testDebugUnitTest.exec")
        }
    )
}

tasks.wrapper {
    gradleVersion = "8.14.3"
    distributionType = Wrapper.DistributionType.BIN
}

// Disable AAR metadata checks that fail under the plugin-builder pipeline
// (the application-as-library packaging trick trips them). Repo convention.
tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach {
    enabled = false
}
