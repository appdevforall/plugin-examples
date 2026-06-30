import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "ai-literacy-course"
}

android {
    namespace = "org.appdevforall.ailiteracycourse"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.appdevforall.ailiteracycourse"
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

    androidResources {
        // The course bundle is an already-compressed ZIP (its mp4/pdf/nested-zip
        // payloads are themselves compressed). Storing it uncompressed keeps the
        // .cgp from paying a pointless second deflate pass over ~110 MB.
        noCompress += listOf("zip")
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
    // plugin-api is the IDE-side contract: compile against it, the IDE provides it.
    compileOnly(files("../libs/plugin-api.jar"))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WebViewAssetLoader: serves the extracted course over a virtual https
    // origin so video byte-ranges and the NeuroPocket service worker work.
    implementation("androidx.webkit:webkit:1.11.0")
}

tasks.wrapper {
    gradleVersion = "8.14.3"
    distributionType = Wrapper.DistributionType.BIN
}

// Disable AAR metadata checks that fail under the plugin-builder pipeline
// (same workaround the other examples use).
tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach {
    enabled = false
}

// --- Build-time course download (mirrors ndk-installer-plugin) ---------------
//
// The 110 MB course ZIP is NOT committed to git. This task pulls it from LAIA's
// Google Drive and verifies a self-computed MD5 we pin below. scripts/update-libs.sh
// runs `downloadAssets` automatically before assemblePlugin for any plugin that
// declares it.

fun openHttpConnection(url: String, readTimeoutMs: Int): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
    connection.connectTimeout = 15000
    connection.readTimeout = readTimeoutMs

    val code = connection.responseCode
    if (code != 200) {
        throw GradleException("Failed to download $url (HTTP $code)")
    }
    return connection
}

fun httpDownloadToFile(url: String, target: File): Long {
    val connection = openHttpConnection(url, readTimeoutMs = 300_000)
    val expectedLength = connection.contentLengthLong
    var receivedLength = 0L

    connection.inputStream.use { input ->
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                receivedLength += read
            }
        }
    }

    // Drive serves this file chunked (no Content-Length), so expectedLength is
    // typically -1 here; the MD5 check below is the real integrity gate.
    if (expectedLength >= 0 && receivedLength != expectedLength) {
        throw GradleException(
            "Truncated download from $url: expected $expectedLength bytes, got $receivedLength"
        )
    }
    return receivedLength
}

fun md5Of(file: File): String {
    val md = MessageDigest.getInstance("MD5")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            md.update(buffer, 0, read)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val downloadAssets by tasks.registering {
    val assetsDir = project.file("src/main/assets")
    val archiveFile = assetsDir.resolve("ai-literacy-course.zip")

    outputs.files(archiveFile)

    doLast {
        assetsDir.mkdirs()

        // Google Drive large-file (>100 MB) endpoint. A plain share/uc URL would
        // return an HTML "can't scan for viruses" interstitial instead of bytes;
        // this endpoint + confirm=t serves the file directly.
        val fileId = "1zRTVzgzZwVCtij55JnFTLtPFwsSlZo-t"
        val archiveUrl =
            "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"

        // MD5 computed from the LAIA-provided zip (110 MB -> 119 MB extracted).
        // A mismatch (including Drive returning an HTML/quota page) fails the build.
        val expectedMd5 = "c2785f29c12056bd54b8cc885355ecc9"
        val maxAttempts = 3

        var lastError: String? = null
        for (attempt in 1..maxAttempts) {
            logger.lifecycle("Downloading AI Literacy course bundle (attempt $attempt/$maxAttempts)...")
            try {
                val bytes = httpDownloadToFile(archiveUrl, archiveFile)
                val actualMd5 = md5Of(archiveFile).lowercase()

                logger.info("Downloaded $bytes bytes")
                logger.info("Expected MD5: $expectedMd5")
                logger.info("Actual MD5:   $actualMd5")

                if (expectedMd5 == actualMd5) {
                    return@doLast
                }
                lastError = "MD5 mismatch (expected=$expectedMd5, actual=$actualMd5). " +
                    "Drive may have served an HTML interstitial/quota page, or the bundle changed."
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }

            logger.warn("Attempt $attempt failed: $lastError")
            archiveFile.delete()
            if (attempt < maxAttempts) {
                val backoffMs = 2_000L * attempt
                logger.lifecycle("Retrying in ${backoffMs}ms...")
                Thread.sleep(backoffMs)
            }
        }

        throw GradleException(
            "Failed to download ai-literacy-course.zip after $maxAttempts attempts: $lastError"
        )
    }
}
