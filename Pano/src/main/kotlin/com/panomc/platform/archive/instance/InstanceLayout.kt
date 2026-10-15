package com.panomc.platform.archive.instance

import com.panomc.platform.AppConstants
import com.panomc.platform.config.PanoConfig
import java.io.File

/**
 * Where an instance keeps what a `pano-instance` archive holds (archive-format.md section 1). Each
 * directory maps to one `app/` prefix; everything else (jar, libraries, UIs, logs, certificates,
 * `.temp`, node data) is never archived.
 */
data class InstanceLayout(
    val configFile: File,
    val pluginsDir: File,
    val themesDir: File,
    val uploadsDir: File,
    val maintenanceDir: File,
    /** Scratch space for dumps and restore staging; must be on the same filesystem as the dirs above. */
    val tempDir: File
) {
    /** The archived directories with their `app/` prefix. */
    val directories: List<Pair<String, File>>
        get() = listOf(
            PLUGINS to pluginsDir,
            THEMES to themesDir,
            FILE_UPLOADS to uploadsDir,
            MAINTENANCE to maintenanceDir
        )

    /** A directory of the archive with its `app/` [prefix] and what is left out of it (path relative to the directory). */
    class Tree(val prefix: String, val dir: File, val exclude: (String) -> Boolean)

    /** What an instance archive carries from disk, with the exclusion rules: the archiver and size estimates both read this. */
    val trees: List<Tree>
        get() = listOf(
            Tree(PLUGINS, pluginsDir) { false },
            Tree(THEMES, themesDir, ::themeExcluded),
            Tree(FILE_UPLOADS, uploadsDir, ::uploadsExcluded),
            Tree(MAINTENANCE, maintenanceDir) { false }
        )

    companion object {
        const val CONFIG_ENTRY = "app/config.conf"
        const val PLUGINS = "app/plugins"
        const val THEMES = "app/themes"
        const val FILE_UPLOADS = "app/file-uploads"
        const val MAINTENANCE = "app/maintenance"

        /** Theme files the host rewrites itself (license token), never archived. */
        val THEME_EXCLUDED_FILES = setOf(".pano-license.jwt", ".pano-license.jwt.tmp")

        /** Top-level `file-uploads` folders that are caches or in-flight data, never archived. */
        val UPLOADS_EXCLUDED_DIRS = setOf("cache", "temp", "transfer")

        /** The layout of the running Pano (same system properties and config keys Pano itself uses). */
        fun current(config: PanoConfig) = InstanceLayout(
            configFile = File(System.getProperty("pano.configFile", "config.conf")),
            pluginsDir = File(System.getProperty("pf4j.pluginsDir", "./plugins")),
            themesDir = File(AppConstants.THEMES_FOLDER_PATH),
            uploadsDir = File(config.fileUploadsFolder),
            maintenanceDir = File(AppConstants.MAINTENANCE_FOLDER_PATH),
            tempDir = File(AppConstants.TEMP_FOLDER)
        )

        fun themeExcluded(relative: String) = relative.substringAfterLast('/') in THEME_EXCLUDED_FILES

        fun uploadsExcluded(relative: String) = relative in UPLOADS_EXCLUDED_DIRS
    }
}
