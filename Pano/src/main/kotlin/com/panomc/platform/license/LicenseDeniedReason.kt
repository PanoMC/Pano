package com.panomc.platform.license

/**
 * Discrete failure reasons produced by [LicenseManager].
 *
 * Surfaced to the panel so the UI can offer the right call-to-action ("Buy License",
 * "Connect Pano account", "Retry network", etc.) instead of dumping a stacktrace.
 */
enum class LicenseDeniedReason(val publicId: String) {
    NOT_CONNECTED("not-connected"),
    NO_PURCHASE("no-purchase"),
    JAR_HASH_MISMATCH("jar-hash-mismatch"),
    VERSION_MISMATCH("version-mismatch"),
    NETWORK_ERROR("network-error"),
    SIGNATURE_INVALID("signature-invalid"),
    EXPIRED("expired"),
    AUDIENCE_MISMATCH("audience-mismatch"),
    PLATFORM_MISMATCH("platform-mismatch"),
    KEY_NOT_AVAILABLE("key-not-available"),
    INVALID_RESPONSE("invalid-response"),
    /**
     * A premium theme's extracted folder on disk no longer matches the
     * `fileFingerprint` it shipped with — likely tampering or partial update.
     * Surfaced by the host before spawning the bun process and by the theme's
     * own runtime helper as a defence-in-depth pass.
     */
    FILE_TAMPERED("file-tampered"),
    UNKNOWN("unknown")
}
