package com.itsaky.androidide.plugins.aiagentmcp.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [JsonRpc], where a mis-shaped envelope becomes "the server returned nothing". */
class JsonRpcTest {

    @Test
    fun givenAMethodAndParams_whenARequestIsBuilt_thenItCarriesTheEnvelopeTheSpecRequires() {
        val envelope = JsonRpc.request("7", "tools/list")

        assertEquals("2.0", envelope.getString("jsonrpc"))
        assertEquals("7", envelope.getString("id"))
        assertEquals("tools/list", envelope.getString("method"))
    }

    @Test
    fun givenANotification_whenBuilt_thenItCarriesNoIdSoNoReplyIsExpected() {
        val envelope = JsonRpc.notification("notifications/initialized")

        assertFalse("a notification with an id would leave the caller waiting", envelope.has("id"))
    }

    @Test
    fun givenASuccessReply_whenParsed_thenTheResultIsReturned() {
        val reply = JsonRpc.parseReply("""{"jsonrpc":"2.0","id":"7","result":{"tools":[]}}""")

        assertEquals("7", reply?.id)
        assertFalse(reply!!.isError)
        assertTrue(reply.result!!.has("tools"))
    }

    @Test
    fun givenAnErrorReply_whenParsed_thenTheCodeAndMessageSurvive() {
        val reply = JsonRpc.parseReply(
            """{"jsonrpc":"2.0","id":"7","error":{"code":-32601,"message":"Unknown method"}}"""
        )

        assertTrue(reply!!.isError)
        assertEquals(JsonRpc.METHOD_NOT_FOUND, reply.errorCode)
        assertEquals("Unknown method", reply.errorMessage)
    }

    @Test
    fun givenAnErrorWithNoMessage_whenParsed_thenSomethingSayableIsStillReported() {
        val reply = JsonRpc.parseReply("""{"jsonrpc":"2.0","id":"7","error":{"code":-32000}}""")

        assertTrue(reply!!.isError)
        assertTrue(reply.errorMessage!!.isNotBlank())
    }

    @Test
    fun givenABatchReply_whenParsed_thenTheAnsweringMemberIsUsed() {
        // Some servers answer a single request with a one-element batch.
        val reply = JsonRpc.parseReply(
            """[{"jsonrpc":"2.0","method":"notifications/message"},""" +
                """{"jsonrpc":"2.0","id":"7","result":{"ok":true}}]"""
        )

        assertEquals("7", reply?.id)
        assertTrue(reply!!.result!!.getBoolean("ok"))
    }

    @Test
    fun givenAServerInitiatedRequest_whenParsed_thenItIsNotMistakenForAReply() {
        // This client answers nothing, so a request arriving on the stream must be ignored, not
        // reported as an empty result.
        val reply = JsonRpc.parseReply("""{"jsonrpc":"2.0","id":"1","method":"ping"}""")

        assertNull(reply)
    }
}
