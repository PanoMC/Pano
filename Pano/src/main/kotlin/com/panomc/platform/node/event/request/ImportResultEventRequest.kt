package com.panomc.platform.node.event.request

import com.panomc.platform.node.NodeEventRequest

/**
 * `IMPORT_RESULT`: what a node found in the directory it just assembled.
 *
 * Every field but the ids may be null, and that is the point: an import is allowed to produce a
 * server whose software nobody can name, as long as there is a jar to launch. Pano writes back
 * what it was told and leaves the rest as it was.
 */
class ImportResultEventRequest(
    val serverUuid: String? = null,
    val taskId: String? = null,
    /** The launchable jar, relative to the server directory. */
    val jar: String? = null,
    val software: String? = null,
    val version: String? = null,
    val port: Int? = null,
    val javaMajor: Int? = null,
    /** True when the server was adopted where it is (`IN_PLACE`); null from a node older than 5. */
    val inPlace: Boolean? = null,
    /** The server directory's absolute path on the node's host, as the node resolved it. */
    val directory: String? = null
) : NodeEventRequest()
