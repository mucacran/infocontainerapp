package com.mucacran.infocontainer.network

/** Analysis and general real AI operations remain disabled independently of identification. */
class AiExecutionGate(val authorized: Boolean = false) {
    private var identificationPermission = false

    val identificationAuthorized: Boolean
        get() = identificationPermission

    /** Called only after the operator explicitly confirms the real identification on screen. */
    fun authorizeIdentificationForSession() {
        identificationPermission = true
    }
}
