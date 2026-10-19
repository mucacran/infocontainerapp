package com.mucacran.infocontainer.network

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
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
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.ProtocolException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Authenticated WordPress AI transport. The production client retains Android's TLS validation. */
class WordPressAiApi internal constructor(
    baseClient: OkHttpClient,
    private val origin: HttpUrl,
    callTimeoutMillis: Long = 120_000
) : AiApi {
    constructor() : this(OkHttpClient(), WordPressConfig.BASE_URL.toHttpUrl())

    private val client: OkHttpClient

    init {
        require(origin.isHttps && origin.encodedPath == "/" && origin.query == null &&
            origin.fragment == null && origin.username.isEmpty() && origin.password.isEmpty())
        require(callTimeoutMillis > 0)
        client = baseClient.newBuilder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(110, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
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
                    throw ProtocolException("AI exchange blocked")
                }
                // OkHttp can follow Retry-After: 0 even with connection retries disabled.
                if (!guard.sent.compareAndSet(false, true)) throw IOException("Automatic AI retry blocked")
                chain.proceed(request)
            }
            .build()
    }

    override suspend fun identify(
        username: String, password: String, file: File, clientRequestId: String
    ): IdentificationRun {
        requireUuid(clientRequestId)
        requireCredentials(username, password)
        val image = inspectImage(file)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "identification." + image.extension,
                SingleUseFileBody(file, image.byteSize, image.mediaType.toMediaType()))
            .addFormDataPart("client_request_id", clientRequestId)
            .build()
        return execute(request(username, password, "ai/identifications", body), setOf(200, 201)) {
            parseIdentification(it).also { result ->
                if (result.run.clientRequestId != clientRequestId) invalid()
            }
        }
    }

    override suspend fun getIdentificationRun(
        username: String, password: String, runId: Long
    ): IdentificationRun {
        requirePositive(runId)
        return execute(request(username, password, "ai/runs/" + runId), setOf(200)) {
            parseIdentification(it).also { result -> if (result.run.id != runId) invalid() }
        }
    }

    override suspend fun analyze(
        username: String, password: String, inspectionId: Long,
        clientRequestId: String, photoIds: List<Long>?
    ): AnalysisRun {
        requireUuid(clientRequestId)
        requirePositive(inspectionId)
        photoIds?.let {
            require(it.isNotEmpty() && it.size <= 12 && it.distinct().size == it.size && it.all { id -> id > 0 })
        }
        val data = JsonObject().apply {
            addProperty("client_request_id", clientRequestId)
            photoIds?.let { ids ->
                add("photo_ids", JsonArray().apply { ids.forEach { add(it) } })
            }
        }
        val path = "inspections/" + inspectionId + "/ai-analyses"
        return execute(request(username, password, path, jsonBody(data)), setOf(200, 201)) {
            parseAnalysis(it, inspectionId).also { result ->
                if (result.run.clientRequestId != clientRequestId) invalid()
            }
        }
    }

    override suspend fun getRun(username: String, password: String, runId: Long): AiRun {
        requirePositive(runId)
        return execute(request(username, password, "ai/runs/" + runId), setOf(200)) {
            parseRun(objectDocument(it)).also { result -> if (result.id != runId) invalid() }
        }
    }

    override suspend fun listRuns(
        username: String, password: String, inspectionId: Long
    ): List<AiRun> {
        requirePositive(inspectionId)
        val path = "inspections/" + inspectionId + "/ai-runs?per_page=100"
        return execute(request(username, password, path), setOf(200)) { json ->
            val array = document(json).asArray()
            if (array.size() > 100) invalid()
            array.map { parseRun(it.asObject()).also { run ->
                if (run.inspectionId != inspectionId) invalid()
            } }
        }
    }

    override suspend fun review(
        username: String, password: String, runId: Long, clientReviewId: String,
        action: String, reviewedContent: Any?, identificationResolution: String?
    ): AiReviewResponse {
        requirePositive(runId)
        requireUuid(clientReviewId)
        require(action in ACTIONS)
        identificationResolution?.let {
            require(it.isNotBlank() && it.codePointCount(0, it.length) <= 1000)
        }
        val data = JsonObject().apply {
            addProperty("client_review_id", clientReviewId)
            addProperty("action", action)
            reviewedContent?.let { add("reviewed_content", Gson().toJsonTree(it)) }
            identificationResolution?.let { addProperty("identification_resolution", it) }
        }
        return execute(
            request(username, password, "ai/runs/" + runId + "/reviews", jsonBody(data)),
            setOf(200, 201)
        ) {
            parseReview(it).also { response ->
                if (response.review.runId != runId ||
                    response.review.clientReviewId != clientReviewId ||
                    response.review.action != action) invalid()
            }
        }
    }

    private fun request(username: String, password: String, path: String, body: RequestBody? = null): Request {
        requireCredentials(username, password)
        val url = requireNotNull(origin.resolve("/wp-json/infocontainer/v1/" + path))
        val method = if (body == null) "GET" else "POST"
        return Request.Builder().url(url).method(method, body)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store, no-cache")
            .header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
            .tag(ExchangeGuard::class.java, ExchangeGuard(url, method))
            .build()
    }

    private suspend fun <T> execute(
        request: Request, accepted: Set<Int>, parse: (String) -> T
    ): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        if (it.code !in accepted) throw httpFailure(it)
                        parse(readJson(it))
                    }
                    continuation.resume(value)
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                } catch (_: RuntimeException) {
                    continuation.resumeWithException(AiInvalidException())
                }
            }
        })
    }

    private fun httpFailure(response: Response): AiHttpException {
        val code = try {
            val root = objectDocument(readJson(response))
            optionalString(root, "code")?.takeIf { it in REST_ERROR_CODES }
        } catch (_: Exception) {
            null
        }
        return AiHttpException(response.code, code)
    }

    private fun readJson(response: Response): String {
        val body = response.body ?: invalid()
        if (body.contentLength() > MAX_JSON_BYTES) invalid()
        val source = body.source()
        if (source.request(MAX_JSON_BYTES + 1)) invalid()
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(source.readByteArray())).toString().removePrefix("\uFEFF")
        } catch (_: CharacterCodingException) {
            invalid()
        }
    }

    private data class ExchangeGuard(
        val url: HttpUrl, val method: String, val sent: AtomicBoolean = AtomicBoolean(false)
    )

    private data class ImageInfo(val mediaType: String, val extension: String, val byteSize: Long)

    private fun inspectImage(file: File): ImageInfo {
        require(file.isFile)
        val byteSize = file.length()
        require(byteSize in 1..MAX_IMAGE_BYTES)
        val header = ByteArray(8)
        val count = file.inputStream().use { input ->
            var total = 0
            while (total < header.size) {
                val n = input.read(header, total, header.size - total)
                if (n < 0) break
                total += n
            }
            total
        }
        return when {
            count >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() &&
                header[2] == 0xff.toByte() -> ImageInfo("image/jpeg", "jpg", byteSize)
            count == 8 && header.contentEquals(PNG_SIGNATURE) ->
                ImageInfo("image/png", "png", byteSize)
            else -> throw IllegalArgumentException("Only JPEG or PNG may be identified")
        }
    }

    private class SingleUseFileBody(
        private val file: File, private val expectedSize: Long, private val mime: MediaType
    ) : RequestBody() {
        override fun contentType(): MediaType = mime
        override fun contentLength(): Long = expectedSize
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink) {
            var size = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > expectedSize) throw IOException("Image changed before upload")
                    sink.write(buffer, 0, count)
                }
            }
            if (size != expectedSize) throw IOException("Image changed before upload")
        }
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        private const val MAX_JSON_BYTES = 1024L * 1024
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        )
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val INTEGER_PATTERN = Regex("[1-9][0-9]*")
        private val STATUSES = setOf("processing", "completed", "failed", "unknown", "interrupted")
        private val ACTIONS = setOf("accepted", "edited", "rejected")
        private val REST_ERROR_CODES = setOf(
            "infocontainer_ai_invalid_request", "infocontainer_authentication_required",
            "infocontainer_forbidden", "infocontainer_ai_idempotency_conflict",
            "infocontainer_ai_photo_mismatch", "infocontainer_inspection_not_draft",
            "infocontainer_ai_not_completed", "infocontainer_ai_stale",
            "infocontainer_ai_review_conflict", "infocontainer_ai_image_limit",
            "infocontainer_ai_image_budget", "infocontainer_ai_provider_refusal",
            "infocontainer_ai_quota", "infocontainer_ai_internal",
            "infocontainer_ai_image_read", "infocontainer_ai_snapshot_invalid",
            "infocontainer_photo_internal_error", "infocontainer_ai_provider_transport",
            "infocontainer_ai_provider_error", "infocontainer_ai_provider_incomplete",
            "infocontainer_ai_provider_empty", "infocontainer_ai_provider_invalid_json",
            "infocontainer_ai_invalid_output", "infocontainer_ai_not_configured",
            "infocontainer_ai_timeout_unknown"
        )

        private fun requireUuid(value: String) { require(UUID_PATTERN.matches(value)) }
        private fun requirePositive(value: Long) { require(value > 0) }
        private fun requireCredentials(username: String, password: String) {
            require(username.isNotBlank() && password.isNotBlank() && ':' !in username &&
                username.none { it == '\r' || it == '\n' } &&
                password.none { it == '\r' || it == '\n' })
        }
        private fun invalid(): Nothing = throw AiInvalidException()
        private fun jsonBody(data: JsonObject) =
            data.toString().toRequestBody("application/json".toMediaType())

        private fun document(json: String): JsonElement = try {
            JsonReader(StringReader(json)).use { reader ->
                reader.strictness = Strictness.STRICT
                reader.skipValue()
                if (reader.peek() != JsonToken.END_DOCUMENT) invalid()
            }
            JsonParser.parseString(json)
        } catch (_: IOException) {
            invalid()
        } catch (_: RuntimeException) {
            invalid()
        }

        private fun objectDocument(json: String): JsonObject = document(json).asObject()
        private fun JsonElement?.asObject(): JsonObject =
            if (this != null && isJsonObject) asJsonObject else invalid()
        private fun JsonElement?.asArray(): JsonArray =
            if (this != null && isJsonArray) asJsonArray else invalid()
        private fun JsonElement?.asPrimitive(): JsonPrimitive =
            if (this != null && isJsonPrimitive) asJsonPrimitive else invalid()
        private fun requiredString(o: JsonObject, name: String): String =
            optionalString(o, name) ?: invalid()
        private fun optionalString(o: JsonObject, name: String): String? {
            val element = o.get(name) ?: return null
            if (element.isJsonNull) return null
            val primitive = element.asPrimitive()
            if (!primitive.isString) invalid()
            return primitive.asString
        }
        private fun requiredBoolean(o: JsonObject, name: String): Boolean {
            val primitive = o.get(name).asPrimitive()
            if (!primitive.isBoolean) invalid()
            return primitive.asBoolean
        }
        private fun requiredLong(o: JsonObject, name: String): Long {
            val primitive = o.get(name).asPrimitive()
            if (!primitive.isNumber || !INTEGER_PATTERN.matches(primitive.asString)) invalid()
            return primitive.asString.toLongOrNull()?.takeIf { it > 0 } ?: invalid()
        }
        private fun optionalLong(o: JsonObject, name: String): Long? =
            o.get(name)?.takeUnless { it.isJsonNull }?.let { requiredLong(o, name) }
        private fun strings(o: JsonObject, name: String): List<String> =
            o.get(name).asArray().map { element ->
                val primitive = element.asPrimitive()
                if (!primitive.isString) invalid()
                primitive.asString
            }
        private fun longs(o: JsonObject, name: String): List<Long> =
            o.get(name).asArray().map { element ->
                val primitive = element.asPrimitive()
                if (!primitive.isNumber || !INTEGER_PATTERN.matches(primitive.asString)) invalid()
                primitive.asString.toLongOrNull() ?: invalid()
            }

        private fun parseRun(o: JsonObject): AiRun {
            val status = requiredString(o, "status").also { if (it !in STATUSES) invalid() }
            return AiRun(
                requiredLong(o, "id"), requiredString(o, "operation"),
                optionalLong(o, "inspection_id"), requiredString(o, "client_request_id").also(::requireUuid),
                status, o.get("proposal")?.takeUnless { it.isJsonNull },
                errorText(o.get("error")), requiredBoolean(o, "stale"),
                requiredBoolean(o, "human_confirmation_required")
            )
        }

        private fun errorText(error: JsonElement?): String? {
            if (error == null || error.isJsonNull) return null
            if (error.isJsonPrimitive && error.asJsonPrimitive.isString) {
                return error.asString.takeIf { it in REST_ERROR_CODES } ?: "error del servidor"
            }
            if (error.isJsonObject) {
                val code = optionalString(error.asJsonObject, "code")
                return code?.takeIf { it in REST_ERROR_CODES } ?: "error del servidor"
            }
            invalid()
        }

        private fun parseIdentification(json: String): IdentificationRun {
            val root = objectDocument(json)
            val run = parseRun(root)
            if (run.operation != "identification" || run.inspectionId != null) invalid()
            val proposal = root.get("proposal")?.takeUnless { it.isJsonNull }?.let {
                parseIdentificationProposal(it.asObject())
            }
            if (run.status == "completed" && proposal == null) invalid()
            return IdentificationRun(run, proposal)
        }

        private fun parseIdentificationProposal(o: JsonObject): IdentificationProposal {
            val candidates = o.get("candidates").asArray().map { element ->
                val candidate = element.asObject()
                val value = requiredString(candidate, "value")
                if (value.length > 32) invalid()
                IdentificationCandidate(value, requiredBoolean(candidate, "iso_6346_valid"))
            }
            if (candidates.size > 3) invalid()
            val visible = optionalString(o, "visible_identifier_text")
            val iso = optionalString(o, "iso_code")
            val legibility = requiredString(o, "legibility")
            val warnings = strings(o, "warnings")
            if ((visible?.length ?: 0) > 64 || (iso?.length ?: 0) > 16 ||
                legibility !in setOf("clear", "uncertain", "unreadable") ||
                warnings.size > 10 || warnings.any { it.length > 500 } ||
                (legibility == "unreadable" && (visible != null || candidates.isNotEmpty()))) invalid()
            return IdentificationProposal(visible, candidates, iso, legibility, warnings)
        }

        private fun parseAnalysis(json: String, inspectionId: Long): AnalysisRun {
            val root = objectDocument(json)
            val run = parseRun(root)
            if (run.operation != "inspection_analysis" || run.inspectionId != inspectionId) invalid()
            val p = root.get("proposal").asObject()
            fun items(name: String): List<AnalysisItem> = p.get(name).asArray().map { element ->
                val item = element.asObject()
                AnalysisItem(requiredString(item, "text"), longs(item, "photo_ids"))
            }
            val readings = p.get("visible_readings").asArray().map { element ->
                val reading = element.asObject()
                VisibleReading(requiredString(reading, "kind"), optionalString(reading, "value"),
                    longs(reading, "photo_ids"))
            }
            val comparison = p.get("identifier_comparison")?.takeUnless { it.isJsonNull }?.asObject()?.let {
                IdentifierComparison(optionalString(it, "registered_identifier"),
                    requiredBoolean(it, "conflict"))
            }
            return AnalysisRun(run, AnalysisProposal(
                items("photo_observations"), readings, items("visible_damage"),
                items("needs_review"), strings(p, "documented_zones"),
                strings(p, "unevaluable_zones"), longs(p, "analyzed_photo_ids"),
                longs(p, "excluded_photo_ids"), requiredString(p, "summary"),
                requiredString(p, "report_draft"), comparison
            ))
        }

        private fun parseReview(json: String): AiReviewResponse {
            val root = objectDocument(json)
            fun one(o: JsonObject): AiReview {
                val action = requiredString(o, "action")
                if (action !in ACTIONS) invalid()
                return AiReview(requiredLong(o, "id"), requiredLong(o, "run_id"),
                    requiredString(o, "client_review_id").also(::requireUuid), action)
            }
            return AiReviewResponse(one(root.get("review").asObject()),
                root.get("history").asArray().map { one(it.asObject()) })
        }
    }
}

/** The server code is allowlisted; messages and credentials are never retained. */
class AiHttpException(val status: Int, val wpCode: String? = null) : IOException("HTTP " + status)
class AiInvalidException : IOException("Invalid WordPress AI response")
