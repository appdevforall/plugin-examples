package com.itsaky.androidide.plugins.aiassistant.gemini

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * Covers every row of the catalog-result → verdict mapping.
 *
 * The rejection rows are load-bearing: [KeyVerification.Rejected] is the only state that blocks a
 * save, so a wrong mapping there discards a working key or lets a broken one through.
 */
class KeyVerificationTest {

    /** Mirrors the message ai-core's `GeminiBackend.fetchAvailableModels` throws. */
    private fun listModelsHttpError(code: Int, body: String = """{"error":{}}""") =
        IOException("ListModels HTTP $code: $body")

    private fun verdictFor(cause: Throwable) =
        CatalogResult.Failed(cause).toKeyVerification()

    @Test
    fun givenANonEmptyCatalog_whenInterpreted_thenTheKeyIsVerifiedWithItsModelCount() {
        val result = CatalogResult.Success(listOf("gemini-2.5-flash", "gemini-2.5-pro"))

        assertEquals(KeyVerification.Verified(2), result.toKeyVerification())
    }

    @Test
    fun givenAnEmptyCatalog_whenInterpreted_thenReportsUnknownRatherThanAPass() {
        // A valid key always lists something, so this says nothing — and must not read as success.
        assertEquals(KeyVerification.Unknown, CatalogResult.Success(emptyList()).toKeyVerification())
    }

    @Test
    fun givenNoBackend_whenInterpreted_thenReportsUnknownAndNeverARejection() {
        assertEquals(KeyVerification.Unknown, CatalogResult.NoBackend.toKeyVerification())
    }

    @Test
    fun givenHttp400ApiKeyInvalid_whenInterpreted_thenTheKeyIsRejected() {
        val cause = listModelsHttpError(400, """{"error":{"status":"INVALID_ARGUMENT"}}""")

        assertEquals(KeyVerification.Rejected, verdictFor(cause))
    }

    @Test
    fun givenHttp401_whenInterpreted_thenTheKeyIsRejected() {
        assertEquals(KeyVerification.Rejected, verdictFor(listModelsHttpError(401)))
    }

    @Test
    fun givenHttp403PermissionDenied_whenInterpreted_thenTheKeyIsRejected() {
        val cause = listModelsHttpError(403, """{"error":{"status":"PERMISSION_DENIED"}}""")

        assertEquals(KeyVerification.Rejected, verdictFor(cause))
    }

    @Test
    fun givenHttp429_whenInterpreted_thenTheKeyCountsAsValidBecauseItStillWorks() {
        // Also pins branch order: 429 sits inside 400..499 and must be matched before it.
        assertEquals(KeyVerification.RateLimited, verdictFor(listModelsHttpError(429)))
    }

    @Test
    fun givenARateLimitedOrVerifiedKey_whenTheSaveRuleIsChecked_thenItIsConfirmed() {
        assertEquals(true, KeyVerification.RateLimited.isConfirmedValid)
        assertEquals(true, KeyVerification.Verified(1).isConfirmedValid)
    }

    @Test
    fun givenAnyOtherVerdict_whenTheSaveRuleIsChecked_thenItIsNotConfirmed() {
        assertEquals(false, KeyVerification.Rejected.isConfirmedValid)
        assertEquals(false, KeyVerification.Unreachable.isConfirmedValid)
        assertEquals(false, KeyVerification.Unknown.isConfirmedValid)
    }

    @Test
    fun givenA5xx_whenInterpreted_thenReportsUnreachableBecauseItIsGooglesFaultNotTheKeys() {
        assertEquals(KeyVerification.Unreachable, verdictFor(listModelsHttpError(500)))
        assertEquals(KeyVerification.Unreachable, verdictFor(listModelsHttpError(503)))
    }

    @Test
    fun givenAnIoExceptionWithNoStatus_whenInterpreted_thenReportsUnreachable() {
        assertEquals(
            KeyVerification.Unreachable,
            verdictFor(UnknownHostException("generativelanguage.googleapis.com"))
        )
        assertEquals(
            KeyVerification.Unreachable,
            verdictFor(SocketTimeoutException("connect timed out"))
        )
    }

    @Test
    fun givenATimeout_whenInterpreted_thenReportsUnknownRatherThanATransportFailure() {
        // A future ai-core will never complete means "couldn't check it", not "offline".
        assertEquals(KeyVerification.Unknown, verdictFor(TimeoutException("gave up")))
    }

    @Test
    fun givenABrokenCrossPluginContract_whenInterpreted_thenReportsUnknownSoNoKeyIsDiscarded() {
        val cause = NoSuchMethodException(
            "com.itsaky.androidide.plugins.aicore.GeminiBackend.listModels(java.lang.String)"
        )

        assertEquals(KeyVerification.Unknown, verdictFor(cause))
    }

    @Test
    fun givenAWrappedCause_whenInterpreted_thenTheStatusIsStillFound() {
        val wrapped = RuntimeException("catalog lookup failed", listModelsHttpError(403))

        assertEquals(KeyVerification.Rejected, verdictFor(wrapped))
    }

    @Test
    fun givenAStatusBuriedDeeperThanTheCap_whenInterpreted_thenTheCauseWalkStillTerminates() {
        // Pins the depth bound so an unbounded walk (or a cycle) can't creep back in.
        var deep: Throwable = listModelsHttpError(403)
        repeat(6) { level -> deep = RuntimeException("wrapper $level", deep) }

        assertEquals(KeyVerification.Unknown, verdictFor(deep))
    }

    @Test
    fun givenHttp404_whenInterpreted_thenTheKeyIsRejected() {
        assertEquals(KeyVerification.Rejected, verdictFor(listModelsHttpError(404)))
    }

    @Test
    fun givenAnyOther4xx_whenInterpreted_thenTheKeyIsRejected() {
        // Every 4xx bar 429 is a client-side refusal, so none of them may reach "Save anyway?".
        assertEquals(KeyVerification.Rejected, verdictFor(listModelsHttpError(402)))
        assertEquals(KeyVerification.Rejected, verdictFor(listModelsHttpError(418)))
        assertEquals(KeyVerification.Rejected, verdictFor(listModelsHttpError(451)))
    }

    @Test
    fun givenAStatusOutsideTheErrorRanges_whenInterpreted_thenReportsUnknown() {
        assertEquals(KeyVerification.Unknown, verdictFor(listModelsHttpError(302)))
    }

    @Test
    fun givenAWrapperMentioningAnotherStatus_whenInterpreted_thenOnlyTheContractMessageCounts() {
        // Only ai-core's `ListModels HTTP <code>` is a status; prose in a wrapper is not.
        val wrapped = RuntimeException("gateway saw HTTP 403", listModelsHttpError(500))

        assertEquals(KeyVerification.Unreachable, verdictFor(wrapped))
    }

    @Test
    fun givenAMessageWithNoContractPrefix_whenInterpreted_thenNoStatusIsInferred() {
        // "HTTP 401" in unrelated prose is not ai-core reporting a status.
        val cause = RuntimeException("proxy rewrote the request; see HTTP 401 in the spec")

        assertEquals(KeyVerification.Unknown, verdictFor(cause))
    }

    @Test
    fun givenAnAiCoreCancellation_whenInterpreted_thenNothingIsConcludedAboutTheKey() {
        // The gateway turns a future ai-core cancelled into Failed rather than letting it escape.
        val cause = java.util.concurrent.CancellationException("ai-core scope closed")

        assertEquals(KeyVerification.Unknown, verdictFor(cause))
    }
}
