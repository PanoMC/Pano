package com.panomc.platform.model

/**
 * Whether an endpoint stays reachable while maintenance mode is on.
 *
 * [BYPASSER_ONLY] (the default) closes the endpoint to normal visitors and keeps it open for users
 * holding the configured maintenance bypass permission — this is what lets a bypassing admin browse
 * the real theme, since the theme's own API calls carry their session.
 *
 * [ALWAYS] is for infrastructure that must never be gated: the panel API, the Minecraft plugin
 * server API, the maintenance endpoints themselves, and the handful of public endpoints the panel
 * renders its own chrome from.
 */
enum class MaintenanceAccess {
    ALWAYS,
    BYPASSER_ONLY
}
