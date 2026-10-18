package com.mucacran.infocontainer.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Choreographer
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mucacran.infocontainer.R
import com.mucacran.infocontainer.network.AiReviewResponse
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Actual app navigation + ConnectionScreen at 300x480 dp. All analysis transports are socket-free. */
@RunWith(AndroidJUnit4::class)
class AiAnalysisUiRegressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private val events = Semaphore(0)
    private lateinit var scenario: ActivityScenario<ConnectionNavigationTestActivity>
    private var previousFlags = 0

    @Before
    fun setUp() {
        val info = automation.serviceInfo
        previousFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        automation.setOnAccessibilityEventListener { events.release() }
        scenario = ActivityScenario.launch(Intent(context, ConnectionNavigationTestActivity::class.java)
            .putExtra(ConnectionNavigationTestActivity.EXTRA_CASE_ID, UUID.randomUUID().toString())
            .putExtra(ConnectionNavigationTestActivity.EXTRA_ANALYSIS, true))
        click(show("home_connection"))
    }

    @After
    fun tearDown() {
        scenario.close()
        automation.setOnAccessibilityEventListener(null)
        val info = automation.serviceInfo
        info.flags = previousFlags
        automation.serviceInfo = info
    }

    @Test
    fun freshSimulation_showsEntireProposal_scrollsToEditorAndButtons_andConfirmsEditedReport() {
        // This is the physical regression: never press the unrelated demo Análisis button.
        scenario.onActivity { assertNull(it.aiDemo!!.analysis) }
        simulate()
        val details = listOf("proposal", "observations", "readings", "damage", "needs_review", "zones", "unevaluable", "summary")
        details.forEach { assertTrue(show("ai_analysis_$it").isVisibleToUser) }
        var proposalBefore: Any? = null
        scenario.onActivity {
            assertNull(it.aiDemo!!.analysis)
            proposalBefore = it.aiAnalysis!!.run!!.proposal
            assertEquals("Borrador sujeto a revisión humana.", it.aiAnalysis!!.editedReport)
        }
        setReport(REPORT)
        click(show("ai_analysis_edit"))
        assertTerminal("edición confirmada")
        scenario.onActivity {
            val state = it.aiAnalysis!!
            assertEquals(proposalBefore, state.run!!.proposal)
            assertEquals("Borrador sujeto a revisión humana.", state.run!!.proposal.reportDraft)
            assertEquals(REPORT, state.editedReport)
            assertEquals("edited", state.lastReview!!.review.action)
            assertEquals(10, state.editedReviewContent()!!.size)
            assertTrue((state.editedReviewContent()!!["visible_damage"] as List<*>).isNotEmpty())
            assertEquals(0, it.fixture.writes.get())
            assertEquals(0, it.fixture.requests.get())
            // The fake will reject if the button submitted a partial schema or wrong report.
            runBlocking {
                val result = it.fixture.aiApi.review("", "", state.run!!.run.id, state.lastReview!!.review.clientReviewId,
                    "edited", state.editedReviewContent(), null)
                assertEquals(1, result.history.size)
            }
        }
        listOf("accept", "edit", "reject").forEach { action ->
            val button = show("ai_analysis_$action")
            val bounds = Rect().also(button::getBoundsInScreen)
            assertTrue("Readable full-width $action action", bounds.width() > bounds.height() * 2)
            assertTrue("Tappable $action action", bounds.height() >= (48 * context.resources.displayMetrics.density).toInt())
        }
        assertTrue(show("ai_analysis_status").isVisibleToUser)
    }

    @Test
    fun acceptanceAndRejection_finishVisibly_andRepeatedDecisionDoesNotDuplicateReview() {
        simulate()
        click(show("ai_analysis_accept"))
        assertTerminal("aceptación confirmada")
        var first: AiReviewResponse? = null
        scenario.onActivity { first = it.aiAnalysis!!.lastReview }
        click(show("ai_analysis_accept"))
        assertTerminal("aceptación confirmada")
        scenario.onActivity {
            assertEquals(first!!.review, it.aiAnalysis!!.lastReview!!.review)
            assertEquals(1, it.aiAnalysis!!.lastReview!!.history.size)
        }
        click(show("ai_analysis_reject"))
        assertTerminal("rechazo confirmada")
        scenario.onActivity {
            assertEquals("rejected", it.aiAnalysis!!.lastReview!!.review.action)
            assertEquals(2, it.aiAnalysis!!.lastReview!!.history.size)
            assertNotNull(it.aiAnalysis!!.run)
        }
    }

    @Test
    fun reviewException_showsError_keepsEditableProposal_andRetryCompletes() {
        simulate()
        setReport(REPORT)
        scenario.onActivity { it.fixture.aiApi.reviewFailure = IllegalStateException("Synthetic failure") }
        click(show("ai_analysis_edit"))
        assertTerminal("Revisión fallida o incierta")
        scenario.onActivity {
            assertNotNull(it.aiAnalysis!!.run)
            assertNull(it.aiAnalysis!!.lastReview)
            assertEquals(REPORT, it.aiAnalysis!!.editedReport)
            it.fixture.aiApi.reviewFailure = null
        }
        assertTrue(show("ai_analysis_edit").isEnabled)
        click(show("ai_analysis_edit"))
        assertTerminal("edición confirmada")
        scenario.onActivity { assertEquals(1, it.aiAnalysis!!.lastReview!!.history.size) }
    }

    @Test
    fun suspendedReview_timesOut_unlocksButtons_andCanRetryWithoutLosingReport() {
        simulate()
        setReport(REPORT)
        scenario.onActivity { it.fixture.aiApi.reviewDelayMillis = 10_000 }
        val button = show("ai_analysis_edit")
        click(button)
        // A second stale accessibility action must not enqueue another coroutine.
        button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        assertTerminal("Tiempo de espera agotado")
        scenario.onActivity {
            assertEquals(REPORT, it.aiAnalysis!!.editedReport)
            assertNull(it.aiAnalysis!!.lastReview)
            it.fixture.aiApi.reviewDelayMillis = 0
        }
        assertTrue(show("ai_analysis_edit").isEnabled)
        click(show("ai_analysis_edit"))
        assertTerminal("edición confirmada")
        scenario.onActivity { assertEquals(1, it.aiAnalysis!!.lastReview!!.history.size) }
    }

    @Test
    fun returningToForm_preservesPendingFields_andRecreationKeepsReportAndIdempotency() {
        click(show("connection_back"))
        clickText(context.getString(R.string.btn_new_inspection))
        var pending: FormContent? = null
        var reference: FormContent? = null
        scenario.onActivity {
            assertTrue(it.formState.updateContent(it.formState.content.copy(containerIdentifier = "CSQU3054383", isoCode = "45G1", observations = "Trabajo local pendiente 📦")))
            pending = it.formState.content
            reference = it.formState.lastSavedContent
        }
        click(show("form_connection"))
        simulate()
        setReport(REPORT)
        click(show("ai_analysis_edit"))
        assertTerminal("edición confirmada")
        var first: AiReviewResponse? = null
        scenario.onActivity { first = it.aiAnalysis!!.lastReview }
        scenario.recreate()
        assertTrue(show("ai_analysis_report").isVisibleToUser)
        scenario.onActivity {
            assertEquals(REPORT, it.aiAnalysis!!.editedReport)
            assertNull(it.aiDemo!!.analysis)
        }
        click(show("ai_analysis_edit"))
        assertTerminal("edición confirmada")
        scenario.onActivity { assertEquals(first!!.review, it.aiAnalysis!!.lastReview!!.review); assertEquals(1, it.aiAnalysis!!.lastReview!!.history.size) }
        click(show("connection_back", forward = false))
        show("form_connection")
        scenario.onActivity {
            assertEquals(pending, it.formState.content)
            assertEquals(reference, it.formState.lastSavedContent)
            assertTrue(it.formState.hasUnsavedChanges)
            assertEquals(0, it.fixture.writes.get())
            assertEquals(0, it.fixture.requests.get())
        }
    }

    private fun simulate() {
        click(show("ai_analysis_simulate"))
        await("coordinated proposal") { var ready = false; scenario.onActivity { ready = it.aiAnalysis!!.run != null && !it.aiAnalysis!!.isBusy }; ready }
        assertTrue(show("ai_analysis_proposal").isVisibleToUser)
    }

    private fun assertTerminal(expected: String) {
        await("terminal review state") {
            var ready = false
            scenario.onActivity { ready = !it.aiAnalysis!!.isBusy && it.aiAnalysis!!.status.contains(expected) }
            ready
        }
        show("ai_analysis_status")
        // State can complete before Compose publishes the next accessibility frame.
        await("visible terminal message") {
            nodes().filter { it.isVisibleToUser && matches(it, "ai_analysis_status") }
                .any { root -> subtree(root).any { it.text?.contains(expected) == true } }
        }
        scenario.onActivity { assertFalse(it.aiAnalysis!!.status.contains("Registrando revisión")) }
    }

    private fun setReport(value: String) {
        val editable = subtree(show("ai_analysis_report")).firstOrNull { it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
        assertNotNull(editable)
        assertTrue(editable!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        scenario.onActivity { it.window.decorView.clearFocus(); it.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.window.decorView.windowToken, 0) }
        settle()
    }

    private fun show(tag: String, forward: Boolean = true): AccessibilityNodeInfo {
        await("app window") { nodes().isNotEmpty() }
        repeat(40) {
            nodes().firstOrNull { it.isVisibleToUser && matches(it, tag) }?.let { return it }
            val scroll = nodes().firstOrNull { matches(it, "connection_scroll") }
            val goForward = if (it < 20) forward else !forward
            if (scroll != null) { swipe(scroll, goForward); settle() }
        }
        error("Control not reachable by scrolling: $tag")
    }

    private fun swipe(scroll: AccessibilityNodeInfo, forward: Boolean) {
        val bounds = Rect().also(scroll::getBoundsInScreen)
        // Swipe in the outer padding so the editor's internal scroll cannot consume the gesture.
        val x = bounds.left + bounds.width() * 0.02f
        val from = bounds.top + bounds.height() * if (forward) 0.8f else 0.35f
        val to = bounds.top + bounds.height() * if (forward) 0.35f else 0.8f
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, from)
        for (step in 1..16) { frame(); send(MotionEvent.ACTION_MOVE, from + (to - from) * step / 16) }
        repeat(4) { frame(); send(MotionEvent.ACTION_MOVE, to) }
        send(MotionEvent.ACTION_UP, to)
    }

    private fun frame() {
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback { latch.countDown() } }
        check(latch.await(2, TimeUnit.SECONDS))
    }

    private fun matches(node: AccessibilityNodeInfo, tag: String) = node.viewIdResourceName == tag || node.viewIdResourceName?.endsWith(":id/$tag") == true
    private fun clickText(text: String) {
        var target: AccessibilityNodeInfo? = null
        await("navigation action") { target = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == text }; target != null }
        click(target!!)
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && target.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) target = target.parent
        assertNotNull(target)
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        settle()
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val roots = buildList {
            automation.rootInActiveWindow?.takeIf { it.packageName?.toString() == context.packageName }?.let(::add)
            automation.windows.forEach { it.root?.takeIf { node -> node.packageName?.toString() == context.packageName }?.let(::add) }
        }.distinctBy { it.windowId }
        return roots.flatMap(::subtree)
    }
    private fun subtree(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        val pending = ArrayDeque<AccessibilityNodeInfo>(); pending.add(root)
        while (pending.isNotEmpty()) { val node = pending.removeFirst(); add(node); repeat(node.childCount) { node.getChild(it)?.let(pending::add) } }
    }
    private fun settle() {
        instrumentation.waitForIdleSync()
        try { automation.waitForIdle(120, 1_000) } catch (_: TimeoutException) { /* Processing states may keep emitting. */ }
        instrumentation.waitForIdleSync()
    }
    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        do { instrumentation.waitForIdleSync(); if (condition()) return; events.tryAcquire(100, TimeUnit.MILLISECONDS) } while (SystemClock.uptimeMillis() < until)
        error("Timed out waiting for $label")
    }
    companion object { private const val REPORT = "Informe revisado y aprobado por el operador" }
}
