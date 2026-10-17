package com.mucacran.infocontainer.network

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.io.StringReader
import java.net.ProtocolException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/**
 * One read-only request. The default client retains Android's trust manager and hostname verifier.
 * It has no disk cache, credential storage, logging interceptor or automatic authentication.
 */
class WordPressConnectionProbe internal constructor(
    baseClient: OkHttpClient,
    origin: HttpUrl,
    callTimeoutMillis: Long = 20_000
) : ConnectionProbe {
    constructor() : this(OkHttpClient(), WordPressConfig.BASE_URL.toHttpUrl())

    private val endpoint: HttpUrl
    private val client: OkHttpClient

    init {
        require(origin.isHttps && origin.encodedPath == "/" && origin.query == null &&
            origin.fragment == null && origin.username.isEmpty() && origin.password.isEmpty())
        endpoint = requireNotNull(origin.resolve(WordPressConfig.PROBE_PATH))
        client = baseClient.newBuilder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cache(null)
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                // Also blocks OkHttp's 503 Retry-After:0 / HTTP 421 follow-up exchanges.
                // A new request gets its own guard; a second exchange never sends credentials.
                val exchange = request.tag(AtomicBoolean::class.java)
                if (request.url != endpoint || request.method != "GET" || exchange == null) {
                    throw ProtocolException("Probe exchange blocked")
                }
                if (!exchange.compareAndSet(false, true)) {
                    throw IOException("Automatic probe retry blocked")
                }
                chain.proceed(request)
            }
            .build()
    }

    override suspend fun check(username: String, applicationPassword: String): ConnectionResult {
        if (username.isBlank() || ':' in username || applicationPassword.isBlank()) {
            return ConnectionResult.CREDENTIALS_REJECTED
        }
        val request = Request.Builder()
            .url(endpoint)
            .get()
            .header("Accept", "application/json")
            .header("Authorization", Credentials.basic(username, applicationPassword, Charsets.UTF_8))
            .tag(AtomicBoolean::class.java, AtomicBoolean(false))
            .build()
        // enqueue performs network and response parsing on OkHttp workers, never on the UI thread.
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(failureResult(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use(::responseResult)
                    } catch (e: IOException) {
                        failureResult(e)
                    } catch (_: IllegalStateException) {
                        ConnectionResult.INVALID_RESPONSE
                    }
                    continuation.resume(result)
                }
            })
        }
    }

    private fun responseResult(response: Response): ConnectionResult = when (response.code) {
        200 -> {
            val body = response.body
            if (body == null || body.contentLength() > MAX_RESPONSE_BYTES) {
                ConnectionResult.INVALID_RESPONSE
            } else {
                val source = body.source()
                if (source.request(MAX_RESPONSE_BYTES + 1)) {
                    ConnectionResult.INVALID_RESPONSE
                } else if (isUtf8JsonArray(source.readByteArray())) {
                    ConnectionResult.SUCCESS
                } else {
                    ConnectionResult.INVALID_RESPONSE
                }
            }
        }
        401 -> ConnectionResult.CREDENTIALS_REJECTED
        403 -> ConnectionResult.PERMISSION_DENIED
        408, 504 -> ConnectionResult.TIMEOUT
        in 500..599 -> ConnectionResult.UNAVAILABLE
        else -> ConnectionResult.INVALID_RESPONSE
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 64L * 1024

        internal fun failureResult(error: IOException): ConnectionResult = when (error) {
            is SSLException -> ConnectionResult.TLS_FAILURE
            is InterruptedIOException -> ConnectionResult.TIMEOUT
            is ProtocolException -> ConnectionResult.INVALID_RESPONSE
            else -> ConnectionResult.UNAVAILABLE
        }

        private fun isUtf8JsonArray(bytes: ByteArray): Boolean = try {
            val json = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("\uFEFF")
            isJsonArray(json)
        } catch (_: CharacterCodingException) {
            false
        }

        /** Consume every token, including string contents, and then require end of document. */
        internal fun isJsonArray(json: String): Boolean = try {
            JsonReader(StringReader(json)).use { reader ->
                reader.strictness = Strictness.STRICT
                if (reader.peek() != JsonToken.BEGIN_ARRAY) return false
                reader.beginArray()
                var depth = 1
                while (depth > 0) {
                    when (reader.peek()) {
                        JsonToken.BEGIN_ARRAY -> { reader.beginArray(); depth++ }
                        JsonToken.BEGIN_OBJECT -> { reader.beginObject(); depth++ }
                        JsonToken.END_ARRAY -> { reader.endArray(); depth-- }
                        JsonToken.END_OBJECT -> { reader.endObject(); depth-- }
                        JsonToken.NAME -> reader.nextName()
                        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NULL -> reader.nextNull()
                        JsonToken.END_DOCUMENT -> return false
                    }
                    if (depth > 128) return false
                }
                reader.peek() == JsonToken.END_DOCUMENT
            }
        } catch (_: IOException) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: NumberFormatException) {
            false
        }
    }
}
