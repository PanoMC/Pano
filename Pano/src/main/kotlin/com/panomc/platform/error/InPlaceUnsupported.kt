package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * A reinstall or a software change was asked of a server that runs in place from a directory that
 * was already there (`server.inPlace`, the "Pano Agent" link). Both build a new directory beside the
 * old one and swap them, and beside somebody else's server directory is not a place Pano creates
 * folders in, so they are refused. Nothing was touched.
 */
class InPlaceUnsupported(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(409, statusMessage, extras)
