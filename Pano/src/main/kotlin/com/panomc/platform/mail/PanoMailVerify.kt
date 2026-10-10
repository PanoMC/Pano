package com.panomc.platform.mail

import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.PanoMailConnectRequired
import com.panomc.platform.error.PanoMailUnavailable
import io.vertx.core.json.JsonObject

/** The "verify mail" routes (set-up step 3 and the panel) for the two providers. */
object PanoMailVerify {
    val SMTP_FIELDS = listOf("hostname", "port", "starttls", "username", "password", "sender")

    /** Settings save: the verify fields plus the toggles the panel form always sends. */
    val SMTP_SAVE_FIELDS = listOf("enabled", "ssl") + SMTP_FIELDS

    /** An SMTP verify needs the fields the schema no longer requires (the body may be `{provider: "PANO_MAIL"}`). */
    fun requireSmtpFields(body: JsonObject, fields: List<String> = SMTP_FIELDS) {
        val missing = fields.filter { !body.containsKey(it) || body.getValue(it) == null }

        if (missing.isNotEmpty()) {
            throw InvalidData(extras = mapOf("missing" to missing))
        }
    }

    /**
     * Pano Mail verify: the account is connected and the website switches Pano Mail on for it (idempotent,
     * no test mail is sent). Answers `{ready, from}`; `ready = false` is not a failure.
     */
    suspend fun run(service: PanoMailService): Map<String, Any?> {
        if (!service.isConnected()) {
            throw PanoMailConnectRequired()
        }

        val status = try {
            service.enable()
        } catch (e: PanoMailException) {
            if (e.code == PanoMailException.CONNECT_REQUIRED) throw PanoMailConnectRequired()

            throw PanoMailUnavailable(e.message ?: "", mapOf("reason" to e.reason))
        }

        return mapOf("ready" to (status.getBoolean("ready") ?: false), "from" to status.getString("from"))
    }
}
