package com.itsaky.androidide.plugins.aiassistant.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.itsaky.androidide.plugins.aiassistant.gemini.CatalogResult
import com.itsaky.androidide.plugins.aiassistant.gemini.GeminiCatalogGateway
import com.itsaky.androidide.plugins.aiassistant.gemini.KeyVerification
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Tests for the pre-save key check.
 *
 * The gateway is faked, so no ai-core, no network and no device are involved — which is the point
 * of having extracted it out of the ViewModel's raw reflection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiSettingsViewModelVerifyTest {

    /** The ViewModel touches LiveData in its init block. */
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val candidateKey = "AIzaSyD-EXAMPLE_key_value_1234567890abc"

    /** No PluginContext in a JVM test; verification never needs prefs, only the gateway. */
    private fun viewModel(gateway: GeminiCatalogGateway) = AiSettingsViewModel(
        getContext = { null },
        ioDispatcher = UnconfinedTestDispatcher(),
        catalogGateway = gateway
    )

    @Test
    fun givenAWorkingKey_whenVerified_thenItIsVerifiedWithItsModelCount() = runTest {
        val gateway = FakeGateway(CatalogResult.Success(listOf("gemini-2.5-flash", "gemini-2.5-pro")))

        val verdict = viewModel(gateway).verifyGeminiKey(candidateKey)

        assertEquals(KeyVerification.Verified(2), verdict)
    }

    @Test
    fun givenATypedKey_whenVerified_thenThatKeyIsCheckedAndNotTheSavedOne() = runTest {
        // Checking the *saved* key would clear a candidate on a different credential.
        val gateway = FakeGateway(CatalogResult.Success(listOf("gemini-2.5-flash")))

        viewModel(gateway).verifyGeminiKey("  $candidateKey  ")

        assertEquals(listOf(candidateKey), gateway.candidateKeys)
        assertEquals(0, gateway.savedKeyCalls)
    }

    @Test
    fun givenAKeyGoogleRefuses_whenVerified_thenItIsRejected() = runTest {
        val gateway = FakeGateway(
            CatalogResult.Failed(IOException("ListModels HTTP 400: {\"error\":{}}"))
        )

        val verdict = viewModel(gateway).verifyGeminiKey(candidateKey)

        assertEquals(KeyVerification.Rejected, verdict)
    }

    @Test
    fun givenNoNetwork_whenVerified_thenReportsUnreachableSoTheKeyIsNotCondemned() = runTest {
        val gateway = FakeGateway(CatalogResult.Failed(IOException("Unable to resolve host")))

        val verdict = viewModel(gateway).verifyGeminiKey(candidateKey)

        assertEquals(KeyVerification.Unreachable, verdict)
    }

    @Test
    fun givenAMissingAiCore_whenVerified_thenReportsUnknown() = runTest {
        val verdict = viewModel(FakeGateway(CatalogResult.NoBackend)).verifyGeminiKey(candidateKey)

        assertEquals(KeyVerification.Unknown, verdict)
    }

    @Test
    fun givenAGatewayThatThrows_whenVerified_thenItCannotBeMistakenForAPass() = runTest {
        val gateway = FakeGateway(error = IllegalStateException("classloader trouble"))

        val verdict = viewModel(gateway).verifyGeminiKey(candidateKey)

        assertEquals(KeyVerification.Unknown, verdict)
        assertTrue(!verdict.isConfirmedValid)
    }

    @Test
    fun givenABlankKey_whenVerified_thenItIsRejectedWithoutANetworkRoundTrip() = runTest {
        val gateway = FakeGateway(CatalogResult.Success(listOf("gemini-2.5-flash")))

        val verdict = viewModel(gateway).verifyGeminiKey("   ")

        assertEquals(KeyVerification.Rejected, verdict)
        assertTrue(gateway.candidateKeys.isEmpty())
    }

    /** Records what it was asked, so tests can assert *which* key got checked. */
    private class FakeGateway(
        private val response: CatalogResult? = null,
        private val error: Throwable? = null
    ) : GeminiCatalogGateway {

        val candidateKeys = mutableListOf<String>()
        var savedKeyCalls = 0

        override fun listModelsForSavedKey(): CatalogResult {
            savedKeyCalls++
            return response ?: CatalogResult.NoBackend
        }

        override fun listModels(apiKey: String): CatalogResult {
            candidateKeys += apiKey
            error?.let { throw it }
            return response ?: CatalogResult.NoBackend
        }
    }
}
