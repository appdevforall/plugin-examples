package com.itsaky.androidide.plugins.aiassistant.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the await-the-user primitive behind the memory warning.
 *
 * This is where the feature's concurrency lives, so it is tested directly: whether a decision is
 * delivered, what happens to a question nobody answers, and that two selections cannot interleave.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserConfirmationTest {

    @Test
    fun givenAQuestion_whenTheUserProceeds_thenAskReturnsTrue() = runTest {
        val confirmation = UserConfirmation<String>()

        val answer = async { confirmation.ask("model.gguf") }
        assertEquals("model.gguf", confirmation.requests.first())
        confirmation.answer(true)

        assertTrue(answer.await())
    }

    @Test
    fun givenAQuestion_whenTheUserCancels_thenAskReturnsFalse() = runTest {
        val confirmation = UserConfirmation<String>()

        val answer = async { confirmation.ask("model.gguf") }
        confirmation.requests.first()
        confirmation.answer(false)

        assertFalse(answer.await())
    }

    @Test
    fun givenAQuestionOnTheWay_whenNobodyHasAnsweredYet_thenAskIsStillWaiting() = runTest {
        val confirmation = UserConfirmation<String>()

        val answer = async { confirmation.ask("model.gguf") }
        runCurrent()

        assertTrue(answer.isActive)
        confirmation.answer(false)
        assertFalse(answer.await())
    }

    @Test
    fun givenAnAnswerWithNothingPending_whenTheNextQuestionIsAsked_thenItIsNotPreAnswered() = runTest {
        // A stray reply from a dismissed dialog must not decide the following selection.
        val confirmation = UserConfirmation<String>()
        confirmation.answer(true)

        val answer = async { confirmation.ask("model.gguf") }
        runCurrent()

        assertTrue(answer.isActive)
        confirmation.answer(false)
        assertFalse(answer.await())
    }

    @Test
    fun givenAnAnsweredQuestion_whenAnsweredAgain_thenTheDuplicateIsIgnored() = runTest {
        // A recreated dialog reporting the same decision twice is harmless.
        val confirmation = UserConfirmation<String>()

        val answer = async { confirmation.ask("model.gguf") }
        confirmation.requests.first()
        confirmation.answer(true)
        confirmation.answer(false)

        assertTrue(answer.await())
    }

    @Test
    fun givenAQuestionAwaitingAnAnswer_whenAnotherIsAsked_thenItWaitsItsTurn() = runTest {
        val confirmation = UserConfirmation<String>()
        val asked = mutableListOf<String>()
        backgroundScope.launch { confirmation.requests.collect { asked += it } }

        val first = async { confirmation.ask("first.gguf") }
        val second = async { confirmation.ask("second.gguf") }
        runCurrent()

        // Only one question is outstanding, so an answer can never be attributed to the wrong one.
        assertEquals(listOf("first.gguf"), asked)

        confirmation.answer(true)
        runCurrent()
        assertEquals(listOf("first.gguf", "second.gguf"), asked)

        confirmation.answer(false)
        assertTrue(first.await())
        assertFalse(second.await())
    }

    @Test
    fun givenAQuestionRaised_whenTheCollectorIsReplaced_thenTheNewOneIsStillAsked() = runTest {
        // A rotation replaces the collector; the question must not vanish with the old view.
        val confirmation = UserConfirmation<String>()
        val firstCollector = launch { confirmation.requests.collect { } }

        val answer = async { confirmation.ask("model.gguf") }
        runCurrent()
        firstCollector.cancelAndJoin()

        assertEquals("model.gguf", confirmation.requests.first())
        confirmation.answer(true)
        assertTrue(answer.await())
    }

    @Test
    fun givenAnAnsweredQuestion_whenACollectorSubscribesAfterwards_thenItIsNotAskedAgain() = runTest {
        // The withdrawn question must not reappear and re-show the dialog on the next resume.
        val confirmation = UserConfirmation<String>()
        val asked = mutableListOf<String>()

        val answer = async { confirmation.ask("model.gguf") }
        confirmation.requests.first()
        confirmation.answer(true)
        assertTrue(answer.await())

        backgroundScope.launch { confirmation.requests.collect { asked += it } }
        runCurrent()

        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun givenNoQuestionEverAsked_whenInspected_thenNothingIsOutstanding() {
        // After process death the UI may hold a dialog for a question this object never saw.
        assertFalse(UserConfirmation<String>().hasOutstandingRequest)
    }

    @Test
    fun givenAQuestionOnTheWay_whenInspected_thenItIsOutstandingUntilAnswered() = runTest {
        val confirmation = UserConfirmation<String>()

        val answer = async { confirmation.ask("model.gguf") }
        runCurrent()
        assertTrue(confirmation.hasOutstandingRequest)

        confirmation.answer(true)
        answer.await()

        assertFalse(confirmation.hasOutstandingRequest)
    }

    @Test
    fun givenAnAbandonedQuestion_whenTheCallerIsCancelled_thenTheNextQuestionStillGetsThrough() = runTest {
        // The ViewModel being cleared mid-dialog must not wedge the next selection.
        val confirmation = UserConfirmation<String>()
        val asked = mutableListOf<String>()
        backgroundScope.launch { confirmation.requests.collect { asked += it } }

        val abandoned = launch { confirmation.ask("abandoned.gguf") }
        runCurrent()
        abandoned.cancelAndJoin()

        val answer = async { confirmation.ask("next.gguf") }
        runCurrent()
        confirmation.answer(true)

        assertTrue(answer.await())
        assertEquals(listOf("abandoned.gguf", "next.gguf"), asked)
    }
}
