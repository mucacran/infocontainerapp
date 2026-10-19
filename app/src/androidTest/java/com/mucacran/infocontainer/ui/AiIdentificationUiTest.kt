package com.mucacran.infocontainer.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mucacran.infocontainer.R
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** Main form -> Connection -> real identification UI, using an in-memory AiApi with zero sockets. */
@RunWith(AndroidJUnit4::class)
class AiIdentificationUiTest {
    @get:Rule val testName = TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private val events = Semaphore(0)
    private lateinit var caseId: String
    private lateinit var scenario: ActivityScenario<ConnectionNavigationTestActivity>
    private var previousFlags = 0

    @Before
    fun setUp() {
        caseId = UUID.randomUUID().toString()
        val info = automation.serviceInfo
        previousFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        automation.setOnAccessibilityEventListener { events.release() }
        scenario = ActivityScenario.launch(Intent(context, ConnectionNavigationTestActivity::class.java)
            .putExtra(ConnectionNavigationTestActivity.EXTRA_CASE_ID, caseId)
            .putExtra(ConnectionNavigationTestActivity.EXTRA_IDENTIFICATION, true)
            .putExtra(ConnectionNavigationTestActivity.EXTRA_IDENTIFICATION_TIMEOUT_MILLIS,
                if (testName.methodName.startsWith("pendingIdentification")) 30_000L else 2_000L))
        clickText(context.getString(R.string.btn_new_inspection))
        show("form_connection")
        await("editable form") { var editable = false; scenario.onActivity { editable = it.formState.canEdit }; editable }
        scenario.onActivity { activity ->
            val photo = requireNotNull(activity.fixture.syntheticPhoto)
            assertTrue(activity.formState.updateContent(activity.formState.content.copy(
                containerIdentifier = "",
                isoCode = "",
                observations = "Trabajo pendiente de identificación sintética",
                photos = listOf(photo)
            )))
            assertEquals(0, activity.fixture.writes.get())
        }
        click(show("form_connection"))
        show("connection_password")
        enterCredentials()
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        automation.setOnAccessibilityEventListener(null)
        val info = automation.serviceInfo
        info.flags = previousFlags
        automation.serviceInfo = info
        if (!::caseId.isInitialized) return
        val root = File(context.cacheDir, "connection_ui_test_$caseId").canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile && root.name.endsWith(caseId))
        if (root.exists()) root.deleteRecursively()
    }

    @Test
    fun photoSelectionAndConfirmation_sendExactlyOneFakePost_showProposal_preserveForm() {
        var before: FormContent? = null
        var reference: FormContent? = null
        scenario.onActivity {
            before = it.formState.content
            reference = it.formState.lastSavedContent
            assertNotNull(it.fixture.syntheticPhoto)
            assertEquals(0, it.fixture.identificationApi.identifyCalls.get())
        }
        click(show("ai_demo_identification"))
        scenario.onActivity {
            assertNotNull(it.aiDemo!!.identification)
            assertNull(it.aiDemo!!.realRun)
            assertEquals(0, it.fixture.identificationApi.identifyCalls.get())
        }
        click(show("ai_real_photo_1", forward = false))
        openRealDialog()
        assertTrue(show("ai_real_confirm_dialog").isVisibleToUser)
        scenario.onActivity { assertEquals(0, it.fixture.identificationApi.identifyCalls.get()) }
        assertFalse(show("ai_real_confirm").isEnabled)
        click(show("ai_real_synthetic_confirmation"))
        assertTrue("Explicit synthetic confirmation enables send", show("ai_real_confirm").isEnabled)
        click(show("ai_real_confirm"))
        await("one completed fake identification") {
            var done = false
            scenario.onActivity {
                done = !it.aiDemo!!.realBusy && it.aiDemo!!.realRun?.run?.status == "completed" &&
                    it.fixture.identificationApi.identifyCalls.get() == 1
            }
            done
        }
        val proposal = show("ai_real_proposal")
        assertTrue(proposal.isVisibleToUser)
        assertTrue(subtree(proposal).any { it.text?.contains("TSTU4855933") == true })
        assertTrue(showTextContaining("22G1").isVisibleToUser)
        assertTrue(show("ai_real_identification_status").isVisibleToUser)
        scenario.onActivity {
            val state = it.aiDemo!!
            assertEquals(requireNotNull(it.fixture.syntheticPhoto).id, state.realSelectedPhotoId)
            assertTrue(state.realAttemptStarted)
            assertEquals(1, it.fixture.identificationApi.identifyCalls.get())
            assertEquals(1, it.fixture.identificationApi.clientRequestIds.distinct().size)
            assertEquals(before, it.formState.content)
            assertEquals(reference, it.formState.lastSavedContent)
            assertEquals(0, it.fixture.writes.get())
            assertEquals(0, it.fixture.requests.get())
        }
        // A new logical provider request is unavailable after this attempt, even if the user taps again.
        assertFalse(show("ai_real_identify", forward = false).isEnabled)
        click(show("ai_demo_identification"))
        scenario.onActivity { assertEquals(1, it.fixture.identificationApi.identifyCalls.get()) }
        click(show("connection_back", forward = false))
        show("form_connection")
        scenario.onActivity {
            assertEquals(before, it.formState.content)
            assertEquals(reference, it.formState.lastSavedContent)
            assertEquals(0, it.fixture.writes.get())
        }
    }

    @Test
    fun pendingIdentification_survivesActivityRecreation_withoutNewPost() {
        val held = CompletableDeferred<Unit>()
        scenario.onActivity { it.fixture.identificationApi.holdResponse = held }
        click(show("ai_real_photo_1"))
        openRealDialog()
        click(show("ai_real_synthetic_confirmation"))
        assertTrue(show("ai_real_confirm").isEnabled)
        click(show("ai_real_confirm"))
        await("one held fake POST") {
            var pending = false
            scenario.onActivity {
                pending = it.fixture.identificationApi.identifyCalls.get() == 1 && it.aiDemo!!.realBusy
            }
            pending
        }
        var requestId: String? = null
        scenario.onActivity { requestId = it.fixture.identificationApi.clientRequestIds.single() }
        scenario.recreate()
        scenario.onActivity {
            assertEquals(1, it.fixture.identificationApi.identifyCalls.get())
            assertTrue(it.aiDemo!!.realBusy)
        }
        held.complete(Unit)
        await("identification completes after Activity recreation") {
            var done = false
            scenario.onActivity { done = !it.aiDemo!!.realBusy && it.aiDemo!!.realRun?.run?.status == "completed" }
            done
        }
        show("ai_real_proposal")
        scenario.onActivity {
            assertEquals(1, it.fixture.identificationApi.identifyCalls.get())
            assertEquals(requestId, it.aiDemo!!.realRun!!.run.clientRequestId)
            assertEquals(0, it.fixture.requests.get())
            assertEquals(0, it.fixture.writes.get())
        }
    }

    @Test
    fun processingRun_isCheckedByGet_andNeverStartsAnotherPost() {
        scenario.onActivity { it.fixture.identificationApi.nextStatus = "processing" }
        click(show("ai_real_photo_1"))
        scenario.onActivity { assertEquals(it.fixture.syntheticPhoto!!.id, it.aiDemo!!.realSelectedPhotoId) }
        openRealDialog()
        click(show("ai_real_synthetic_confirmation"))
        assertTrue(show("ai_real_confirm").isEnabled)
        click(show("ai_real_confirm"))
        await("processing run ID") {
            var ready = false
            scenario.onActivity {
                ready = !it.aiDemo!!.realBusy && it.aiDemo!!.realRun?.run?.status == "processing" &&
                    it.aiDemo!!.realKnownRunId == 701L
            }
            ready
        }
        scenario.onActivity {
            assertEquals(1, it.fixture.identificationApi.identifyCalls.get())
            assertEquals(0, it.fixture.identificationApi.lookupCalls.get())
            it.fixture.identificationApi.lookupStatus = "completed"
        }
        click(show("ai_real_check_existing"))
        await("same run completed after fake GET") {
            var ready = false
            scenario.onActivity {
                ready = !it.aiDemo!!.realBusy && it.aiDemo!!.realRun?.run?.status == "completed" &&
                    it.fixture.identificationApi.lookupCalls.get() == 1
            }
            ready
        }
        assertTrue(show("ai_real_proposal").isVisibleToUser)
        assertFalse(show("ai_real_identify", forward = false).isEnabled)
        scenario.onActivity {
            assertEquals(1, it.fixture.identificationApi.identifyCalls.get())
            assertEquals(it.fixture.identificationApi.clientRequestIds.single(), it.aiDemo!!.realRun!!.run.clientRequestId)
        }
    }

    @Test
    fun uncertainTimeout_preservesRequestId_blocksSecondPost_andKeepsOriginalPhoto() {
        val held = CompletableDeferred<Unit>()
        scenario.onActivity { it.fixture.identificationApi.holdResponse = held }
        click(show("ai_real_photo_1"))
        openRealDialog()
        click(show("ai_real_synthetic_confirmation"))
        assertTrue(show("ai_real_confirm").isEnabled)
        click(show("ai_real_confirm"))
        await("single fake POST becomes uncertain") {
            var uncertain = false
            scenario.onActivity {
                uncertain = it.fixture.identificationApi.identifyCalls.get() == 1 &&
                    it.aiDemo!!.realAttemptStarted && !it.aiDemo!!.realBusy &&
                    it.aiDemo!!.realStatus.contains("incierto")
            }
            uncertain
        }
        assertTrue(show("ai_real_identification_status").isVisibleToUser)
        assertFalse(show("ai_real_identify", forward = false).isEnabled)
        var requestId: String? = null
        scenario.onActivity {
            requestId = it.aiDemo!!.realRequestId
            assertEquals(it.fixture.identificationApi.clientRequestIds.single(), requestId)
            assertNull(it.aiDemo!!.realKnownRunId)
            val photo = requireNotNull(it.fixture.syntheticPhoto)
            val original = File(context.cacheDir, "connection_ui_test_$caseId/no_backup/inspection_photos/${photo.id}.image")
            assertTrue(original.isFile && original.length() == photo.byteSize)
            assertEquals(0, it.fixture.writes.get())
        }
        scenario.recreate()
        assertFalse(show("ai_real_identify", forward = false).isEnabled)
        scenario.onActivity {
            assertEquals(requestId, it.aiDemo!!.realRequestId)
            assertEquals(1, it.fixture.identificationApi.identifyCalls.get())
            assertEquals(0, it.fixture.requests.get())
            assertEquals(0, it.fixture.writes.get())
        }
    }

    private fun enterCredentials() {
        setText("connection_username", "synthetic-identification-user")
        setText("connection_password", "synthetic-test-only")
        scenario.onActivity {
            it.window.decorView.clearFocus()
            it.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(it.window.decorView.windowToken, 0)
        }
        settle()
    }

    private fun setText(tag: String, value: String) {
        val editable = subtree(show(tag)).firstOrNull { node ->
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        }
        assertNotNull("Editable $tag", editable)
        assertTrue(editable!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        settle()
    }

    private fun show(tag: String, forward: Boolean = true): AccessibilityNodeInfo {
        await("app window") { nodes().isNotEmpty() }
        repeat(40) { index ->
            nodes().firstOrNull { it.isVisibleToUser && matches(it, tag) }?.let { return it }
            val scroll = nodes().firstOrNull { matches(it, "connection_scroll") }
            if (scroll != null) { swipe(scroll, if (index < 20) forward else !forward); settle() }
        }
        val dialogEvidence = nodes().filter { node ->
            node.viewIdResourceName?.startsWith("ai_real_") == true ||
                node.text?.toString()?.startsWith("Iniciar identificación real") == true ||
                node.text?.toString()?.startsWith("Confirmo que la fotografía") == true ||
                node.text?.toString()?.startsWith("Enviar una vez") == true
        }.take(20).joinToString { node ->
            "${node.viewIdResourceName}:${node.text?.toString()?.take(35)}:visible=${node.isVisibleToUser}:enabled=${node.isEnabled}:checkable=${node.isCheckable}"
        }
        error("Control not reachable by scrolling: $tag; dialog nodes: $dialogEvidence")
    }

    private fun showTextContaining(value: String): AccessibilityNodeInfo {
        repeat(40) {
            nodes().firstOrNull { it.isVisibleToUser && it.text?.contains(value) == true }?.let { return it }
            nodes().firstOrNull { matches(it, "connection_scroll") }?.let { scroll -> swipe(scroll, true); settle() }
        }
        error("Text not reachable by scrolling: $value")
    }

    private fun openRealDialog() {
        // A tag can be reported visible when only a few pixels of the button enter the small viewport.
        // Scroll it toward the centre before injecting the same kind of tap used on the phone.
        val scroll = show("connection_scroll")
        swipe(scroll, true)
        settle()
        val identify = show("ai_real_identify", forward = false)
        assertTrue("Identification button enabled", identify.isEnabled)
        tap(identify)
        await("real confirmation dialog") { nodes().any { matches(it, "ai_real_confirm_dialog") && it.isVisibleToUser } }
    }

    private fun swipe(scroll: AccessibilityNodeInfo, forward: Boolean) {
        val bounds = Rect().also(scroll::getBoundsInScreen)
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

    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        check(bounds.width() > 0 && bounds.height() > 0)
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        settle()
    }

    private fun frame() {
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback { latch.countDown() } }
        check(latch.await(2, TimeUnit.SECONDS))
    }

    private fun matches(node: AccessibilityNodeInfo, tag: String) =
        node.viewIdResourceName == tag || node.viewIdResourceName?.endsWith(":id/$tag") == true

    private fun clickText(text: String) {
        var target: AccessibilityNodeInfo? = null
        await("navigation action") { target = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == text }; target != null }
        click(requireNotNull(target))
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
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst(); add(node)
            repeat(node.childCount) { node.getChild(it)?.let(pending::add) }
        }
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        try { automation.waitForIdle(120, 1_000) } catch (_: TimeoutException) { }
        instrumentation.waitForIdleSync()
    }

    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        do { instrumentation.waitForIdleSync(); if (condition()) return; events.tryAcquire(100, TimeUnit.MILLISECONDS) }
        while (SystemClock.uptimeMillis() < until)
        error("Timed out waiting for $label")
    }
}
