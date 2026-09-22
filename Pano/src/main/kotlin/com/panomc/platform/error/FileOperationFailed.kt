package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * The node refused or could not finish a file operation.
 *
 * Carries the node's own reason as `message`, because the interesting failures here ("already
 * exists", "not an archive", "no space left") are all things the person can act on and none of
 * them mean anything to Pano itself.
 */
class FileOperationFailed(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(400, statusMessage, extras)
