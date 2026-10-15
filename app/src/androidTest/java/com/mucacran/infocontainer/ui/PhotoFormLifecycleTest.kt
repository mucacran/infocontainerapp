package com.mucacran.infocontainer.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mucacran.infocontainer.R
import com.mucacran.infocontainer.data.InspectionDatabaseHelper
import com.mucacran.infocontainer.data.SqliteInspectionDraftRepository
import com.mucacran.infocontainer.model.InspectionDraft
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity/Compose/SQLite/private PNG integration, confined to a fresh UUID fixture per test. */
@RunWith(AndroidJUnit4::class)
class PhotoFormLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var caseId: String
    private var scenario: ActivityScenario<PhotoFormTestActivity>? = null

    @Before
    fun setUp() {
        caseId = UUID.randomUUID().toString()
        PhotoFormTestActivity.backCalls.set(0)
    }

    @After
    fun tearDown() {
        scenario?.close()
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
        compose.onNodeWithText(context.getString(R.string.btn_cancel)).performClick()
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
        compose.onNodeWithText(context.getString(R.string.btn_discard)).performClick()
        compose.waitUntil(10_000) { PhotoFormTestActivity.backCalls.get() == 1 }
        assertEquals(saved, readDrafts().single())
        assertArrayEquals(bytes, persistedOriginal.readBytes())

        scenario!!.close()
        launch(existing = true)
        assertEquals(saved.photos, content().photos)
        assertEquals(saved.observations, content().observations)
        scenario!!.onActivity { assertFalse(it.formState.hasUnsavedChanges) }
        compose.onAllNodesWithContentDescription("Fotografía del contenedor").assertCountEquals(1)
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

        compose.onNodeWithTag("form_save").performScrollTo().performClick()
        compose.waitUntil(10_000) {
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
        compose.onAllNodesWithContentDescription("Fotografía del contenedor").assertCountEquals(1)
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
        compose.waitUntil(10_000) {
            var ready = false
            scenario!!.onActivity { ready = it.formState.canEdit }
            ready
        }
        compose.onNodeWithTag("form_photos").assertExists()
    }

    private fun enterChangesAndAttachPhoto() {
        compose.onNodeWithTag("form_container").performScrollTo().performTextReplacement("CSQU3054383")
        compose.onNodeWithTag("form_iso").performScrollTo().performTextReplacement("45G1")
        compose.onNodeWithTag("form_type_receipt").performScrollTo().performClick()
        compose.onNodeWithTag("form_observations").performScrollTo().performTextReplacement("Observación pendiente con fotografía")
        closeSoftKeyboard()
        val count = content().photos.size
        scenario!!.onActivity { it.attachSyntheticPhoto() }
        compose.waitUntil(10_000) {
            var imported = false
            scenario!!.onActivity { imported = it.formState.canEdit && it.formState.content.photos.size == count + 1 }
            imported
        }
        compose.onNodeWithTag("form_photos").performScrollTo()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Fotografía del contenedor").fetchSemanticsNodes().size == count + 1
        }
    }

    private fun rotate(requested: Int, expected: Int) {
        scenario!!.onActivity { it.requestedOrientation = requested }
        compose.waitUntil(10_000) {
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
        compose.onNodeWithTag("form_container").assertTextContains(expected.containerIdentifier)
        compose.onNodeWithTag("form_iso").assertTextContains(expected.isoCode)
        compose.onNodeWithTag("form_observations").assertTextContains(expected.observations)
        compose.onNodeWithTag("form_photos").performScrollTo()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Fotografía del contenedor").fetchSemanticsNodes().size == expected.photos.size
        }
    }

    private fun showExitWarning() {
        compose.onNodeWithTag("form_back").performClick()
        compose.onNodeWithText(context.getString(R.string.dialog_unsaved_changes_title)).assertIsDisplayed()
        assertEquals(0, PhotoFormTestActivity.backCalls.get())
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
