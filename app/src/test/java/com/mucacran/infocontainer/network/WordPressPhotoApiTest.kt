package com.mucacran.infocontainer.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestName
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Every request stays on synthetic loopback HTTPS. No hosted WordPress or OpenAI access. */
class WordPressPhotoApiTest {
    @get:Rule val folder = TemporaryFolder()
    @get:Rule val testName = TestName()
    private lateinit var server: MockWebServer
    private lateinit var trustedClient: OkHttpClient
    private lateinit var payload: UploadPhotoPayload
    private val bytes = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="
    )

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        // This CA is confined to tests, never used by the Android production constructor.
        trustedClient = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
        val file = folder.newFile("synthetic-original.png").apply { writeBytes(bytes) }
        payload = UploadPhotoPayload(file, UUID, "other", "image/png", bytes.size.toLong(), hash(bytes))
    }

    @After
    fun tearDown() {
        println("Simulated photo loopback HTTP requests in ${testName.methodName}: ${server.requestCount}")
        server.shutdown()
        trustedClient.connectionPool.evictAll()
        trustedClient.dispatcher.executorService.shutdown()
    }

    @Test
    fun createsSyntheticContainerAndDraftInspectionWithTwoExplicitPosts() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{\"id\":55}"))
        server.enqueue(MockResponse().setResponseCode(201).setBody("{\"id\":77,\"status\":\"draft\"}"))
        val created = api().createTestInspection(USER, PASSWORD)
        assertEquals(55L, created.containerId)
        assertEquals(77L, created.inspectionId)
        assertTrue(com.mucacran.infocontainer.util.ContainerValidator.validateContainerIdentifier(created.containerIdentifier).isValid)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("/wp-json/infocontainer/v1/containers", first.path)
        assertEquals("POST", second.method)
        assertEquals("/wp-json/infocontainer/v1/containers/55/inspections", second.path)
    }

    @Test
    fun multipartCreatesPhotoWithExactlyRequiredFieldsAndPreservesOriginal() = runBlocking {
        var networkOnCallingThread = true
        val callingThread = Thread.currentThread()
        val observer = trustedClient.newBuilder().addNetworkInterceptor { chain ->
            networkOnCallingThread = Thread.currentThread() == callingThread
            chain.proceed(chain.request())
        }.build()
        server.enqueue(jsonResponse(201))
        val result = api(observer).upload(USER, PASSWORD, INSPECTION_ID, payload)
        assertEquals(remote(), result)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("$API/inspections/$INSPECTION_ID/photos", request.path)
        assertEquals(Credentials.basic(USER, PASSWORD, Charsets.UTF_8), request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val body = request.body.readByteArray()
        val text = body.toString(Charsets.ISO_8859_1)
        assertEquals(1, Regex("name=\"file\"").findAll(text).count())
        assertEquals(1, Regex("name=\"client_photo_id\"").findAll(text).count())
        assertEquals(1, Regex("name=\"category\"").findAll(text).count())
        assertFalse(text.contains("name=\"description\""))
        assertFalse(text.contains("name=\"captured_at\""))
        assertTrue(text.contains("filename=\"photo.png\""))
        assertFalse(text.contains(payload.file.absolutePath))
        assertTrue(text.contains("Content-Type: image/png"))
        assertTrue(text.contains("\r\n\r\n$UUID\r\n"))
        assertTrue(text.contains("\r\n\r\nother\r\n"))
        assertTrue(body.toList().windowed(bytes.size).any { it == bytes.toList() })
        assertArrayEquals(bytes, payload.file.readBytes())
        assertFalse(networkOnCallingThread)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun optionalMultipartFieldsAreSentOnlyWhenProvided() = runBlocking {
        server.enqueue(jsonResponse(201))
        val optional = payload.copy(description = "Imagen sintética", capturedAt = "2026-10-17T12:34:56-05:00")
        assertEquals(remote(), api().upload(USER, PASSWORD, INSPECTION_ID, optional))
        val body = server.takeRequest().body.readUtf8()
        assertEquals(1, Regex("name=\"description\"").findAll(body).count())
        assertEquals(1, Regex("name=\"captured_at\"").findAll(body).count())
        assertTrue(body.contains("Imagen sintética"))
        assertTrue(body.contains("2026-10-17T12:34:56-05:00"))
    }

    @Test
    fun explicitRetryUsesSameUuidAndAccepts200WithoutChangingPayload() = runBlocking {
        server.enqueue(jsonResponse(201))
        server.enqueue(jsonResponse(200))
        assertEquals(remote(), upload())
        assertEquals(remote(), upload())
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        for (body in listOf(first, second)) {
            assertTrue(body.contains("\r\n\r\n$UUID\r\n"))
            assertTrue(body.contains("name=\"file\"; filename=\"photo.png\""))
        }
        assertArrayEquals(bytes, payload.file.readBytes())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun timeoutHasNoAutomaticRetryAndExplicitRetryKeepsUuid() = runBlocking {
        server.enqueue(jsonResponse(201).setBodyDelay(1, TimeUnit.SECONDS))
        failure(PhotoTransferFailure.TIMEOUT) {
            api(uploadTimeout = 150).upload(USER, PASSWORD, INSPECTION_ID, payload)
        }
        assertEquals(1, server.requestCount)
        server.enqueue(jsonResponse(200))
        assertEquals(remote(), upload())
        repeat(2) { assertTrue(server.takeRequest().body.readUtf8().contains("\r\n\r\n$UUID\r\n")) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun conflictCodesDistinguishPayloadConflictAndNonDraftInspection() = runBlocking {
        for ((code, expected) in listOf(
            "infocontainer_photo_retry_conflict" to PhotoTransferFailure.RETRY_CONFLICT,
            "infocontainer_inspection_not_editable" to PhotoTransferFailure.NOT_EDITABLE
        )) {
            server.enqueue(MockResponse().setResponseCode(409)
                .setBody("{\"code\":\"$code\",\"message\":\"private server detail\",\"data\":{\"status\":409}}"))
            val error = failure(expected) { upload() }
            assertEquals(409, error.httpCode)
            assertEquals(code, error.wpCode)
            assertFalse(error.toString().contains("private server detail"))
            assertNull(error.cause)
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun httpFailuresAreDistinctAndNeverRetainServerMessages() = runBlocking {
        val cases = listOf(
            400 to PhotoTransferFailure.INVALID_REQUEST,
            401 to PhotoTransferFailure.CREDENTIALS_REJECTED,
            403 to PhotoTransferFailure.PERMISSION_DENIED,
            404 to PhotoTransferFailure.NOT_FOUND,
            413 to PhotoTransferFailure.TOO_LARGE,
            415 to PhotoTransferFailure.UNSUPPORTED_FORMAT,
            500 to PhotoTransferFailure.UNAVAILABLE,
            503 to PhotoTransferFailure.UNAVAILABLE
        )
        for ((status, expected) in cases) {
            server.enqueue(MockResponse().setResponseCode(status)
                .addHeader("WWW-Authenticate", "Basic realm=fixture")
                .setBody("{\"code\":\"untrusted_arbitrary_code\",\"message\":\"private detail\"}"))
            val error = failure(expected) { upload() }
            assertEquals(status, error.httpCode)
            assertNull(error.wpCode)
            assertFalse(error.toString().contains("private detail"))
        }
        assertEquals(cases.size, server.requestCount)
    }

    @Test
    fun redirectsNeverForwardPostOrCredentialsToAnyDestination() = runBlocking {
        val recipient = MockWebServer()
        recipient.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val targets = listOf(
                origin().resolve("/unexpected").toString(),
                recipient.url("/must-not-receive-credentials").newBuilder().scheme("https").build().toString(),
                recipient.url("/must-not-receive-credentials").toString()
            )
            for ((index, status) in listOf(301, 302, 303, 307, 308).withIndex()) {
                server.enqueue(MockResponse().setResponseCode(status).addHeader("Location", targets[index % targets.size]))
                failure(PhotoTransferFailure.REDIRECT_BLOCKED) { upload() }
            }
            assertEquals(5, server.requestCount)
            assertEquals(0, recipient.requestCount)
            repeat(5) { assertEquals("$API/inspections/$INSPECTION_ID/photos", server.takeRequest().path) }
        } finally { recipient.shutdown() }
    }

    @Test
    fun retryAfterZeroOnPostAndGetNeverCausesSecondExchange() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
        failure(PhotoTransferFailure.UNAVAILABLE) { upload() }
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
        failure(PhotoTransferFailure.UNAVAILABLE) { api().list(USER, PASSWORD, INSPECTION_ID) }
        assertEquals(2, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(408).addHeader("Retry-After", "0"))
        failure(PhotoTransferFailure.TIMEOUT) { api().list(USER, PASSWORD, INSPECTION_ID) }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun invalidSuccessfulUploadResponsesCannotBeReportedAsSuccess() = runBlocking {
        val valid = json()
        val invalid = listOf("", "[]", "null", "{}", "<html>login</html>", "{", "$valid {}",
            valid.replace("\"id\":17", "\"id\":\"17\""),
            valid.replace("\"id\":17", "\"id\":17.0"),
            valid.replace("\"id\":17", "\"id\":-1"),
            valid.replace("\"id\":17", "\"id\":17,\"id\":18"),
            valid.replace("\"category\":\"other\"", "\"category\":\"unknown\""),
            valid.replace(UUID, "not-a-uuid"), valid.replace(hash(bytes), "invalid-hash"),
            valid.dropLast(1) + ",\"extra\":[1,]}",
            valid.dropLast(1) + ",\"extra\":[\"bad\\q\"]}",
            valid.dropLast(1) + ",\"extra\":" + "[".repeat(66) + "0" + "]".repeat(66) + "}")
        for ((index, body) in invalid.withIndex()) {
            server.enqueue(MockResponse().setResponseCode(201).setBody(body))
            try { failure(PhotoTransferFailure.INVALID_RESPONSE) { upload() } }
            catch (e: AssertionError) { throw AssertionError("Invalid response fixture $index", e) }
        }
        assertEquals(invalid.size, server.requestCount)
    }

    @Test
    fun uploadChecksRemoteAssociationUuidCategoryMimeSizeAndHash() = runBlocking {
        val valid = json()
        val incompatible = listOf(
            valid.replace("\"inspection_id\":31", "\"inspection_id\":32"),
            valid.replace(UUID, "cc6bded0-a539-4f83-bbe4-123456789abc"),
            valid.replace("\"category\":\"other\"", "\"category\":\"damage\""),
            valid.replace("image/png", "image/jpeg"),
            valid.replace("\"byte_size\":${bytes.size}", "\"byte_size\":${bytes.size + 1}"),
            valid.replace(hash(bytes), "0".repeat(64))
        )
        for (body in incompatible) {
            server.enqueue(MockResponse().setResponseCode(201).setBody(body))
            failure(PhotoTransferFailure.INTEGRITY_MISMATCH) { upload() }
        }
        assertEquals(incompatible.size, server.requestCount)
    }

    @Test
    fun invalidUtf8AndOversizedJsonAreRejected() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(Buffer().write(byteArrayOf(123, 34, 0xC3.toByte(), 34, 125))))
        failure(PhotoTransferFailure.INVALID_RESPONSE) { upload() }
        server.enqueue(MockResponse().setResponseCode(201).setBody(" ".repeat(1_048_577)))
        failure(PhotoTransferFailure.INVALID_RESPONSE) { upload() }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun metadataAndEmptyOrNonEmptyListUseOnlyAuthenticatedGetRoutes() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals(emptyList<RemotePhoto>(), api().list(USER, PASSWORD, INSPECTION_ID))
        server.enqueue(MockResponse().setBody("[${json()}]"))
        assertEquals(listOf(remote()), api().list(USER, PASSWORD, INSPECTION_ID))
        server.enqueue(jsonResponse(200))
        assertEquals(remote(), api().metadata(USER, PASSWORD, PHOTO_ID))
        val routes = listOf("$API/inspections/$INSPECTION_ID/photos", "$API/inspections/$INSPECTION_ID/photos", "$API/photos/$PHOTO_ID")
        for (route in routes) {
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals(route, request.path)
            assertEquals(0L, request.bodySize)
            assertEquals(Credentials.basic(USER, PASSWORD, Charsets.UTF_8), request.getHeader("Authorization"))
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun listingRejectsMismatchedInspectionDuplicateIdsAndDuplicateClientUuids() = runBlocking {
        val invalid = listOf(
            "[${json().replace("\"inspection_id\":31", "\"inspection_id\":32")}]",
            "[${json()},${json()}]",
            "[${json()},${json().replace("\"id\":17", "\"id\":18")}]",
            "{}", "[] trailing", "[null]"
        )
        for (body in invalid) {
            server.enqueue(MockResponse().setBody(body))
            failure(PhotoTransferFailure.INVALID_RESPONSE) { api().list(USER, PASSWORD, INSPECTION_ID) }
        }
        server.enqueue(MockResponse().setBody(json().replace("\"id\":17", "\"id\":18")))
        failure(PhotoTransferFailure.INVALID_RESPONSE) { api().metadata(USER, PASSWORD, PHOTO_ID) }
        assertEquals(invalid.size + 1, server.requestCount)
    }

    @Test
    fun downloadHashesAuthenticatedOriginalBytesWithoutChangingOrCreatingFiles() = runBlocking {
        val originalFiles = folder.root.listFiles()!!.map { it.name }.sorted()
        server.enqueue(MockResponse().addHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
        val result = api().downloadVerified(USER, PASSWORD, remote())
        assertEquals(VerifiedPhotoDownload(PHOTO_ID, bytes.size.toLong(), hash(bytes)), result)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("$API/photos/$PHOTO_ID/file", request.path)
        assertEquals(Credentials.basic(USER, PASSWORD, Charsets.UTF_8), request.getHeader("Authorization"))
        assertEquals("image/png", request.getHeader("Accept"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertEquals(originalFiles, folder.root.listFiles()!!.map { it.name }.sorted())
        assertArrayEquals(bytes, payload.file.readBytes())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun downloadRejectsWrongHashSizeAndMime() = runBlocking {
        val wrong = bytes.clone().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        server.enqueue(MockResponse().addHeader("Content-Type", "image/png").setBody(Buffer().write(wrong)))
        failure(PhotoTransferFailure.INTEGRITY_MISMATCH) { api().downloadVerified(USER, PASSWORD, remote()) }
        server.enqueue(MockResponse().addHeader("Content-Type", "image/png").setBody(Buffer().write(bytes.copyOf(bytes.size - 1))))
        failure(PhotoTransferFailure.INTEGRITY_MISMATCH) { api().downloadVerified(USER, PASSWORD, remote()) }
        server.enqueue(MockResponse().addHeader("Content-Type", "text/html").setBody(Buffer().write(bytes)))
        failure(PhotoTransferFailure.INVALID_RESPONSE) { api().downloadVerified(USER, PASSWORD, remote()) }
        server.enqueue(MockResponse().addHeader("Content-Type", "image/png").setChunkedBody(Buffer().write(bytes + byteArrayOf(1)), 8))
        failure(PhotoTransferFailure.INTEGRITY_MISMATCH) { api().downloadVerified(USER, PASSWORD, remote()) }
        assertArrayEquals(bytes, payload.file.readBytes())
        assertEquals(4, server.requestCount)
    }

    @Test
    fun invalidCredentialsIdsAndPayloadNeverOpenSocket() = runBlocking {
        failure(PhotoTransferFailure.CREDENTIALS_REJECTED) { api().upload("bad:user", PASSWORD, INSPECTION_ID, payload) }
        failure(PhotoTransferFailure.CREDENTIALS_REJECTED) { api().list(USER, "", INSPECTION_ID) }
        failure(PhotoTransferFailure.INVALID_REQUEST) { api().upload(USER, PASSWORD, 0, payload) }
        failure(PhotoTransferFailure.INVALID_REQUEST) { api().metadata(USER, PASSWORD, -1) }
        for (bad in listOf(payload.copy(clientPhotoId = "1-2-3-4-5"), payload.copy(category = "bad"),
            payload.copy(sha256 = "bad"), payload.copy(description = "a".repeat(2001)),
            payload.copy(capturedAt = "2026-10-17T12:34Z"), payload.copy(capturedAt = "2026-02-30T12:34:56Z"))) {
            failure(PhotoTransferFailure.INVALID_REQUEST) { api().upload(USER, PASSWORD, INSPECTION_ID, bad) }
        }
        failure(PhotoTransferFailure.TOO_LARGE) {
            api().upload(USER, PASSWORD, INSPECTION_ID, payload.copy(byteSize = WordPressPhotoApi.MAX_PHOTO_BYTES + 1))
        }
        failure(PhotoTransferFailure.UNSUPPORTED_FORMAT) {
            api().upload(USER, PASSWORD, INSPECTION_ID, payload.copy(mimeType = "image/gif"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun alteredOriginalIsRejectedBeforePostWithoutOverwritingIt() = runBlocking {
        val altered = bytes.clone().apply { this[0] = 0 }
        payload.file.writeBytes(altered)
        failure(PhotoTransferFailure.LOCAL_FILE_CHANGED) { upload() }
        assertArrayEquals(altered, payload.file.readBytes())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun disconnectDoesNotRetryAndCancellationDoesNotCompletePendingAction() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        failure(PhotoTransferFailure.UNAVAILABLE) { upload() }
        assertEquals(1, server.requestCount)
        server.takeRequest()
        server.enqueue(MockResponse().setBody("[]").setBodyDelay(1, TimeUnit.SECONDS))
        var completed = false
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            api().list(USER, PASSWORD, INSPECTION_ID).also { completed = true }
        }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertFalse(completed)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun invalidTlsCertificateAndHostnameCannotSendHttpCredentials() = runBlocking {
        server.enqueue(jsonResponse(201))
        failure(PhotoTransferFailure.TLS_FAILURE) { api(OkHttpClient()).upload(USER, PASSWORD, INSPECTION_ID, payload) }
        failure(PhotoTransferFailure.TLS_FAILURE) {
            WordPressPhotoApi(trustedClient, origin().newBuilder().host("127.0.0.1").build())
                .upload(USER, PASSWORD, INSPECTION_ID, payload)
        }
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cleartextOriginAndEmbeddedCredentialConfigurationAreRejected() {
        for (bad in listOf(origin().newBuilder().scheme("http").build(),
            origin().newBuilder().username("synthetic-config-user").build(),
            origin().newBuilder().encodedPath("/unexpected").build())) {
            try {
                WordPressPhotoApi(trustedClient, bad)
                throw AssertionError("Invalid origin must be rejected")
            } catch (_: IllegalArgumentException) { assertEquals(0, server.requestCount) }
        }
    }

    private fun api(
        client: OkHttpClient = trustedClient, uploadTimeout: Long = 60_000
    ) = WordPressPhotoApi(client, origin(), uploadTimeout)

    private fun origin() = server.url("/").newBuilder().host("localhost").build()
    private suspend fun upload() = api().upload(USER, PASSWORD, INSPECTION_ID, payload)
    private fun remote() = RemotePhoto(PHOTO_ID, INSPECTION_ID, UUID, "other", "image/png", bytes.size.toLong(), hash(bytes))
    private fun jsonResponse(status: Int) = MockResponse().setResponseCode(status).setBody(json())
    private fun json() = """{"id":17,"inspection_id":31,"client_photo_id":"$UUID","category":"other","mime_type":"image/png","byte_size":${bytes.size},"sha256":"${hash(bytes)}"}"""
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private suspend fun failure(expected: PhotoTransferFailure, action: suspend () -> Any?): PhotoTransferException {
        try { action() } catch (e: PhotoTransferException) {
            assertEquals(expected, e.reason)
            return e
        }
        throw AssertionError("Expected $expected")
    }

    companion object {
        private const val USER = "fixture_operator"
        private const val PASSWORD = "synthetic_photo_fixture_password"
        private const val UUID = "a87c2f91-62ac-4b67-ace3-23456789abcd"
        private const val INSPECTION_ID = 31L
        private const val PHOTO_ID = 17L
        private const val API = "/wp-json/infocontainer/v1"
    }
}
