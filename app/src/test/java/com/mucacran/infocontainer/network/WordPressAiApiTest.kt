package com.mucacran.infocontainer.network

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Synthetic HTTPS on local loopback only. No hosted or provider endpoints are contacted. */
class WordPressAiApiTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var trustedClient: OkHttpClient
    private lateinit var png: File

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        trustedClient = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
        png = folder.newFile("private.image").apply {
            writeBytes(byteArrayOf(
                0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4
            ))
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        trustedClient.connectionPool.evictAll()
        trustedClient.dispatcher.executorService.shutdown()
    }

    @Test
    fun identificationMultipartContainsOneCorrectlyTypedImageAndStableUuid() = runBlocking {
        val original = png.readBytes()
        server.enqueue(json(201, runJson(proposal = proposalJson())))
        val result = api().identify(USER, PASSWORD, png, UUID)
        assertEquals("completed", result.run.status)
        assertEquals("TSTU4855933", result.proposal?.visibleIdentifierText)
        assertEquals("22G1", result.proposal?.isoCode)
        assertEquals(true, result.proposal?.candidates?.single()?.iso6346Valid)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(API + "/ai/identifications", request.path)
        assertEquals(Credentials.basic(USER, PASSWORD, Charsets.UTF_8),
            request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val body = request.body.readUtf8()
        assertEquals(1, Regex("name=\"file\"").findAll(body).count())
        assertEquals(1, Regex("name=\"client_request_id\"").findAll(body).count())
        assertTrue(body.contains("filename=\"identification.png\""))
        assertTrue(body.contains("Content-Type: image/png"))
        assertTrue(body.contains(UUID))
        assertFalse(body.contains("inspection_id"))
        assertEquals(1, server.requestCount)
        assertTrue(original.contentEquals(png.readBytes()))
    }

    @Test
    fun jpegPrivateFileIsSentWithJpegExtensionAndMime() = runBlocking {
        val jpeg = folder.newFile("another-private.image").apply {
            writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 1, 2))
        }
        server.enqueue(json(201, runJson(proposal = proposalJson())))
        api().identify(USER, PASSWORD, jpeg, UUID)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("filename=\"identification.jpg\""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
    }

    @Test
    fun processingFailedAndUnknownRunsWithoutProposalCanBeReadByGetWithoutAnotherPost() = runBlocking {
        server.enqueue(json(201, runJson(status = "processing", proposal = "null")))
        val pending = api().identify(USER, PASSWORD, png, UUID)
        assertEquals("processing", pending.run.status)
        assertNull(pending.proposal)
        server.enqueue(json(200, runJson(status = "failed", proposal = "null",
            error = "\"infocontainer_ai_provider_error\"")))
        val failed = api().getIdentificationRun(USER, PASSWORD, 101)
        assertEquals("failed", failed.run.status)
        assertEquals("infocontainer_ai_provider_error", failed.run.error)
        assertNull(failed.proposal)
        server.enqueue(json(200, runJson(status = "unknown", proposal = "null")))
        val unknown = api().getIdentificationRun(USER, PASSWORD, 101)
        assertEquals("unknown", unknown.run.status)
        assertNull(unknown.proposal)
        server.enqueue(json(200, runJson(proposal = proposalJson())))
        val completed = api().getIdentificationRun(USER, PASSWORD, 101)
        assertEquals("completed", completed.run.status)
        assertEquals("TSTU4855933", completed.proposal?.candidates?.single()?.value)
        assertEquals("POST", server.takeRequest().method)
        repeat(3) {
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals(API + "/ai/runs/101", request.path)
            assertEquals(Credentials.basic(USER, PASSWORD, Charsets.UTF_8),
                request.getHeader("Authorization"))
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun incompatibleCompletedResponsesAreNeverSuccess() = runBlocking {
        val invalid = listOf(
            "[]", "{}", "null", "<html>login</html>", runJson(proposal = "null"),
            runJson(proposal = proposalJson().replace(
                "{\"value\":\"TSTU4855933\",\"iso_6346_valid\":true}",
                "\"TSTU4855933\"")),
            runJson(requestId = "00000000-0000-4000-8000-000000000000",
                proposal = proposalJson()),
            runJson(proposal = proposalJson()) + " trailing"
        )
        for ((index, body) in invalid.withIndex()) {
            server.enqueue(json(201, body))
            try {
                api().identify(USER, PASSWORD, png, UUID)
                throw AssertionError("Invalid fixture " + index + " was accepted")
            } catch (_: AiInvalidException) {
                // A completed proposal must have the public WordPress structure.
            }
        }
        assertEquals(invalid.size, server.requestCount)
    }

    @Test
    fun documentedHttpFailuresExposeOnlyStatusAndAllowlistedCode() = runBlocking {
        val cases = listOf(
            401 to "infocontainer_authentication_required",
            403 to "infocontainer_forbidden",
            409 to "infocontainer_ai_idempotency_conflict",
            429 to "infocontainer_ai_quota",
            502 to "infocontainer_ai_provider_error",
            503 to "infocontainer_ai_not_configured",
            504 to "infocontainer_ai_timeout_unknown"
        )
        for ((status, code) in cases) {
            server.enqueue(MockResponse().setResponseCode(status)
                .setBody("{\"code\":\"$code\",\"message\":\"private server detail\",\"data\":{\"status\":$status}}"))
            try {
                api().identify(USER, PASSWORD, png, UUID)
                throw AssertionError("Expected HTTP " + status)
            } catch (e: AiHttpException) {
                assertEquals(status, e.status)
                assertEquals(code, e.wpCode)
                assertFalse(e.toString().contains("private server detail"))
                assertFalse(e.toString().contains(PASSWORD))
            }
        }
        assertEquals(cases.size, server.requestCount)
    }

    @Test
    fun reviewUsesDocumentedJsonAndParsesPublicConfirmation() = runBlocking {
        val reviewId = "550e8400-e29b-41d4-a716-446655440002"
        for ((action, content) in listOf(
            "accepted" to null,
            "edited" to mapOf("identifier" to "TSTU4855933", "iso_code" to "22G1"),
            "rejected" to "La fotografía no permite confirmar el resultado."
        )) {
            val one = """{"id":501,"run_id":101,"client_review_id":"$reviewId","action":"$action"}"""
            server.enqueue(json(201, """{"review":$one,"history":[$one]}"""))
            val result = api().review(USER, PASSWORD, 101, reviewId, action, content, null)
            assertEquals(action, result.review.action)
            assertEquals(1, result.history.size)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(API + "/ai/runs/101/reviews", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"client_review_id\":\"$reviewId\""))
            assertTrue(body.contains("\"action\":\"$action\""))
            if (action == "edited") {
                assertTrue(body.contains("\"identifier\":\"TSTU4855933\""))
                assertTrue(body.contains("\"iso_code\":\"22G1\""))
            }
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun redirectsAndRetryAfterZeroNeverSendASecondExchange() = runBlocking {
        val recipient = MockWebServer()
        recipient.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(MockResponse().setResponseCode(307)
                .addHeader("Location", recipient.url("/credentials-must-not-arrive")))
            try {
                api().identify(USER, PASSWORD, png, UUID)
                throw AssertionError("Redirect accepted")
            } catch (e: AiHttpException) {
                assertEquals(307, e.status)
            }
            assertEquals(1, server.requestCount)
            assertEquals(0, recipient.requestCount)
            server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
            try {
                api().identify(USER, PASSWORD, png, UUID)
                throw AssertionError("Retry-After accepted")
            } catch (_: IOException) {
                assertEquals(2, server.requestCount)
            }
            assertEquals(0, recipient.requestCount)
        } finally {
            recipient.shutdown()
        }
    }

    @Test
    fun timeoutDoesNotAutomaticallyRetryOrChangeOriginal() = runBlocking {
        val original = png.readBytes()
        server.enqueue(json(201, runJson(proposal = proposalJson()))
            .setBodyDelay(1, TimeUnit.SECONDS))
        try {
            api(timeoutMillis = 150).identify(USER, PASSWORD, png, UUID)
            throw AssertionError("Timeout expected")
        } catch (_: IOException) {
            assertEquals(1, server.requestCount)
        }
        assertTrue(original.contentEquals(png.readBytes()))
    }

    @Test
    fun invalidInputAndInsecureOriginsAreRejectedBeforeAnyHttpRequest() = runBlocking {
        val webp = folder.newFile("webp.image").apply { writeBytes("RIFFxxxxWEBP".toByteArray()) }
        for (image in listOf(webp, folder.newFile("empty.image"))) {
            try {
                api().identify(USER, PASSWORD, image, UUID)
                throw AssertionError("Image must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        try {
            api().identify(USER, PASSWORD, png, "bad-uuid")
            throw AssertionError("UUID must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            api().identify("bad:user", PASSWORD, png, UUID)
            throw AssertionError("Username must be rejected")
        } catch (_: IllegalArgumentException) { }
        for (origin in listOf(
            origin().newBuilder().scheme("http").build(),
            origin().newBuilder().username("embedded").build(),
            origin().newBuilder().encodedPath("/unexpected").build()
        )) {
            try {
                WordPressAiApi(trustedClient, origin)
                throw AssertionError("Insecure origin accepted")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun invalidTlsCertificateAndHostnameNeverSendApplicationCredentials() = runBlocking {
        server.enqueue(json(201, runJson(proposal = proposalJson())))
        try {
            WordPressAiApi(OkHttpClient(), origin()).identify(USER, PASSWORD, png, UUID)
            throw AssertionError("Untrusted certificate accepted")
        } catch (_: IOException) { }
        try {
            WordPressAiApi(trustedClient, origin().newBuilder().host("127.0.0.1").build())
                .identify(USER, PASSWORD, png, UUID)
            throw AssertionError("Wrong hostname accepted")
        } catch (_: IOException) { }
        assertEquals(0, server.requestCount)
    }

    private fun api(timeoutMillis: Long = 120_000) =
        WordPressAiApi(trustedClient, origin(), timeoutMillis)
    private fun origin() = server.url("/").newBuilder().host("localhost").build()
    private fun json(status: Int, body: String) =
        MockResponse().setResponseCode(status).setBody(body)
    private fun proposalJson() =
        """{"visible_identifier_text":"TSTU4855933","candidates":[{"value":"TSTU4855933","iso_6346_valid":true}],"iso_code":"22G1","legibility":"clear","warnings":[]}"""
    private fun runJson(
        status: String = "completed", requestId: String = UUID,
        proposal: String, error: String = "null"
    ) = """{"id":101,"operation":"identification","inspection_id":null,"client_request_id":"$requestId","status":"$status","proposal":$proposal,"error":$error,"stale":false,"human_confirmation_required":true}"""

    companion object {
        private const val USER = "synthetic_operator"
        private const val PASSWORD = "synthetic_application_password"
        private const val UUID = "550e8400-e29b-41d4-a716-446655440000"
        private const val API = "/wp-json/infocontainer/v1"
    }
}
