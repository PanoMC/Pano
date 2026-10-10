package com.panomc.platform.mail

import com.panomc.platform.error.InvalidData
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
     * no test mail is sent; [name] creates or renames the address). Answers `{ready, from}` with the resulting
     * address; `ready = false` is not a failure.
     */
    suspend fun run(service: PanoMailService, name: String? = null, localPart: String? = null): Map<String, Any?> {
        val enabled = service.enableAs(name, localPart)

        return mapOf("ready" to (enabled.status.getBoolean("ready") ?: false), "from" to enabled.address)
    }
}
