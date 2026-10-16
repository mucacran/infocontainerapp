package com.mucacran.infocontainer.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
import com.mucacran.infocontainer.data.InspectionDatabaseHelper
import com.mucacran.infocontainer.data.LocalPhotoStore
import com.mucacran.infocontainer.data.SqliteInspectionDraftRepository
import com.mucacran.infocontainer.model.InspectionDraft
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Activity/accessibility/SQLite/private PNG integration, isolated by a fresh UUID per test.
 * Android's UiAutomation performs UI actions directly; this harness never initializes Espresso.
 */
@RunWith(AndroidJUnit4::class)
class PhotoFormLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private val accessibilityEvents = Semaphore(0)
    private lateinit var caseId: String
    private var scenario: ActivityScenario<PhotoFormTestActivity>? = null
    private var previousAccessibilityFlags = 0

    @Before
    fun setUp() {
        caseId = UUID.randomUUID().toString()
        PhotoFormTestActivity.backCalls.set(0)
        val info = automation.serviceInfo
        previousAccessibilityFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        automation.setOnAccessibilityEventListener { accessibilityEvents.release() }
    }

    @After
    fun tearDown() {
        scenario?.close()
        automation.setOnAccessibilityEventListener(null)
        val info = automation.serviceInfo
        info.flags = previousAccessibilityFlags
        automation.serviceInfo = info
        val name = PhotoFormTestActivity.databaseName(caseId)
        check(name.startsWith("form_photo_ui_test_") && name != InspectionDatabaseHelper.DATABASE_NAME)
        context.deleteDatabase(name)
        val root = File(context.cacheDir, "form_photo_ui_test_$caseId").canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile && root.name.endsWith(caseId))
        if (root.exists()) root.deleteRecursively()
    }

    @Test
    fun newDraft_realLandscapeAndPortraitRotationPreservePendingFieldsPhotosAndExitWarning() {
        launch()
        enterChangesAndAttachPhoto()
        val expected = content()
        val reference = reference()

        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        assertPending(expected, reference)
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        assertPending(expected, reference)
        showExitWarning()
        clickText(context.getString(R.string.btn_cancel))
        assertPending(expected, reference)
        assertEquals(0, PhotoFormTestActivity.backCalls.get())
        assertTrue(readDrafts().isEmpty())
    }

    @Test
    fun existingDraft_realRotationAndDiscardKeepPersistedFieldsAndOriginalPhoto() {
        launch(existing = true)
        val saved = readDrafts().single()
        val persistedOriginal = original(saved.photos.single().id)
        val bytes = persistedOriginal.readBytes()
        enterChangesAndAttachPhoto()
        val expected = content()
        val reference = reference()

        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        assertPending(expected, reference)
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        assertPending(expected, reference)
        showExitWarning()
        clickText(context.getString(R.string.btn_discard))
        await("callback al descartar") { PhotoFormTestActivity.backCalls.get() == 1 }
        assertEquals(saved, readDrafts().single())
        assertArrayEquals(bytes, persistedOriginal.readBytes())

        scenario!!.close()
        launch(existing = true)
        assertEquals(saved.photos, content().photos)
        assertEquals(saved.observations, content().observations)
        scenario!!.onActivity { assertFalse(it.formState.hasUnsavedChanges) }
        assertPhotoPreviews(1)
    }

    @Test
    fun activityScenarioRecreationRetainsUnsavedFormAndPhotoWithoutClaimingProcessDeath() {
        launch()
        enterChangesAndAttachPhoto()
        val expected = content()
        val reference = reference()
        var previousInstance = ""
        scenario!!.onActivity { previousInstance = it.instanceId }

        scenario!!.recreate()
        awaitReady()

        scenario!!.onActivity { assertNotEquals(previousInstance, it.instanceId) }
        assertPending(expected, reference)
        showExitWarning()
        assertTrue(readDrafts().isEmpty())
    }

    @Test
    fun savePhoto_closeAndRelaunchReadTheSameOriginalAndSqliteMetadata() {
        launch()
        enterChangesAndAttachPhoto()
        val attached = content().photos.single()
        val bytes = original(attached.id).readBytes()

        click(scrollToTag("form_save"))
        await("guardado confirmado") {
            var saved = false
            scenario!!.onActivity { saved = it.formState.draftId != null && !it.formState.isSaving && !it.formState.hasUnsavedChanges }
            saved
        }
        val draft = readDrafts().single()
        assertEquals(listOf(attached), draft.photos)
        scenario!!.onActivity { assertEquals(it.formState.content, it.formState.lastSavedContent) }

        scenario!!.close()
        launch(draftId = draft.id)

        assertEquals(draft.photos, content().photos)
        assertEquals(draft.observations, content().observations)
        assertArrayEquals(bytes, original(attached.id).readBytes())
        scenario!!.onActivity { activity ->
            assertTrue(activity.photoStore.exists(attached))
            assertFalse(activity.formState.hasUnsavedChanges)
        }
        assertPhotoPreviews(1)
    }

    @Test
    fun saveFromExitDialogKeepsTheScreenAndRetryOptionsWhileACaptureIsStillPending() {
        launch()
        enterFields()
        var fixtureStore: LocalPhotoStore? = null
        scenario!!.onActivity { fixtureStore = it.photoStore }
        // Only an empty app-private fixture is prepared; no camera or gallery is opened.
        val captureId = runBlocking { requireNotNull(fixtureStore).createCapture() }
        scenario!!.onActivity { activity ->
            activity.formState.setPendingCapture(captureId)
            activity.formState.reportPhotoError("Captura de prueba pendiente de incorporar")
        }

        showExitWarning()
        clickText(context.getString(R.string.btn_save))
        await("guardado con captura todavía pendiente") {
            var completed = false
            scenario!!.onActivity {
                completed = it.formState.draftId != null && !it.formState.isSaving && it.formState.completedSaveCount > 0
            }
            completed
        }

        assertEquals(0, PhotoFormTestActivity.backCalls.get())
        scenario!!.onActivity { activity ->
            assertEquals(captureId, activity.formState.pendingCaptureId)
            assertEquals(activity.formState.content, activity.formState.lastSavedContent)
            assertTrue(activity.formState.hasUnsavedChanges)
            assertTrue(activity.formState.canEdit)
        }
        val saved = readDrafts().single()
        assertTrue(saved.photos.isEmpty())
        assertEquals("Observación pendiente con fotografía", saved.observations)
        scrollToTag("photo_picker")
        await("opciones para reintentar o retirar la captura") {
            val visibleText = nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }
            "Reintentar captura pendiente" in visibleText && "Quitar captura pendiente" in visibleText
        }
        assertTrue(awaitVisibleTag("form_back").isVisibleToUser)
        assertEquals(0, PhotoFormTestActivity.backCalls.get())
    }

    private fun launch(existing: Boolean = false, draftId: String? = null) {
        val intent = Intent(context, PhotoFormTestActivity::class.java)
            .putExtra(PhotoFormTestActivity.EXTRA_CASE_ID, caseId)
            .putExtra(PhotoFormTestActivity.EXTRA_EXISTING, existing)
        if (draftId != null) intent.putExtra(PhotoFormTestActivity.EXTRA_DRAFT_ID, draftId)
        scenario = ActivityScenario.launch(intent)
        awaitReady()
    }

    private fun awaitReady() {
        await("formulario editable") {
            var ready = false
            scenario!!.onActivity { ready = it.formState.canEdit }
            ready
        }
        await("nodos accesibles del formulario") { findTag("form_back") != null }
    }

    private fun enterChangesAndAttachPhoto() {
        enterFields()
        val count = content().photos.size
        scenario!!.onActivity { it.attachSyntheticPhoto() }
        await("copia privada de fotografía sintética") {
            var imported = false
            scenario!!.onActivity { imported = it.formState.canEdit && it.formState.content.photos.size == count + 1 }
            imported
        }
        assertPhotoPreviews(count + 1)
    }

    private fun enterFields() {
        setText("form_container", "CSQU3054383")
        setText("form_iso", "45G1")
        click(scrollToTag("form_type_receipt"))
        setText("form_observations", "Observación pendiente con fotografía")
        scenario!!.onActivity { activity ->
            activity.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
    }

    private fun rotate(requested: Int, expected: Int) {
        scenario!!.onActivity { it.requestedOrientation = requested }
        await("orientación real $expected") {
            var orientation = Configuration.ORIENTATION_UNDEFINED
            scenario!!.onActivity { orientation = it.resources.configuration.orientation }
            orientation == expected
        }
        awaitReady()
    }

    private fun assertPending(expected: FormContent, saved: FormContent) {
        scenario!!.onActivity { activity ->
            assertEquals(expected, activity.formState.content)
            assertEquals(saved, activity.formState.lastSavedContent)
            assertTrue(activity.formState.hasUnsavedChanges)
            expected.photos.forEach { assertTrue(activity.photoStore.exists(it)) }
        }
        assertField("form_container", expected.containerIdentifier)
        assertField("form_iso", expected.isoCode)
        assertField("form_observations", expected.observations)
        assertPhotoPreviews(expected.photos.size)
    }

    private fun showExitWarning() {
        click(awaitVisibleTag("form_back"))
        await("diálogo de cambios sin guardar") {
            nodes().any { it.isVisibleToUser && it.text?.toString() == context.getString(R.string.dialog_unsaved_changes_title) }
        }
        assertEquals(0, PhotoFormTestActivity.backCalls.get())
    }

    private fun assertPhotoPreviews(expected: Int) {
        val firstPhotoId = content().photos.firstOrNull()?.id
        if (firstPhotoId != null) scrollToTag("photo_remove_$firstPhotoId") else scrollToTag("form_photos")
        await("$expected miniaturas accesibles") {
            nodes().count { it.isVisibleToUser && it.contentDescription?.toString() == "Fotografía del contenedor" } == expected
        }
    }

    private fun assertField(tag: String, expected: String) {
        scrollToTag(tag)
        await("contenido accesible de $tag") {
            findTag(tag)?.let { node -> subtree(node).any { it.text?.toString() == expected } } == true
        }
    }

    private fun setText(tag: String, text: String) {
        val root = scrollToTag(tag)
        val editable = subtree(root).firstOrNull { node ->
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        } ?: error("Campo $tag sin acción de edición. ${dumpTree()}")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        assertTrue("No se pudo editar $tag", editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        assertField(tag, text)
    }

    private fun clickText(text: String) {
        var matched: AccessibilityNodeInfo? = null
        await("botón $text") {
            matched = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == text }
            matched != null
        }
        click(requireNotNull(matched))
    }

    private fun click(node: AccessibilityNodeInfo) {
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && clickable.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
            clickable = clickable.parent
        }
        assertNotNull("Nodo sin acción de pulsar", clickable)
        assertTrue("Acción de pulsar rechazada", clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        settleAccessibility()
    }

    private fun scrollToTag(tag: String): AccessibilityNodeInfo {
        releaseInputFocus()
        settleAccessibility()
        findTag(tag)?.takeIf { it.isVisibleToUser }?.let { return it }
        val desiredOrder = formOrder(tag) ?: 0
        val visibleOrders = nodes().filter { it.isVisibleToUser }.mapNotNull { formOrder(it.viewIdResourceName) }
        var forward = visibleOrders.minOrNull()?.let { desiredOrder > it } ?: true
        var unchangedMoves = 0
        repeat(18) {
            findTag(tag)?.takeIf { it.isVisibleToUser }?.let { return it }
            val before = viewportSignature()
            if (!scrollVertical(forward)) {
                settleAccessibility()
                findTag(tag)?.takeIf { it.isVisibleToUser }?.let { return it }
                forward = !forward
            } else {
                findTag(tag)?.takeIf { it.isVisibleToUser }?.let { return it }
                unchangedMoves = if (before == viewportSignature()) unchangedMoves + 1 else 0
                if (unchangedMoves >= 2) {
                    // A physical edge was reached. Search the opposite direction rather than assuming absence.
                    forward = !forward
                    unchangedMoves = 0
                }
            }
        }
        return awaitVisibleTag(tag)
    }

    private fun awaitVisibleTag(tag: String): AccessibilityNodeInfo {
        var matched: AccessibilityNodeInfo? = null
        await("$tag visible") {
            matched = findTag(tag)?.takeIf { it.isVisibleToUser }
            matched != null
        }
        // Return the snapshot that satisfied the condition; a second root query can cross a window frame.
        return requireNotNull(matched)
    }

    private fun scrollVertical(forward: Boolean): Boolean {
        settleAccessibility()
        // Compose may transiently omit accessibility SCROLL_* actions or isScrollable at a range edge.
        // A real touch gesture uses the visible vertical viewport and does not depend on those flags.
        val visible = nodes().filter { it.isVisibleToUser }
        val scroll = visible.firstOrNull { it.className?.toString() == "android.widget.ScrollView" }
            ?: visible.filter { it.isScrollable }
                .maxByOrNull { Rect().apply { it.getBoundsInScreen(this) }.height() }
            ?: return false
        val bounds = Rect().apply { scroll.getBoundsInScreen(this) }
        if (bounds.width() < 20 || bounds.height() < 80) return false
        // The form's outer padding avoids touching editable text, photo thumbnails or action buttons.
        val x = bounds.left + bounds.width() * 0.02f
        val upper = bounds.top + bounds.height() * 0.20f
        val lower = bounds.top + bounds.height() * 0.80f
        val start = if (forward) lower else upper
        val end = if (forward) upper else lower
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float): Boolean {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            return try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                automation.injectInputEvent(event, true)
            } finally {
                event.recycle()
            }
        }
        if (!send(MotionEvent.ACTION_DOWN, start)) return false
        var injected = true
        for (step in 1..16) {
            awaitRenderFrame()
            injected = send(MotionEvent.ACTION_MOVE, start + (end - start) * step / 16f) && injected
        }
        // End at rest so a high-velocity fling cannot skip a small field or chip.
        repeat(4) {
            awaitRenderFrame()
            injected = send(MotionEvent.ACTION_MOVE, end) && injected
        }
        injected = send(MotionEvent.ACTION_UP, end) && injected
        settleAccessibility()
        return injected
    }

    private fun awaitRenderFrame() {
        val frame = CountDownLatch(1)
        instrumentation.runOnMainSync {
            Choreographer.getInstance().postFrameCallback { frame.countDown() }
        }
        assertTrue("No llegó el siguiente frame del gesto", frame.await(1, TimeUnit.SECONDS))
    }

    private fun releaseInputFocus() {
        nodes().filter { it.isFocused }.forEach { node ->
            if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLEAR_FOCUS }) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
            }
        }
        scenario!!.onActivity { activity ->
            activity.window.decorView.clearFocus()
            activity.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
    }

    private fun formOrder(tag: String?): Int? = when {
        tag == "form_photos" || tag?.startsWith("photo_") == true -> 0
        tag == "form_container" -> 1
        tag == "form_iso" -> 2
        tag?.startsWith("form_type_") == true -> 3
        tag == "form_datetime" -> 4
        tag == "form_observations" -> 5
        tag == "form_save" -> 6
        else -> null
    }

    private fun viewportSignature(): String = nodes().filter {
        it.isVisibleToUser && formOrder(it.viewIdResourceName) != null
    }.joinToString("|") { node ->
        val bounds = Rect().apply { node.getBoundsInScreen(this) }
        "${node.viewIdResourceName}:$bounds"
    }

    private fun settleAccessibility() {
        instrumentation.waitForIdleSync()
        try {
            // Main-thread idle alone does not include Compose animation or accessibility semantics frames.
            automation.waitForIdle(150L, 3_000L)
        } catch (_: TimeoutException) {
            // An ongoing snackbar/progress event must not block the condition-based checks below.
        }
        instrumentation.waitForIdleSync()
        accessibilityEvents.drainPermits()
    }

    private fun findTag(tag: String): AccessibilityNodeInfo? = nodes().firstOrNull {
        it.viewIdResourceName == tag || it.viewIdResourceName?.endsWith(":id/$tag") == true
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val active = automation.rootInActiveWindow
        // Dialog/snackbar/IME transitions may temporarily change the active root. Query only this app,
        // using all distinct app windows so an empty transitional root cannot hide the form underneath.
        val roots = buildList {
            active?.takeIf { it.packageName?.toString() == context.packageName }?.let { add(it) }
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

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            // Wake on accessibility events; the bounded poll also observes coroutine/SQLite state.
            accessibilityEvents.tryAcquire(150, TimeUnit.MILLISECONDS)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Tiempo agotado: $label. ${dumpTree()}")
    }

    private fun dumpTree(): String = nodes().joinToString(" | ") {
        val bounds = Rect().apply { it.getBoundsInScreen(this) }
        "${it.className}[${it.viewIdResourceName}] text=${it.text} description=${it.contentDescription}" +
            " visible=${it.isVisibleToUser} focused=${it.isFocused} scrollable=${it.isScrollable}" +
            " bounds=$bounds actions=${it.actionList.map { action -> action.id }}"
    }

    private fun content(): FormContent {
        var result: FormContent? = null
        scenario!!.onActivity { result = it.formState.content }
        return requireNotNull(result)
    }

    private fun reference(): FormContent {
        var result: FormContent? = null
        scenario!!.onActivity { result = it.formState.lastSavedContent }
        return requireNotNull(result)
    }

    private fun readDrafts(): List<InspectionDraft> {
        val helper = InspectionDatabaseHelper(context, PhotoFormTestActivity.databaseName(caseId))
        return try {
            runBlocking { SqliteInspectionDraftRepository(helper) { true }.getAllDrafts().getOrThrow() }
        } finally {
            helper.close()
        }
    }

    private fun original(id: String): File {
        require(UUID.fromString(id).toString() == id)
        val file = File(context.cacheDir, "form_photo_ui_test_$caseId/no_backup/inspection_photos/$id.image")
        assertTrue(file.isFile)
        assertNotNull(file.parentFile)
        return file
    }
}
