package com.itsaky.androidide.plugins.aiagentlocal.packaging

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the committed llama.cpp AAR against a bad regeneration.
 * The plugin filters packaging to one ABI, so the AAR losing that ABI - or a
 * partial native build - would only surface as an UnsatisfiedLinkError on device.
 */
class PrebuiltAarAbiTest {

    // Gradle passes the AAR path; the relative fallback keeps an IDE test runner - which sets no
    // system properties but does run from the module directory - reporting a real assertion.
    private val aar: File = File(System.getProperty("prebuiltAarPath") ?: AAR_RELATIVE_PATH)

    private val expectedAbi: String = System.getProperty("expectedAbi") ?: DEFAULT_ABI

    // One pass over the archive; every test reads this map instead of reopening it.
    private val jniLibs: Map<String, Long> by lazy {
        ZipFile(aar).use { zip ->
            zip.entries()
                .asSequence()
                .filter { !it.isDirectory && it.name.startsWith("jni/") && it.name.endsWith(".so") }
                .associate { it.name to it.size }
        }
    }

    private fun abisPresent(): Set<String> =
        jniLibs.keys.map { it.removePrefix("jni/").substringBefore('/') }.toSet()

    @Test
    fun givenTheCommittedAar_whenLocated_thenItExists() {
        assertTrue("Missing prebuilt AAR at ${aar.absolutePath}", aar.isFile)
    }

    @Test
    fun givenTheCommittedAar_whenInspected_thenItShipsTheExpectedAbi() {
        assertTrue(
            "AAR carries $expectedAbi? Found ${abisPresent()}",
            abisPresent().contains(expectedAbi),
        )
    }

    @Test
    fun givenTheCommittedAar_whenInspected_thenTheExpectedAbiCarriesEveryNativeLibrary() {
        // A partial native build drops one .so and fails at load time, not at build time.
        val actual = jniLibs.keys
            .filter { it.startsWith("jni/$expectedAbi/") }
            .map { it.substringAfterLast('/') }
            .toSet()
        // Subset, not equality: an upstream llama.cpp bump may legitimately add a library.
        assertEquals(
            "Prebuilt AAR is missing native libraries (found $actual)",
            emptySet<String>(),
            REQUIRED_LIBS - actual,
        )
    }

    @Test
    fun givenTheCommittedAar_whenInspected_thenTheJniWrapperIsNotEmpty() {
        val size = jniLibs["jni/$expectedAbi/libllama-android.so"] ?: -1
        assertTrue("libllama-android.so is missing or empty (size=$size)", size > 0)
    }

    private companion object {
        const val DEFAULT_ABI = "arm64-v8a"

        const val AAR_RELATIVE_PATH = "libs/v8/llama-v8-release.aar"

        /** The libraries llama.cpp has to produce for the wrapper to load at all. */
        val REQUIRED_LIBS = setOf(
            "libggml-base.so",
            "libggml-cpu.so",
            "libggml.so",
            "libllama-android.so",
            "libllama.so",
            "libomp.so",
        )
    }
}
