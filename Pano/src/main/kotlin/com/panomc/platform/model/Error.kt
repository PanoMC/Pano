package com.panomc.platform.model

import com.panomc.platform.model.Result.Companion.encode

/**
 * An API error. Every non-2xx answer (and every failed event of a stream endpoint) has the body
 * `{ "error": { "code", "message"?, "details"?, "fields"? } }`.
 *
 * [code] is declared by the subclass, never derived from the class name, so a rename cannot break a client.
 * It must match `^[A-Z][A-Z0-9_]*$`. [extras] with the key `message` becomes `error.message`; every other
 * extra (and the extras a call site hands to [encode]) becomes `error.details`. [fields] becomes `error.fields`
 * (field name to code). `message`, `details` and `fields` are left out when empty.
 */
abstract class Error(
    val code: String,
    private val statusCode: Int = 500,
    private val statusMessage: String = "",
    private val extras: Map<String, Any?> = mapOf(),
    private val fields: Map<String, Any?> = mapOf()
) : Throwable(extras["message"] as String?), Result {

    init {
        require(CODE_PATTERN.matches(code)) { "error code '$code' must match ${CODE_PATTERN.pattern}" }
    }

    override fun encode(extras: Map<String, Any?>): String {
        val error = linkedMapOf<String, Any?>("code" to code)

        (this.extras["message"] as? String)?.takeIf { it.isNotEmpty() }?.let { error["message"] = it }

        val details = linkedMapOf<String, Any?>()

        this.extras.forEach { (key, value) -> if (key != "message") details[key] = value }
        details.putAll(extras)

        if (details.isNotEmpty()) {
            error["details"] = details
        }

        if (fields.isNotEmpty()) {
            error["fields"] = fields
        }

        return mapOf("error" to error).encode()
    }

    override fun getStatusCode(): Int = statusCode

    override fun getStatusMessage(): String = statusMessage

    fun hasExtra(key: String) = extras.containsKey(key)

    fun getErrorCode() = code

    companion object {
        val CODE_PATTERN = Regex("^[A-Z][A-Z0-9_]*$")
    }
}
