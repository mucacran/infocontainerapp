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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestName
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.InetAddress

/** Simulated HTTPS on loopback only. Never connects to the hosted WordPress installation. */
class WordPressConnectionProbeTest {
    @get:Rule val testName = TestName()
    private lateinit var server: MockWebServer
    private lateinit var trustedClient: OkHttpClient

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        // The synthetic CA is confined to this test client; production uses Android defaults.
        trustedClient = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
    }

    @After
    fun tearDown() {
        println("Simulated loopback HTTP requests in ${testName.methodName}: ${server.requestCount}")
        server.shutdown()
        trustedClient.connectionPool.evictAll()
        trustedClient.dispatcher.executorService.shutdown()
    }

    @Test
    fun emptyArrayIsSuccessAndOnlyExpectedAuthenticatedGetIsSent() = runBlocking {
        val callingThread = Thread.currentThread()
        var networkOnCallingThread = true
        val observerClient = trustedClient.newBuilder().addNetworkInterceptor { chain ->
            networkOnCallingThread = Thread.currentThread() == callingThread
            chain.proceed(chain.request())
        }.build()
        server.enqueue(MockResponse().setBody("[]"))
        val result = probe(observerClient).check(TEST_USERNAME, TEST_PASSWORD)
        assertEquals(ConnectionResult.SUCCESS, result)
        assertFalse(networkOnCallingThread)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals(WordPressConfig.PROBE_PATH, request.path)
        assertEquals(Credentials.basic(TEST_USERNAME, TEST_PASSWORD, Charsets.UTF_8), request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals(0L, request.bodySize)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun nonEmptyJsonArrayIsSuccess() = runBlocking {
        server.enqueue(MockResponse().setBody("[{\"id\":17}]"))
        assertEquals(ConnectionResult.SUCCESS, check())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun incompatibleAndMalformedResponsesAreInvalid() = runBlocking {
        val responses = listOf(
            "", "{}", "null", "true", "123", "\"[]\"", "<html>login</html>",
            "[", "[1,]", "[NaN]", "[01]", "[/* comment */1]", "['single']",
            "[foo]", "[{\"id\":}]", "[] {}", "[] trailing", "[\"line\nfeed\"]",
            "[TRUE]", "[NULL]", "[1.]", "[{\"a\":1;\"b\":2}]", "[\"\\q\"]", "[\"\\uZZZZ\"]"
        )
        for ((index, body) in responses.withIndex()) {
            server.enqueue(MockResponse().setBody(body))
            assertEquals("Fixture $index must be rejected", ConnectionResult.INVALID_RESPONSE, check())
        }
        assertEquals(responses.size, server.requestCount)
    }

    @Test
    fun malformedUtf8AndOversizedResponsesAreInvalid() = runBlocking {
        val invalidUtf8 = Buffer().write(byteArrayOf(91, 34, 0xC3.toByte(), 34, 93))
        server.enqueue(MockResponse().setBody(invalidUtf8))
        assertEquals(ConnectionResult.INVALID_RESPONSE, check())
        server.enqueue(MockResponse().setBody("[\"" + "a".repeat(65_536) + "\"]"))
        assertEquals(ConnectionResult.INVALID_RESPONSE, check())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun unauthorizedAndForbiddenHaveDistinctResultsWithoutRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=fixture"))
        assertEquals(ConnectionResult.CREDENTIALS_REJECTED, check())
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(ConnectionResult.PERMISSION_DENIED, check())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun sameOriginAndCrossOriginAndCleartextRedirectsAreNeverFollowed() = runBlocking {
        val recipient = MockWebServer()
        recipient.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val targets = listOf(
                origin().resolve("/unexpected").toString(),
                recipient.url("/must-not-receive-credentials").newBuilder().scheme("https").build().toString(),
                recipient.url("/must-not-receive-credentials").toString()
            )
            for ((index, code) in listOf(301, 302, 303, 307, 308).withIndex()) {
                server.enqueue(MockResponse().setResponseCode(code).addHeader("Location", targets[index % targets.size]))
                assertEquals(ConnectionResult.INVALID_RESPONSE, check())
            }
            assertEquals(5, server.requestCount)
            assertEquals(0, recipient.requestCount)
            repeat(5) { assertEquals(WordPressConfig.PROBE_PATH, server.takeRequest().path) }
        } finally {
            recipient.shutdown()
        }
    }

    @Test
    fun retryAfterZeroAndRequestTimeoutCannotSendAnotherRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
        assertEquals(ConnectionResult.UNAVAILABLE, check())
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(408).addHeader("Retry-After", "0"))
        assertEquals(ConnectionResult.TIMEOUT, check())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun connectionFailureIsUnavailableAndDoesNotRetry() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(ConnectionResult.UNAVAILABLE, check())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun totalTimeoutIsReportedWithoutRetry() = runBlocking {
        server.enqueue(MockResponse().setBody("[]").setBodyDelay(2, TimeUnit.SECONDS))
        assertEquals(ConnectionResult.TIMEOUT, probe(timeoutMillis = 250).check(TEST_USERNAME, TEST_PASSWORD))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cancellationStopsThePendingCheckWithoutAnotherRequest() = runBlocking {
        server.enqueue(MockResponse().setBody("[]").setBodyDelay(2, TimeUnit.SECONDS))
        val completed = CountDownLatch(1)
        val call = async(start = CoroutineStart.UNDISPATCHED) {
            probe().check(TEST_USERNAME, TEST_PASSWORD).also { completed.countDown() }
        }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        call.cancelAndJoin()
        assertTrue(call.isCancelled)
        assertEquals(1L, completed.count)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unknownCertificateIsTlsFailureAndSendsNoHttpCredentials() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals(ConnectionResult.TLS_FAILURE, probe(OkHttpClient()).check(TEST_USERNAME, TEST_PASSWORD))
        assertNull(server.takeRequest(250, TimeUnit.MILLISECONDS))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun wrongHostnameIsTlsFailureEvenWithTrustedCertificate() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        val otherHostname = origin().newBuilder().host("127.0.0.1").build()
        val probe = WordPressConnectionProbe(trustedClient, otherHostname)
        assertEquals(ConnectionResult.TLS_FAILURE, probe.check(TEST_USERNAME, TEST_PASSWORD))
        assertNull(server.takeRequest(250, TimeUnit.MILLISECONDS))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cleartextOriginIsRejectedBeforeCredentialsCanBeSent() {
        val origin = origin().newBuilder().scheme("http").build()
        try {
            WordPressConnectionProbe(trustedClient, origin)
            throw AssertionError("HTTP must be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, server.requestCount)
        }
    }

    private fun probe(client: OkHttpClient = trustedClient, timeoutMillis: Long = 20_000) =
        WordPressConnectionProbe(client, origin(), timeoutMillis)

    private fun origin() = server.url("/").newBuilder().host("localhost").build()

    private suspend fun check() = probe().check(TEST_USERNAME, TEST_PASSWORD)

    companion object {
        // Synthetic fixture strings only; never real WordPress credentials.
        private const val TEST_USERNAME = "fixture_operator"
        private const val TEST_PASSWORD = "synthetic_fixture_password"
    }
}
