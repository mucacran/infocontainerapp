package com.mucacran.infocontainer.network

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InterruptedIOException
import java.io.StringReader
import java.net.ProtocolException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fixed-origin HTTPS only, with Android's ordinary trust manager and hostname verification. */
class WordPressPhotoApi internal constructor(
    baseClient: OkHttpClient,
    private val origin: HttpUrl,
    uploadTimeoutMillis: Long = 60_000,
    readTimeoutMillis: Long = 20_000
) : PhotoApi, TestInspectionApi {
    constructor() : this(OkHttpClient(), WordPressConfig.BASE_URL.toHttpUrl())

    override suspend fun createTestInspection(username: String, applicationPassword: String): CreatedTestInspection {
        credentialsValid(username, applicationPassword)
        val suffix = java.time.Instant.now().epochSecond.toString().takeLast(6).padStart(6, '0')
        val base = "MSCU${suffix}"
        val identifier = base + requireNotNull(com.mucacran.infocontainer.util.ContainerValidator.calculateIso6346CheckDigit(base))
        val containerBody = "{\"identifier\":\"$identifier\",\"iso_code\":\"22G1\"}".toRequestBody("application/json".toMediaType())
        val container = execute(request(username, applicationPassword, "containers", containerBody), client) { response ->
            successOrThrow(response, setOf(200, 201)); parseCreatedContainer(readJson(response), identifier)
        }
        val inspectionBody = "{\"inspection_type\":\"receipt\",\"inspected_at\":\"${java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0)}\",\"observations\":\"Prueba sintética InfoContainer Android\"}".toRequestBody("application/json".toMediaType())
        return execute(request(username, applicationPassword, "containers/${container.first}/inspections", inspectionBody), client) { response ->
            successOrThrow(response, setOf(200, 201)); CreatedTestInspection(container.first, identifier, parseId(readJson(response), "id"))
        }
    }

    private val client: OkHttpClient
    private val uploadClient: OkHttpClient

    init {
        require(origin.isHttps && origin.encodedPath == "/" && origin.query == null &&
            origin.fragment == null && origin.username.isEmpty() && origin.password.isEmpty())
        client = baseClient.newBuilder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cache(null)
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val guard = request.tag(ExchangeGuard::class.java)
                if (guard == null || request.url != guard.url || request.method != guard.method ||
                    request.url.scheme != origin.scheme || request.url.host != origin.host ||
                    request.url.port != origin.port) {
                    throw ProtocolException("Photo exchange blocked")
                }
                // OkHttp can follow 503 Retry-After:0 even with retryOnConnectionFailure(false).
                if (!guard.sent.compareAndSet(false, true)) throw IOException("Photo retry blocked")
                chain.proceed(request)
            }
            .build()
        uploadClient = client.newBuilder()
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(uploadTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
    }

    override suspend fun upload(
        username: String, applicationPassword: String, inspectionId: Long, payload: UploadPhotoPayload
    ): RemotePhoto {
        credentialsValid(username, applicationPassword)
        positiveId(inspectionId)
        validatePayload(payload)
        // Hash on IO before opening a socket. The streaming request body checks it again.
        withContext(Dispatchers.IO) {
            if (!payload.file.isFile || payload.file.length() != payload.byteSize) {
                fail(PhotoTransferFailure.LOCAL_FILE_CHANGED)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                payload.file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        size += count
                        if (size > payload.byteSize) fail(PhotoTransferFailure.LOCAL_FILE_CHANGED)
                        digest.update(buffer, 0, count)
                    }
                    if (size != payload.byteSize || digest.digest().hex() != payload.sha256.lowercase()) {
                        fail(PhotoTransferFailure.LOCAL_FILE_CHANGED)
                    }
                }
            } catch (_: IOException) {
                fail(PhotoTransferFailure.LOCAL_FILE_CHANGED)
            }
        }
        val extension = when (payload.mimeType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            else -> "webp"
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "photo.$extension", VerifiedFileBody(payload))
            .addFormDataPart("client_photo_id", payload.clientPhotoId)
            .addFormDataPart("category", payload.category)
            .apply {
                payload.description?.let { addFormDataPart("description", it) }
                payload.capturedAt?.let { addFormDataPart("captured_at", it) }
            }.build()
        val request = request(username, applicationPassword, "inspections/$inspectionId/photos", multipart)
        return execute(request, uploadClient) { response ->
            successOrThrow(response, setOf(200, 201))
            val remote = parseMetadata(readJson(response))
            if (remote.inspectionId != inspectionId ||
                !remote.clientPhotoId.equals(payload.clientPhotoId, ignoreCase = true) ||
                remote.category != payload.category || remote.mimeType != payload.mimeType ||
                remote.byteSize != payload.byteSize || !remote.sha256.equals(payload.sha256, ignoreCase = true)) {
                fail(PhotoTransferFailure.INTEGRITY_MISMATCH)
            }
            remote
        }
    }

    override suspend fun list(
        username: String, applicationPassword: String, inspectionId: Long
    ): List<RemotePhoto> {
        positiveId(inspectionId)
        val request = request(username, applicationPassword, "inspections/$inspectionId/photos")
        return execute(request, client) { response ->
            successOrThrow(response, setOf(200))
            parseList(readJson(response)).also { photos ->
                if (photos.any { it.inspectionId != inspectionId } ||
                    photos.map { it.id }.distinct().size != photos.size ||
                    photos.map { it.clientPhotoId.lowercase() }.distinct().size != photos.size) {
                    fail(PhotoTransferFailure.INVALID_RESPONSE)
                }
            }
        }
    }

    override suspend fun metadata(
        username: String, applicationPassword: String, photoId: Long
    ): RemotePhoto {
        positiveId(photoId)
        return execute(request(username, applicationPassword, "photos/$photoId"), client) { response ->
            successOrThrow(response, setOf(200))
            parseMetadata(readJson(response)).also {
                if (it.id != photoId) fail(PhotoTransferFailure.INVALID_RESPONSE)
            }
        }
    }

    override suspend fun downloadVerified(
        username: String, applicationPassword: String, photo: RemotePhoto
    ): VerifiedPhotoDownload {
        validateRemote(photo)
        val request = request(username, applicationPassword, "photos/${photo.id}/file")
            .newBuilder().header("Accept", photo.mimeType).header("Accept-Encoding", "identity").build()
        return execute(request, uploadClient) { response ->
            successOrThrow(response, setOf(200))
            val body = response.body ?: fail(PhotoTransferFailure.INVALID_RESPONSE)
            if (body.contentLength() != -1L && body.contentLength() != photo.byteSize) {
                fail(PhotoTransferFailure.INTEGRITY_MISMATCH)
            }
            val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
            if (mime != photo.mimeType) fail(PhotoTransferFailure.INVALID_RESPONSE)
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    size += count
                    if (size > photo.byteSize) fail(PhotoTransferFailure.INTEGRITY_MISMATCH)
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().hex()
            if (size != photo.byteSize || hash != photo.sha256.lowercase()) {
                fail(PhotoTransferFailure.INTEGRITY_MISMATCH)
            }
            VerifiedPhotoDownload(photo.id, size, hash)
        }
    }

    private fun request(
        username: String, password: String, path: String, body: RequestBody? = null
    ): Request {
        credentialsValid(username, password)
        val url = requireNotNull(origin.resolve("$NAMESPACE/$path"))
        val method = if (body == null) "GET" else "POST"
        return Request.Builder().url(url).method(method, body)
            .header("Accept", "application/json")
            .header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
            .tag(ExchangeGuard::class.java, ExchangeGuard(url, method))
            .build()
    }

    private suspend fun <T> execute(request: Request, httpClient: OkHttpClient, parse: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(networkFailure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        continuation.resume(response.use(parse))
                    } catch (e: PhotoTransferException) {
                        continuation.resumeWithException(e)
                    } catch (e: IOException) {
                        continuation.resumeWithException(networkFailure(e))
                    } catch (_: IllegalStateException) {
                        continuation.resumeWithException(PhotoTransferException(PhotoTransferFailure.INVALID_RESPONSE))
                    }
                }
            })
        }

    private fun successOrThrow(response: Response, accepted: Set<Int>) {
        if (response.code in accepted) return
        // Only an allowlisted REST code is retained. No arbitrary server message is exposed.
        val code = try { parseErrorCode(readJson(response)) } catch (_: Exception) { null }
        val reason = when (response.code) {
            401 -> PhotoTransferFailure.CREDENTIALS_REJECTED
            403 -> PhotoTransferFailure.PERMISSION_DENIED
            404 -> PhotoTransferFailure.NOT_FOUND
            409 -> if (code == "infocontainer_photo_retry_conflict") PhotoTransferFailure.RETRY_CONFLICT
                else PhotoTransferFailure.NOT_EDITABLE
            413 -> PhotoTransferFailure.TOO_LARGE
            415 -> PhotoTransferFailure.UNSUPPORTED_FORMAT
            408, 504 -> PhotoTransferFailure.TIMEOUT
            in 300..399 -> PhotoTransferFailure.REDIRECT_BLOCKED
            in 500..599 -> PhotoTransferFailure.UNAVAILABLE
            400 -> PhotoTransferFailure.INVALID_REQUEST
            else -> PhotoTransferFailure.INVALID_RESPONSE
        }
        throw PhotoTransferException(reason, response.code, code)
    }

    private fun readJson(response: Response): String {
        val body = response.body ?: fail(PhotoTransferFailure.INVALID_RESPONSE)
        if (body.contentLength() > MAX_JSON_BYTES) fail(PhotoTransferFailure.INVALID_RESPONSE)
        val source = body.source()
        if (source.request(MAX_JSON_BYTES + 1)) fail(PhotoTransferFailure.INVALID_RESPONSE)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(source.readByteArray())).toString().removePrefix("\uFEFF")
        } catch (_: CharacterCodingException) {
            fail(PhotoTransferFailure.INVALID_RESPONSE)
        }
    }

    private data class ExchangeGuard(val url: HttpUrl, val method: String, val sent: AtomicBoolean = AtomicBoolean())

    private class LocalFileChangedException : IOException("Local file changed")

    private class VerifiedFileBody(private val payload: UploadPhotoPayload) : RequestBody() {
        override fun contentType() = payload.mimeType.toMediaType()
        override fun contentLength() = payload.byteSize
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            try {
                payload.file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        size += count
                        if (size > payload.byteSize) throw LocalFileChangedException()
                        digest.update(buffer, 0, count)
                        sink.write(buffer, 0, count)
                    }
                }
            } catch (_: java.io.FileNotFoundException) {
                throw LocalFileChangedException()
            }
            if (size != payload.byteSize || digest.digest().hex() != payload.sha256.lowercase()) {
                throw LocalFileChangedException()
            }
        }
    }

    companion object {
        const val MAX_PHOTO_BYTES = 10L * 1024 * 1024
        val CATEGORIES = setOf("identifier", "technical_plate", "doors", "front", "left_side",
            "right_side", "interior", "floor", "damage", "other")
        val MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        private const val NAMESPACE = "/wp-json/infocontainer/v1"
        private const val MAX_JSON_BYTES = 1024L * 1024
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val HASH_PATTERN = Regex("[0-9a-fA-F]{64}")
        private val CAPTURE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})")
        private val ERROR_CODES = setOf("infocontainer_photo_retry_conflict", "infocontainer_inspection_not_editable")

        private fun credentialsValid(username: String, password: String) {
            if (username.isBlank() || ':' in username || password.isBlank() ||
                username.any { it == '\r' || it == '\n' } || password.any { it == '\r' || it == '\n' }) {
                fail(PhotoTransferFailure.CREDENTIALS_REJECTED)
            }
        }

        private fun positiveId(id: Long) { if (id <= 0) fail(PhotoTransferFailure.INVALID_REQUEST) }

        private fun validatePayload(payload: UploadPhotoPayload) {
            if (!UUID_PATTERN.matches(payload.clientPhotoId) || !HASH_PATTERN.matches(payload.sha256) ||
                payload.category !in CATEGORIES || payload.byteSize <= 0) fail(PhotoTransferFailure.INVALID_REQUEST)
            if (payload.byteSize > MAX_PHOTO_BYTES) fail(PhotoTransferFailure.TOO_LARGE)
            if (payload.mimeType !in MIME_TYPES) fail(PhotoTransferFailure.UNSUPPORTED_FORMAT)
            payload.description?.let {
                if (it.codePointCount(0, it.length) > 2000) fail(PhotoTransferFailure.INVALID_REQUEST)
            }
            payload.capturedAt?.let {
                if (!CAPTURE_PATTERN.matches(it)) fail(PhotoTransferFailure.INVALID_REQUEST)
                try { OffsetDateTime.parse(it) } catch (_: DateTimeParseException) {
                    fail(PhotoTransferFailure.INVALID_REQUEST)
                }
            }
        }

        private fun validateRemote(photo: RemotePhoto) {
            if (photo.id <= 0 || photo.inspectionId <= 0 || !UUID_PATTERN.matches(photo.clientPhotoId) ||
                !HASH_PATTERN.matches(photo.sha256) || photo.category !in CATEGORIES ||
                photo.mimeType !in MIME_TYPES || photo.byteSize !in 1..MAX_PHOTO_BYTES) {
                fail(PhotoTransferFailure.INVALID_RESPONSE)
            }
        }

        private fun <T> jsonDocument(json: String, parse: (JsonReader) -> T): T = try {
            JsonReader(StringReader(json)).use { reader ->
                reader.strictness = Strictness.STRICT
                val result = parse(reader)
                if (reader.peek() != JsonToken.END_DOCUMENT) fail(PhotoTransferFailure.INVALID_RESPONSE)
                result
            }
        } catch (_: IOException) {
            fail(PhotoTransferFailure.INVALID_RESPONSE)
        } catch (_: IllegalStateException) {
            fail(PhotoTransferFailure.INVALID_RESPONSE)
        } catch (_: NumberFormatException) {
            fail(PhotoTransferFailure.INVALID_RESPONSE)
        }

        private fun parseMetadata(json: String) = jsonDocument(json, ::readPhoto)

        private fun parseCreatedContainer(json: String, expected: String): Pair<Long, String> = jsonDocument(json) { reader ->
            if (reader.peek() != JsonToken.BEGIN_OBJECT) fail(PhotoTransferFailure.INVALID_RESPONSE)
            reader.beginObject(); var id: Long? = null; var identifier: String? = null
            while (reader.hasNext()) when (val n = reader.nextName()) {
                "id" -> { if (reader.peek() != JsonToken.NUMBER) fail(PhotoTransferFailure.INVALID_RESPONSE); id = reader.nextLong() }
                "identifier" -> { if (reader.peek() != JsonToken.STRING) fail(PhotoTransferFailure.INVALID_RESPONSE); identifier = reader.nextString() }
                else -> skipValue(reader, 0)
            }
            reader.endObject(); val resultId = id ?: fail(PhotoTransferFailure.INVALID_RESPONSE)
            val resultIdentifier = identifier ?: expected
            if (!resultIdentifier.equals(expected, true) || !com.mucacran.infocontainer.util.ContainerValidator.validateContainerIdentifier(resultIdentifier).isValid) fail(PhotoTransferFailure.INVALID_RESPONSE)
            resultId to resultIdentifier
        }

        private fun parseId(json: String, field: String): Long = jsonDocument(json) { reader ->
            if (reader.peek() != JsonToken.BEGIN_OBJECT) fail(PhotoTransferFailure.INVALID_RESPONSE)
            reader.beginObject(); var id: Long? = null
            while (reader.hasNext()) when (val n = reader.nextName()) {
                field -> { if (reader.peek() != JsonToken.NUMBER) fail(PhotoTransferFailure.INVALID_RESPONSE); id = reader.nextLong() }
                else -> skipValue(reader, 0)
            }
            reader.endObject(); id?.takeIf { it > 0 } ?: fail(PhotoTransferFailure.INVALID_RESPONSE)
        }

        private fun parseList(json: String): List<RemotePhoto> = jsonDocument(json) { reader ->
            if (reader.peek() != JsonToken.BEGIN_ARRAY) fail(PhotoTransferFailure.INVALID_RESPONSE)
            reader.beginArray()
            val result = mutableListOf<RemotePhoto>()
            while (reader.hasNext()) {
                if (result.size >= 1000) fail(PhotoTransferFailure.INVALID_RESPONSE)
                result += readPhoto(reader)
            }
            reader.endArray()
            result
        }

        private fun readPhoto(reader: JsonReader): RemotePhoto {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) fail(PhotoTransferFailure.INVALID_RESPONSE)
            reader.beginObject()
            val fields = mutableMapOf<String, String>()
            val names = mutableSetOf<String>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (!names.add(name)) fail(PhotoTransferFailure.INVALID_RESPONSE)
                when (name) {
                    "id", "inspection_id", "byte_size" -> {
                        if (reader.peek() != JsonToken.NUMBER) fail(PhotoTransferFailure.INVALID_RESPONSE)
                        fields[name] = reader.nextString()
                    }
                    "client_photo_id", "category", "mime_type", "sha256" -> {
                        if (reader.peek() != JsonToken.STRING) fail(PhotoTransferFailure.INVALID_RESPONSE)
                        fields[name] = reader.nextString()
                    }
                    else -> skipValue(reader, 0)
                }
            }
            reader.endObject()
            fun field(name: String) = fields[name] ?: fail(PhotoTransferFailure.INVALID_RESPONSE)
            fun number(name: String) = field(name).toLongOrNull() ?: fail(PhotoTransferFailure.INVALID_RESPONSE)
            return RemotePhoto(number("id"), number("inspection_id"), field("client_photo_id"),
                field("category"), field("mime_type"), number("byte_size"), field("sha256")).also(::validateRemote)
        }

        private fun parseErrorCode(json: String): String? = jsonDocument(json) { reader ->
            if (reader.peek() != JsonToken.BEGIN_OBJECT) fail(PhotoTransferFailure.INVALID_RESPONSE)
            reader.beginObject()
            val names = mutableSetOf<String>()
            var code: String? = null
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (!names.add(name)) fail(PhotoTransferFailure.INVALID_RESPONSE)
                if (name == "code" && reader.peek() == JsonToken.STRING) {
                    code = reader.nextString().takeIf { it in ERROR_CODES }
                } else skipValue(reader, 0)
            }
            reader.endObject()
            code
        }

        private fun skipValue(reader: JsonReader, depth: Int) {
            if (depth > 64) fail(PhotoTransferFailure.INVALID_RESPONSE)
            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) skipValue(reader, depth + 1)
                    reader.endArray()
                }
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    val names = mutableSetOf<String>()
                    while (reader.hasNext()) {
                        if (!names.add(reader.nextName())) fail(PhotoTransferFailure.INVALID_RESPONSE)
                        skipValue(reader, depth + 1)
                    }
                    reader.endObject()
                }
                JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> reader.nextNull()
                else -> fail(PhotoTransferFailure.INVALID_RESPONSE)
            }
        }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun fail(reason: PhotoTransferFailure): Nothing = throw PhotoTransferException(reason)
        private fun networkFailure(error: IOException) = PhotoTransferException(when (error) {
            is LocalFileChangedException -> PhotoTransferFailure.LOCAL_FILE_CHANGED
            is SSLException -> PhotoTransferFailure.TLS_FAILURE
            is InterruptedIOException -> PhotoTransferFailure.TIMEOUT
            is ProtocolException -> PhotoTransferFailure.INVALID_RESPONSE
            else -> PhotoTransferFailure.UNAVAILABLE
        })
    }
}
