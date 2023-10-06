package com.panomc.platform

object AppConstants {
    const val DEFAULT_POST_UPLOAD_PATH = "post"
    const val DEFAULT_POST_THUMBNAIL_UPLOAD_PATH = "$DEFAULT_POST_UPLOAD_PATH/thumbnail"

    const val POST_THUMBNAIL_URL_PREFIX = "/api/post/thumbnail/"

    const val COOKIE_PREFIX = "pano_"

    const val CSRF_TOKEN_COOKIE_NAME = "CSRFToken"

    const val JWT_COOKIE_NAME = "jwt"

    val CSRF_HEADER = "X-CSRF-Token".lowercase()

    val AVAILABLE_LOCALES = listOf("tr", "en-us")
}