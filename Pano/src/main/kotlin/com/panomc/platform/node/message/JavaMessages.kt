package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage
import com.panomc.platform.node.NodeRequestMessage

/**
 * Asks a node what Java it has and what it could download (`JAVA_CATALOG`, SM-63, §2.4.28).
 *
 * A request: the node's Java card is waiting on an HTTP response. The answer is the usual
 * `FILE_RESULT`, carrying `os`, `arch`, `libc`, `autoDownload`, `runtimes` and `downloadable`
 * flat in the payload. A node that cannot reach Adoptium or Azul still answers, with an empty
 * `downloadable` and a `catalogError`; a node too old to know the message never answers, which is
 * why Pano only asks nodes that announced the feature.
 */
class JavaCatalogMessage : NodeRequestMessage()

/**
 * Tells a node to download the newest Java [major] it can get (`JAVA_INSTALL`).
 *
 * Reported back as a `JAVA_INSTALL` task under [taskId]. Installing a major whose managed runtime
 * is already current ends DONE at once, and an update installs side by side and retires the old
 * version when nothing runs from it.
 */
data class JavaInstallMessage(
    val taskId: String,
    val major: Int
) : NodeMessage

/**
 * Tells a node to delete a managed Java runtime (`JAVA_REMOVE`).
 *
 * [version] picks one of several managed runtimes of the same [major]; null removes the managed
 * runtime of that major. The node refuses a runtime a server is using and one it did not download
 * itself, and says so in the task's `error` (`JAVA_IN_USE`, `NOT_MANAGED`, `NOT_FOUND`).
 */
data class JavaRemoveMessage(
    val taskId: String,
    val major: Int,
    val version: String? = null
) : NodeMessage
