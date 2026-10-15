package com.panomc.platform.webhook

/** The `X-Pano-*` request headers and the admin-defined extra headers of an endpoint. */
object WebhookHeaders {
    const val EVENT = "X-Pano-Event"
    const val EVENT_ID = "X-Pano-Event-Id"
    const val DELIVERY = "X-Pano-Delivery"
    const val ATTEMPT = "X-Pano-Attempt"

    const val MAX_HEADERS = 10
    const val MAX_VALUE_LENGTH = 512

    private val NAME = Regex("^[A-Za-z0-9\\-]{1,64}$")
    private val VALUE = Regex("^[\\u0020-\\u007e]{1,$MAX_VALUE_LENGTH}$")
    private val FORBIDDEN = setOf("host", "content-length", "content-type", "transfer-encoding", "connection", "user-agent")

    fun isAllowedName(name: String): Boolean =
        NAME.matches(name) && name.lowercase() !in FORBIDDEN && !name.startsWith("X-Pano-", ignoreCase = true)

    fun isAllowedValue(value: String): Boolean = VALUE.matches(value)

    /** Field errors keyed `headers.<name>` (or `headers` for the count), empty when [headers] is acceptable. */
    fun validate(headers: Map<String, String>): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        if (headers.size > MAX_HEADERS) errors["headers"] = "TOO_MANY"
        val seen = HashSet<String>()
        for ((name, value) in headers) {
            if (!isAllowedName(name)) errors["headers.$name"] = "INVALID_NAME"
            else if (!seen.add(name.lowercase())) errors["headers.$name"] = "DUPLICATE"
            else if (!isAllowedValue(value)) errors["headers.$name"] = "INVALID_VALUE"
        }
        return errors
    }

    /**
     * What is actually put on a request: the entries that pass the rules, in order. A stored entry that no longer
     * passes (a rule tightened later) is dropped rather than sent; `X-Pano-*`, `Host` and the framing headers can
     * therefore never be overridden through the stored JSON.
     */
    fun sendable(headers: Map<String, String>): List<Pair<String, String>> =
        headers.entries.filter { isAllowedName(it.key) && isAllowedValue(it.value) }.take(MAX_HEADERS).map { it.key to it.value }
}
