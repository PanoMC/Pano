package com.panomc.platform.util

/**
 * Where Pano asks which Pano / pano-mc-plugin release is the newest (config `update-source`).
 *
 * The jars themselves always come from the GitHub release assets and are always verified against
 * the release's `.sha256`; this only decides who answers "which version is newest".
 */
enum class UpdateSource {
    /** api.panomc.com first; GitHub when it cannot answer. The default. */
    AUTO,

    /** api.panomc.com only. */
    PANO_API,

    /** api.github.com only (the behaviour before api.panomc.com served release info). */
    GITHUB;

    companion object {
        /** Lenient parse: case-insensitive, `-` or `_`; anything unknown is null. */
        fun parse(value: String?): UpdateSource? {
            val normalized = value?.trim()?.replace('-', '_')?.takeIf { it.isNotEmpty() } ?: return null

            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        }
    }
}
