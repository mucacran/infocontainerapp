package com.mucacran.infocontainer.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WordPressAiApi internal constructor(private val client: OkHttpClient, private val origin: HttpUrl) : AiApi {
    constructor() : this(OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).build(), WordPressConfig.BASE_URL.toHttpUrl())
    override suspend fun identify(u: String, p: String, file: File, id: String): IdentificationRun = withContext(Dispatchers.IO) {
        requireUuid(id); requireCredentials(u,p); require(file.isFile)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType())).addFormDataPart("client_request_id", id).build()
        parseIdentification(client.newCall(request(u,p,"ai/identifications",body)).execute().use(::readBody))
    }
    override suspend fun analyze(u: String,p:String,inspectionId:Long,id:String,photoIds:List<Long>?):AnalysisRun = withContext(Dispatchers.IO) {
        requireUuid(id); requireCredentials(u,p); require(inspectionId>0); photoIds?.let { require(it.isNotEmpty() && it.size<=12 && it.distinct().size==it.size && it.all { v -> v>0 }) }
        val obj=JsonObject().apply { addProperty("client_request_id",id); photoIds?.let { ids -> add("photo_ids",JsonArray().also { a -> ids.forEach { value -> a.add(value) } }) } }
        parseAnalysis(client.newCall(request(u,p,"inspections/$inspectionId/ai-analyses",obj.toString().toRequestBody("application/json".toMediaType()))).execute().use(::readBody), inspectionId)
    }
    override suspend fun getRun(u:String,p:String,runId:Long):AiRun = withContext(Dispatchers.IO) { parseRun(client.newCall(request(u,p,"ai/runs/$runId")).execute().use(::readBody)) }
    override suspend fun listRuns(u:String,p:String,inspectionId:Long):List<AiRun> = withContext(Dispatchers.IO) { JsonParser.parseString(client.newCall(request(u,p,"inspections/$inspectionId/ai-runs?per_page=100")).execute().use(::readBody)).getAsJsonArray().map { parseRun(it.getAsJsonObject()) } }
    override suspend fun review(u:String,p:String,runId:Long,clientReviewId:String,action:String,reviewedContent:Any?,identificationResolution:String?):AiReviewResponse = withContext(Dispatchers.IO) {
        requireUuid(clientReviewId); require(action in setOf("accepted","edited","rejected")); val o=JsonObject().apply { addProperty("client_review_id",clientReviewId); addProperty("action",action); identificationResolution?.let { addProperty("identification_resolution",it) }; reviewedContent?.let { add("reviewed_content",JsonParser.parseString(com.google.gson.Gson().toJson(it))) } }
        parseReview(client.newCall(request(u,p,"ai/runs/$runId/reviews",o.toString().toRequestBody("application/json".toMediaType()))).execute().use(::readBody))
    }
    private fun request(u:String,p:String,path:String,body:okhttp3.RequestBody?=null):Request { requireCredentials(u,p); val url=origin.resolve("/wp-json/infocontainer/v1/$path")!!; return Request.Builder().url(url).method(if(body==null)"GET" else "POST",body).header("Accept","application/json").header("Authorization",Credentials.basic(u,p)).build() }
    private fun readBody(r:okhttp3.Response):String { if(r.code !in 200..299) throw AiHttpException(r.code); return r.body?.string() ?: throw AiInvalidException() }
    companion object {
        private fun requireUuid(v:String){ require(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(v)) }
        private fun requireCredentials(u:String,p:String){ require(u.isNotBlank()&&p.isNotBlank()&&!u.contains(':')&&!u.contains('\n')&&!p.contains('\n')) }
        private fun s(o:JsonObject,n:String):String?=o.get(n)?.takeIf{!it.isJsonNull}?.asString
        private fun l(o:JsonObject,n:String):Long=o.get(n).asLong
        private fun parseRun(json:String)=parseRun(JsonParser.parseString(json).asJsonObject)
        private fun parseRun(o:JsonObject):AiRun { val prop=o.get("proposal")?.takeUnless{it.isJsonNull}; return AiRun(l(o,"id"),s(o,"operation")?:throw AiInvalidException(),o.get("inspection_id")?.takeUnless{it.isJsonNull}?.asLong,s(o,"client_request_id")?:throw AiInvalidException(),s(o,"status")?:throw AiInvalidException(),prop,s(o,"error"),o.get("stale")?.asBoolean?:false,o.get("human_confirmation_required")?.asBoolean?:false) }
        private fun parseIdentification(json:String):IdentificationRun { val o=JsonParser.parseString(json).asJsonObject; val r=parseRun(o); val p=o.getAsJsonObject("proposal")?:throw AiInvalidException(); val c=p.getAsJsonArray("candidates")?.map { val x=it.asJsonObject; IdentificationCandidate(s(x,"value")?:throw AiInvalidException(),x.get("iso_6346_valid")?.asBoolean?:false) }?:emptyList(); return IdentificationRun(r,IdentificationProposal(s(p,"visible_identifier_text"),c,s(p,"iso_code"),s(p,"legibility")?:throw AiInvalidException(),p.getAsJsonArray("warnings")?.map { it.asString }?:emptyList())) }
        private fun parseAnalysis(json: String, inspectionId: Long): AnalysisRun {
            val root = JsonParser.parseString(json).asJsonObject
            val run = parseRun(root)
            require(run.inspectionId == inspectionId)
            val p = root.getAsJsonObject("proposal") ?: throw AiInvalidException()
            fun items(name: String): List<AnalysisItem> = p.getAsJsonArray(name)?.map { element ->
                val x = element.asJsonObject
                AnalysisItem(s(x, "text") ?: "", x.getAsJsonArray("photo_ids")?.map { it.asLong } ?: emptyList())
            } ?: emptyList()
            val readings = p.getAsJsonArray("visible_readings")?.map { element ->
                val x = element.asJsonObject
                VisibleReading(s(x, "kind") ?: throw AiInvalidException(), s(x, "value"), x.getAsJsonArray("photo_ids")?.map { it.asLong } ?: emptyList())
            } ?: emptyList()
            val comparison = p.getAsJsonObject("identifier_comparison")?.let { x -> IdentifierComparison(s(x, "registered_identifier"), x.get("conflict")?.asBoolean ?: false) }
            return AnalysisRun(run, AnalysisProposal(items("photo_observations"), readings, items("visible_damage"), items("needs_review"), p.getAsJsonArray("documented_zones")?.map { it.asString } ?: emptyList(), p.getAsJsonArray("unevaluable_zones")?.map { it.asString } ?: emptyList(), p.getAsJsonArray("analyzed_photo_ids")?.map { it.asLong } ?: emptyList(), p.getAsJsonArray("excluded_photo_ids")?.map { it.asLong } ?: emptyList(), s(p, "summary") ?: throw AiInvalidException(), s(p, "report_draft") ?: throw AiInvalidException(), comparison))
        }
        private fun parseReview(json: String): AiReviewResponse {
            val o = JsonParser.parseString(json).asJsonObject
            fun one(x: JsonObject): AiReview = AiReview(l(x, "id"), l(x, "run_id"), s(x, "client_review_id") ?: throw AiInvalidException(), s(x, "action") ?: throw AiInvalidException())
            return AiReviewResponse(one(o.getAsJsonObject("review")), o.getAsJsonArray("history")?.map { one(it.getAsJsonObject()) } ?: emptyList())
        }
    }
}
class AiHttpException(val status:Int):IOException()
class AiInvalidException:IOException()
