package com.mucacran.infocontainer.network

/** Real AI calls stay disabled until an explicit, non-persistent authorization is supplied. */
class AiExecutionGate(var authorized: Boolean = false)
