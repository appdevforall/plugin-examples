import java.net.URL
import java.security.MessageDigest
import java.net.HttpURLConnection

plugins {
    id("com.android.application") version "8.8.2"
    id("org.jetbrains.kotlin.android") version "2.1.21"
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "ndk-installer"
}

android {
    namespace = "com.codeonthego.ndkinstaller"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.codeonthego.ndkinstaller"
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
        noCompress += listOf("xz")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].assets.srcDir(
        layout.buildDirectory.dir("generated/assets")
    )

}

dependencies {
    compileOnly(project(":plugin-api"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

fun httpDownload(url: String): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
    connection.connectTimeout = 15000
    connection.readTimeout = 30000

    val code = connection.responseCode
    if (code != 200) {
        throw GradleException("Failed to download $url (HTTP $code)")
    }

    return connection.inputStream.use { it.readBytes() }
}

fun md5Of(file: File): String {
    val md = MessageDigest.getInstance("MD5")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            md.update(buffer, 0, read)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val downloadAssets by tasks.registering {
    val assetsDir = project.file("src/main/assets")
    val archiveFile = assetsDir.resolve("ndk-cmake.tar.xz")
    val md5File = assetsDir.resolve("ndk-cmake.tar.xz.md5")

    outputs.files(archiveFile)

    doLast {
        assetsDir.mkdirs()

        val archiveUrl = "https://www.appdevforall.org/dev-assets/release/v8/ndk-cmake.tar.xz"
        val md5Url = "https://www.appdevforall.org/dev-assets/release/v8/ndk-cmake.tar.xz.md5"

        logger.info("Downloading archive....")
        val archiveBytes = httpDownload(archiveUrl)
        archiveFile.writeBytes(archiveBytes)

        logger.info("Downloading MD5…")
        val md5Bytes = httpDownload(md5Url)
        md5File.writeBytes(md5Bytes)

        val expected = md5File.readText().trim()
        val actual = md5Of(archiveFile)

        logger.info("Expected MD5: $expected")
        logger.info("Actual MD5:   $actual")

        md5File.delete()

        if (!expected.equals(actual, ignoreCase = true)) {
            throw GradleException("MD5 checksum mismatch for ndk-cmake.tar.xz")
        }

    }
}







