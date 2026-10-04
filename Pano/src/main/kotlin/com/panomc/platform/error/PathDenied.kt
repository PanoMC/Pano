package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * The path is outside what the file manager may touch.
 *
 * Covers traversal, absolute paths, symlinks leaving the server directory and the denylist that
 * hides a server's own Pano credentials. Deliberately one error for all of them: telling a caller
 * *why* a path was refused tells them where the boundary is.
 */
class PathDenied(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(400, statusMessage, extras)
