package com.panomc.platform.server

/**
 * Versions of the plugin to platform protocol Pano knows about.
 *
 * A connected plugin announces its version on connect. Pano never assumes the plugin speaks the
 * current version: anything it does not announce is treated as the legacy behaviour.
 */
object ServerProtocol {
    /**
     * Implicit version of every plugin released before `protocolVersion` was announced. Such a
     * plugin sends no version and no capabilities at all.
     */
    const val LEGACY_PROTOCOL_VERSION = 1

    /**
     * Version spoken by the current plugin: announces `protocolVersion`, `pluginVersion` and a
     * capability list on connect.
     */
    const val CURRENT_PROTOCOL_VERSION = 2
}
