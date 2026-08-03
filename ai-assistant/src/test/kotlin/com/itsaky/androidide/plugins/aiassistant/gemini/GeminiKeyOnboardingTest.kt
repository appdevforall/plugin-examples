package com.itsaky.androidide.plugins.aiassistant.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiKeyOnboardingTest {

    @Test
    fun givenTheKeySourceUrl_whenInspected_thenItPointsAtAiStudioNotTheCloudConsole() {
        // AI Studio provisions the Cloud project itself; pinned so nobody "fixes" it back.
        assertEquals("https://aistudio.google.com/apikey", GeminiKeyOnboarding.AI_STUDIO_URL)
    }

    @Test
    fun givenTheKeySourceUrl_whenInspected_thenItIsHttps() {
        // A key is typed into whatever this opens; it must not be reachable over cleartext.
        assertTrue(GeminiKeyOnboarding.AI_STUDIO_URL.startsWith("https://"))
    }
}
