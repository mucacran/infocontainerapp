package com.mucacran.infocontainer.network

/** Non-secret configuration. Credentials are supplied by the connection screen only. */
object WordPressConfig {
    const val BASE_URL = "https://loinde.com.ec"
    internal const val PROBE_PATH = "/wp-json/infocontainer/v1/containers?per_page=1&_fields=id"
}

/** Deliberately contains neither server bodies nor exception messages or credentials. */
enum class ConnectionResult {
    SUCCESS,
    CREDENTIALS_REJECTED,
    PERMISSION_DENIED,
    TLS_FAILURE,
    UNAVAILABLE,
    TIMEOUT,
    INVALID_RESPONSE
}

fun interface ConnectionProbe {
    suspend fun check(username: String, applicationPassword: String): ConnectionResult
}
