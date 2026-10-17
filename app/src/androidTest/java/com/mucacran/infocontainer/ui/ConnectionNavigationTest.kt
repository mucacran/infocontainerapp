package com.mucacran.infocontainer.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mucacran.infocontainer.R
import java.io.File
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real navigation/orientation/accessibility with only synthetic input and a socket-free probe. */
@RunWith(AndroidJUnit4::class)
class ConnectionNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private val accessibilityEvents = Semaphore(0)
    private lateinit var caseId: String
    private var scenario: ActivityScenario<ConnectionNavigationTestActivity>? = null
    private var previousAccessibilityFlags = 0

    @Before
    fun setUp() {
        caseId = UUID.randomUUID().toString()
        val info = automation.serviceInfo
        previousAccessibilityFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        automation.setOnAccessibilityEventListener { accessibilityEvents.release() }
        scenario = ActivityScenario.launch(
            Intent(context, ConnectionNavigationTestActivity::class.java)
                .putExtra(ConnectionNavigationTestActivity.EXTRA_CASE_ID, caseId)
        )
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await("initial portrait orientation") {
            var portrait = false
            scenario!!.onActivity { portrait = it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            portrait
        }
        awaitTag("home_connection")
    }

    @After
    fun tearDown() {
        scenario?.close()
        automation.setOnAccessibilityEventListener(null)
        val info = automation.serviceInfo
        info.flags = previousAccessibilityFlags
        automation.serviceInfo = info
        // This host never constructs a database helper. Only this test's UUID cache directory is removed.
        val root = File(context.cacheDir, "connection_ui_test_$caseId").canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile && root.name.endsWith(caseId))
        if (root.exists()) root.deleteRecursively()
    }

    @Test
    fun newForm_openAndCloseConnectionPreservesEveryPendingFieldAndReference() {
        openForm(existing = false)
        assertConnectionRoundTripPreservesForm(expectedDraftId = null)
    }

    @Test
    fun existingForm_openAndCloseConnectionPreservesDraftIdentityAndUnsavedEdits() {
        openForm(existing = true)
        assertConnectionRoundTripPreservesForm(expectedDraftId = "isolated-navigation-fixture")
    }

    @Test
    fun maskedSecret_inFlightProbeSurvivesRealRotationsOnce_andCloseReopenClearsCredentials() {
        click(awaitTag("home_connection"))
        enterSyntheticCredentials()
        var originalConnectionState: ConnectionState? = null
        scenario!!.onActivity { originalConnectionState = it.connectionState }
        val testButton = awaitTag("connection_test")
        click(testButton)
        // A stale accessibility snapshot can still receive a second tap before the disabled UI frame.
        testButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        await("one pending fake probe") {
            var pending = false
            scenario!!.onActivity { pending = it.connectionState.isChecking && it.fixture.requests.get() == 1 }
            pending
        }

        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)

        scenario!!.onActivity {
            assertTrue(originalConnectionState === it.connectionState)
            assertTrue(it.connectionState.isChecking)
            assertEquals(1, it.fixture.requests.get())
            assertEquals(SYNTHETIC_PASSWORD, it.connectionState.password)
        }
        assertMaskedPassword()
        assertFalse(awaitTag("connection_test").isEnabled)
        click(awaitTag("connection_back"))
        awaitTag("home_connection")
        await("in-flight fake probe cancelled on close") {
            var cancelled = false
            scenario!!.onActivity { cancelled = it.fixture.cancellations.get() == 1 }
            cancelled
        }
        scenario!!.onActivity {
            assertEquals("", it.connectionState.password)
            assertEquals("", it.connectionState.username)
            assertFalse(it.connectionState.isChecking)
            assertNull(it.connectionState.result)
            assertEquals(1, it.fixture.requests.get())
            assertEquals(0, it.fixture.writes.get())
        }

        click(awaitTag("home_connection"))
        awaitTag("connection_password")
        scenario!!.onActivity {
            assertEquals("", it.connectionState.password)
            assertEquals("", it.connectionState.username)
            assertFalse(it.connectionState.canTest)
            assertEquals(1, it.fixture.requests.get())
        }
        assertFalse(awaitTag("connection_test").isEnabled)
    }

    private fun openForm(existing: Boolean) {
        clickText(context.getString(if (existing) R.string.btn_saved_inspections else R.string.btn_new_inspection))
        if (existing) clickText("CSQU3054383")
        awaitTag("form_connection")
        await("editable form") {
            var editable = false
            scenario!!.onActivity { editable = it.formState.canEdit }
            editable
        }
    }

    private fun assertConnectionRoundTripPreservesForm(expectedDraftId: String?) {
        var expected: FormContent? = null
        var reference: FormContent? = null
        var session = ""
        var createdAt = 0L
        scenario!!.onActivity {
            assertTrue(it.formState.updateContent(it.formState.content.copy(
                containerIdentifier = "CSQU3054383",
                isoCode = "45G1",
                inspectionType = "receipt",
                inspectedAt = "2026-10-17T12:34:56-05:00",
                observations = "Trabajo pendiente al abrir conexión 📦"
            )))
            expected = it.formState.content
            reference = it.formState.lastSavedContent
            session = it.formState.photoSessionId
            createdAt = it.formState.createdAt
            assertEquals(expectedDraftId, it.formState.draftId)
            assertTrue(it.formState.hasUnsavedChanges)
        }

        click(awaitTag("form_connection"))
        enterSyntheticCredentials()
        scenario!!.onActivity { assertEquals(0, it.fixture.requests.get()) }
        click(awaitTag("connection_back"))
        awaitTag("form_connection")

        scenario!!.onActivity {
            assertEquals(expected, it.formState.content)
            assertEquals(reference, it.formState.lastSavedContent)
            assertEquals(expectedDraftId, it.formState.draftId)
            assertEquals(session, it.formState.photoSessionId)
            assertEquals(createdAt, it.formState.createdAt)
            assertTrue(it.formState.hasUnsavedChanges)
            assertEquals("", it.connectionState.password)
            assertEquals(0, it.fixture.requests.get())
            assertEquals(0, it.fixture.writes.get())
        }
        click(awaitTag("form_back"))
        await("unsaved work warning") {
            nodes().any { it.isVisibleToUser && it.text?.toString() == context.getString(R.string.dialog_unsaved_changes_title) }
        }
        clickText(context.getString(R.string.btn_cancel))
        awaitTag("form_connection")
        scenario!!.onActivity { assertEquals(expected, it.formState.content) }
    }

    private fun enterSyntheticCredentials() {
        setText("connection_username", "synthetic_navigation_user")
        setText("connection_password", SYNTHETIC_PASSWORD)
        hideKeyboard()
        await("credentials entered in memory") {
            var entered = false
            scenario!!.onActivity {
                entered = it.connectionState.username == "synthetic_navigation_user" &&
                    it.connectionState.password == SYNTHETIC_PASSWORD
            }
            entered
        }
        assertMaskedPassword()
    }

    private fun assertMaskedPassword() {
        val password = subtree(awaitTag("connection_password"))
        assertTrue("Password semantics must be enabled", password.any { it.isPassword })
        assertFalse("Synthetic secret must be visually transformed", password.any { it.text?.toString() == SYNTHETIC_PASSWORD })
    }

    private fun rotate(requested: Int, expected: Int) {
        var previousInstance = ""
        scenario!!.onActivity {
            previousInstance = it.instanceId
            it.requestedOrientation = requested
        }
        await("real orientation $expected") {
            var rotated = false
            scenario!!.onActivity {
                rotated = it.resources.configuration.orientation == expected && it.instanceId != previousInstance
            }
            rotated
        }
        awaitTag("connection_back")
        scenario!!.onActivity { assertNotEquals(previousInstance, it.instanceId) }
    }

    private fun setText(tag: String, value: String) {
        val editable = subtree(awaitTag(tag)).firstOrNull { node ->
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        }
        assertNotNull("Editable field $tag", editable)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        assertTrue("Set text $tag", editable!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        settleAccessibility()
    }

    private fun hideKeyboard() {
        scenario!!.onActivity {
            it.window.decorView.clearFocus()
            it.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(it.window.decorView.windowToken, 0)
        }
        settleAccessibility()
    }

    private fun clickText(text: String) {
        var node: AccessibilityNodeInfo? = null
        await("visible requested action") {
            node = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == text }
            node != null
        }
        click(requireNotNull(node))
    }

    private fun click(node: AccessibilityNodeInfo) {
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && clickable.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
            clickable = clickable.parent
        }
        assertNotNull("Clickable node", clickable)
        assertTrue("Click accepted", clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        settleAccessibility()
    }

    private fun awaitTag(tag: String): AccessibilityNodeInfo {
        var matched: AccessibilityNodeInfo? = null
        await("$tag visible") {
            matched = nodes().firstOrNull {
                it.isVisibleToUser && (it.viewIdResourceName == tag || it.viewIdResourceName?.endsWith(":id/$tag") == true)
            }
            matched != null
        }
        return requireNotNull(matched)
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val roots = buildList {
            automation.rootInActiveWindow?.takeIf { it.packageName?.toString() == context.packageName }?.let { add(it) }
            automation.windows.sortedByDescending { it.layer }.forEach { window ->
                window.root?.takeIf { it.packageName?.toString() == context.packageName }?.let { add(it) }
            }
        }.distinctBy { it.windowId }
        return roots.flatMap(::subtree)
    }

    private fun subtree(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { pending.add(it) }
        }
    }

    private fun settleAccessibility() {
        instrumentation.waitForIdleSync()
        try {
            automation.waitForIdle(150L, 3_000L)
        } catch (_: TimeoutException) {
            // Progress semantics can keep emitting while the fake probe is deliberately pending.
        }
        instrumentation.waitForIdleSync()
        accessibilityEvents.drainPermits()
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            accessibilityEvents.tryAcquire(150, TimeUnit.MILLISECONDS)
        } while (SystemClock.uptimeMillis() < deadline)
        // Never dump field contents: a future test must not accidentally write a secret to reports.
        error("Timed out waiting for $label. Visible tags: ${nodes().filter { it.isVisibleToUser }.mapNotNull { it.viewIdResourceName }}")
    }

    companion object {
        // Clearly synthetic UI fixture input; the fake probe never transmits or retains it.
        private const val SYNTHETIC_PASSWORD = "synthetic-navigation-test-value"
    }
}
