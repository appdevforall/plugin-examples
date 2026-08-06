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
    compileSdk = 36

    defaultConfig {
        applicationId = "com.itsaky.androidide.plugins.aicore"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
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

    testImplementation(files("../libs/plugin-api.jar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
}

/**
 * Fails the build when the crypto constants of ai-assistant's and ai-core's duplicated
 * SecureApiKeyStore drift, which would otherwise surface only on a device as "backend not
 * available". On preBuild, not `test`: CI runs assemblePlugin and never the unit tests.
 */
val verifySecureApiKeyStoreParity by tasks.registering {
    group = "verification"
    description = "Fails if ai-core and ai-assistant's SecureApiKeyStore crypto constants differ."

    val ours = file("src/main/kotlin/com/itsaky/androidide/plugins/aicore/SecureApiKeyStore.kt")
    val theirs = file(
        "../ai-assistant/src/main/kotlin/com/itsaky/androidide/plugins/aiassistant/security/SecureApiKeyStore.kt"
    )
    // inputs.files (not inputs.file) so a missing sibling is an absent input, not a failure.
    inputs.files(ours, theirs)

    doLast {
        if (!theirs.exists()) {
            logger.warn(
                "SecureApiKeyStore parity check skipped: ${theirs.path} not found. " +
                    "Build ai-core from the plugin-examples repo to verify it."
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
                    appendLine("  $it: ai-core=${ourConstants[it]} ai-assistant=${theirConstants[it]}")
                }
            }
            throw GradleException(
                "SecureApiKeyStore crypto constants differ between ai-core and ai-assistant.\n" +
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

// AAR metadata checks are disabled by convention for these application-as-library
// plugins. The prebuilt llama .aar carries a "core library desugaring required"
// flag, but this module's minSdk (33) makes desugaring unnecessary at runtime,
// so the check is a false positive here.
tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach { enabled = false }
