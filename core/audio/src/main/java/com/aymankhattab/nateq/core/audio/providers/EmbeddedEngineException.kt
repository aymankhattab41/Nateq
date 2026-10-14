package com.aymankhattab.nateq.core.audio.providers

/**
 * استثناء فشل محرك مدمج (ع٣٣-7).
 */
class EmbeddedEngineException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException("[$code] $message", cause)
