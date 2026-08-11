plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "ai-backend-gemini"
}

android {
    namespace = "com.itsaky.androidide.plugins.aigemini"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.itsaky.androidide.plugins.aigemini"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
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

    // 'implementation' (not 'compileOnly') for the androidx/Material libraries: AAPT2 needs them
    // at compile time to process the settings pane's layout, as in every CoGo plugin with XML.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("com.google.android.material:material:1.10.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation(files("../libs/plugin-api.jar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.json:json:20231013")
}

/**
 * Fails the build when the crypto constants of ai-assistant's and this plugin's duplicated
 * SecureApiKeyStore drift, which would otherwise surface only on a device as "backend not
 * available". On preBuild, not `test`: CI runs assemblePlugin and never the unit tests.
 */
val verifySecureApiKeyStoreParity by tasks.registering {
    group = "verification"
    description = "Fails if ai-backend-gemini and ai-assistant's SecureApiKeyStore crypto constants differ."

    val ours = file("src/main/kotlin/com/itsaky/androidide/plugins/aigemini/SecureApiKeyStore.kt")
    val theirs = file(
        "../ai-assistant/src/main/kotlin/com/itsaky/androidide/plugins/aiassistant/security/SecureApiKeyStore.kt"
    )
    // inputs.files (not inputs.file) so a missing sibling is an absent input, not a failure.
    inputs.files(ours, theirs)

    doLast {
        if (!theirs.exists()) {
            logger.warn(
                "SecureApiKeyStore parity check skipped: ${theirs.path} not found. " +
                    "Build ai-backend-gemini from the plugin-examples repo to verify it."
            )
            return@doLast
        }

        val required = listOf("KEYSTORE", "ALIAS", "TRANSFORM", "IV_LEN", "TAG_BITS", "ENC_PREFIX")
        val constant = Regex("""const\s+val\s+(\w+)\s*=\s*(.+)""")

        fun constantsOf(source: File): Map<String, String> = source.readLines()
            .mapNotNull { constant.find(it) }
            .associate { it.groupValues[1] to it.groupValues[2].substringBefore("//").trim() }
            .filterKeys { it in required }

        val ourConstants = constantsOf(ours)
        val theirConstants = constantsOf(theirs)

        val missing = required.filter { it !in ourConstants || it !in theirConstants }
        val drifted = required.filter {
            it in ourConstants && it in theirConstants && ourConstants[it] != theirConstants[it]
        }

        if (missing.isNotEmpty() || drifted.isNotEmpty()) {
            val details = buildString {
                if (missing.isNotEmpty()) {
                    appendLine("  missing from one or both copies: ${missing.joinToString()}")
                }
                drifted.forEach {
                    appendLine("  $it: ai-backend-gemini=${ourConstants[it]} ai-assistant=${theirConstants[it]}")
                }
            }
            throw GradleException(
                "SecureApiKeyStore crypto constants differ between ai-backend-gemini and ai-assistant.\n" +
                    details +
                    "A key encrypted by one plugin would not decrypt in the other. " +
                    "Keep both copies in sync:\n" +
                    "  ${ours.path}\n  ${theirs.path}"
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifySecureApiKeyStoreParity)
}

// AAR metadata checks are disabled by convention for these application-as-library plugins.
tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach { enabled = false }
