package com.itsaky.androidide.plugins.aiagentmcp.errors

import com.itsaky.androidide.plugins.aiagentmcp.client.McpProtocolException
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [McpErrorFormatter]. The classification is what decides whether a user re-types a
 * token or goes looking for a network problem that does not exist.
 */
class McpErrorFormatterTest {

    @Test
    fun givenAnUnauthorizedStatus_whenClassified_thenItPointsAtTheToken() {
        val failure = McpErrorFormatter.classify(McpHttpException(401, "{}"))

        assertEquals(McpFailure.TokenRefused, failure)
    }

    @Test
    fun givenAMethodNotAllowed_whenClassified_thenItPointsAtTheTransport() {
        // The commonest way a non-MCP URL fails, and the least obvious to a user.
        assertEquals(McpFailure.WrongTransport, McpErrorFormatter.classify(McpHttpException(405, "")))
    }

    @Test
    fun givenAServerSideStatus_whenClassified_thenTheStatusIsCarried() {
        val failure = McpErrorFormatter.classify(McpHttpException(503, "upstream down"))

        assertEquals(McpFailure.ServerError(503), failure)
    }

    @Test
    fun givenAnUnhandledStatus_whenClassified_thenItStaysGenericRatherThanGuessing() {
        assertEquals(McpFailure.Http(418), McpErrorFormatter.classify(McpHttpException(418, "")))
    }

    @Test
    fun givenAJsonRpcError_whenClassified_thenTheServersOwnWordsAreKept() {
        val failure = McpErrorFormatter.classify(McpProtocolException(-32602, "Unknown tool"))

        assertEquals(McpFailure.Rejected("Unknown tool"), failure)
    }

    @Test
    fun givenATimeout_whenClassified_thenItIsNotReportedAsCancellation() {
        // SocketTimeoutException extends InterruptedIOException, which is the cancellation branch.
        assertEquals(McpFailure.TimedOut, McpErrorFormatter.classify(SocketTimeoutException("read")))
    }

    @Test
    fun givenADnsFailure_whenClassified_thenItPointsAtTheUrl() {
        assertEquals(McpFailure.UnknownHost, McpErrorFormatter.classify(UnknownHostException("nope")))
    }

    @Test
    fun givenATlsFailure_whenClassified_thenItPointsAtTheScheme() {
        assertEquals(McpFailure.TlsFailed, McpErrorFormatter.classify(SSLHandshakeException("bad cert")))
    }

    @Test
    fun givenSomethingElse_whenClassified_thenTheReasonSurvivesForTheLog() {
        val failure = McpErrorFormatter.classify(IOException("socket closed"))

        assertTrue(failure is McpFailure.Failed)
        assertEquals("socket closed", (failure as McpFailure.Failed).reason)
    }

    @Test
    fun givenNoPluginContext_whenFormatted_thenTheUserStillGetsASentence() {
        val message = McpErrorFormatter.format(null, "Docs", IOException("socket closed"))

        assertTrue(message.contains("Docs"))
        assertTrue(message.contains("socket closed"))
    }
}
