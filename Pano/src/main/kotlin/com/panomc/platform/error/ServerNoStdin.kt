package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * The managed server's process was adopted after a node restart, so nothing can be written to its
 * console (SM-51, §2.4.16).
 *
 * The node inherited a running process and not the pipes that went with it: output still arrives,
 * because it is read from the log file, but stdin belonged to the daemon that is gone. Where the
 * server's Pano plugin announces the `commands` capability the command is sent through it instead
 * and this is never reached; it only happens for a server whose plugin is missing, offline or too
 * old, and the way out of it is a restart from the panel.
 *
 * A 409 rather than a 400 because it is a state and not a mistake: the very same request works
 * again the moment the server is restarted, so the panel explains it instead of hiding the input.
 */
class ServerNoStdin(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(409, statusMessage, extras)
