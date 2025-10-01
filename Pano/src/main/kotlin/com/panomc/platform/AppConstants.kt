package com.panomc.platform

import com.panomc.platform.db.model.Locale
import java.io.File

object AppConstants {
    const val DEFAULT_POST_UPLOAD_PATH = "post"
    val DEFAULT_POST_THUMBNAIL_UPLOAD_PATH = "${DEFAULT_POST_UPLOAD_PATH + File.separator}thumbnail"

    const val POST_THUMBNAIL_URL_PREFIX = "/api/post/thumbnail/"

    const val COOKIE_PREFIX = "pano_"

    const val CSRF_TOKEN_COOKIE_NAME = "csrf_token"

    const val JWT_COOKIE_NAME = "auth_token"

    val CSRF_HEADER = "X-CSRF-Token".lowercase()

    val AVAILABLE_LOCALES = listOf("tr", "en-US")
    val DEFAULT_LOCALE_CODE = "en-US"

    val pluginUiFolder = "plugin-ui/"

    val THEMES_FOLDER_PATH: String = System.getProperty("pano.themesFolder", "themes")
    const val DEFAULT_THEME_ID = "vanilla-theme"

    const val TEMP_FOLDER = ".temp"

    const val REPO = "PanoMC/pano"

    const val UPDATER_JAR = "pano-updater.jar"

    const val DEFAULT_WEBSITE_LOGO_FILE = "assets/img/default-logo.png"
    const val DEFAULT_WEBSITE_ICON_FILE = "assets/img/default-icon.ico"

    val UPDATE_ICON_FOLDER = "update-thumbnail" + File.separator

    val THEME_SETTINS_FILE_UPLOAD_FOLDER = "theme-settings"

    val DEFAULT_LOCALES = listOf(
        Locale(
            code = "en-US",
            name = "English (US)",
            dateFnsCode = "en-US",
            derivatives = listOf()
        ),
        Locale(
            code = "tr",
            name = "Türkçe (TR)",
            dateFnsCode = "tr",
            derivatives = listOf("tr-tr")
        )
    )
}