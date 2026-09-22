package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * Nothing that is currently reachable can perform the feature the panel asked for (SM-52).
 *
 * Thrown by `ServerFeatureResolver.pick` with `{ feature, reason, capability? }`: the dotted feature
 * name, the `ServerFeatureReason` that explains it (the same one `features.reasons` carries for
 * that entry, SM-70) and, for the plugin reasons, the capability that would have served it — so
 * the panel says what the server lacks and what would fix it rather than showing a generic
 * failure. A 409 rather than a 400 because it is a state and not a mistake: the same request works
 * the moment the node reconnects, the server starts, or the plugin is updated.
 */
class FeatureUnavailable(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(409, statusMessage, extras)
