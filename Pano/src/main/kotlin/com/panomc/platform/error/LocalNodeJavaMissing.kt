package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * The machine has no Java new enough to run `pano-node.jar`.
 *
 * Its own error code rather than a bare [BadRequest] because it is the one local-node failure an
 * admin can fix themselves, and the panel has something useful to say about it: install a JDK 17+,
 * or point `local-node.java-path` at the one that is already there. The specific reason travels in
 * `message`, since "no Java" and "the Java you configured is too old" need different answers.
 */
class LocalNodeJavaMissing(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(400, statusMessage, extras)
