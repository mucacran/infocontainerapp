package com.mucacran.infocontainer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Only UUID-named synthetic cache fixtures; no production journal, DB, or photograph is opened. */
@RunWith(AndroidJUnit4::class)
class IdentificationAttemptStoreTest {
    @Test fun reservationAndRunIdSurviveStoreRecreationAndRejectSecondAttempt() {
        val path = newFixturePath()
        val first = IdentificationAttemptStore(path)
        val attempt = syntheticAttempt()
        assertNull(first.reserveIfAbsent(attempt))

        val reopened = IdentificationAttemptStore(path)
        assertEquals(attempt, reopened.read())
        assertEquals(attempt, reopened.reserveIfAbsent(syntheticAttempt()))
        reopened.recordRunId(attempt.clientRequestId, 101)

        val third = IdentificationAttemptStore(path)
        assertEquals(attempt.copy(runId = 101), third.read())
        assertEquals(attempt.copy(runId = 101), third.reserveIfAbsent(syntheticAttempt()))
    }

    @Test fun corruptReservationFailsClosedAndIsNotOverwritten() {
        val path = newFixturePath()
        path.writeText("{invalid", Charsets.UTF_8)
        val store = IdentificationAttemptStore(path)
        val before = path.readBytes()

        assertTrue(failure { store.read() } is IllegalStateException)
        assertTrue(failure { store.reserveIfAbsent(syntheticAttempt()) } is IllegalStateException)
        assertTrue(before.contentEquals(path.readBytes()))
    }

    private fun newFixturePath(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.cacheDir, "ai-identification-attempt-test-${UUID.randomUUID()}.json")
    }

    private fun syntheticAttempt() = IdentificationAttempt(
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), "a".repeat(64), null
    )

    private fun failure(block: () -> Unit): Exception = try {
        block()
        throw AssertionError("Expected failure")
    } catch (exception: Exception) { exception }
}
