package com.panomc.node.net

/**
 * Every Pano URL path this daemon calls, and the one reader of Pano's error body.
 *
 * Pano mounts the machine protocol under `/api/v1/node` (doc 04 section 9). There is no fallback
 * to the old `/api/node` paths: a Pano that still serves only those is too old for this node.
 * Plain JDK only, because the agent launcher uses it before anything else is on the classpath.
 */
object NodePaths {
    const val BASE = "/api/v1/node"

    const val CONNECT = "$BASE/connect"

    const val CONNECTION = "$BASE/connection"

    const val TRANSFER = "$BASE/transfer/"

    const val AGENT_JAR_CHECKSUM = "$BASE/pano-agent.jar.sha256"

    const val NODE_JAR_CHECKSUM = "$BASE/pano-node.jar.sha256"

    private val CODE = Regex("\"error\"\\s*:\\s*\\{[^{}]*?\"code\"\\s*:\\s*\"([A-Z][A-Z0-9_]*)\"")

    /** The code of `{"error":{"code":"..."}}`, or null when [body] is not Pano's error envelope. */
    fun errorCode(body: String?): String? = body?.let { CODE.find(it)?.groupValues?.get(1) }
}
