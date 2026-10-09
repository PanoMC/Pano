package com.panomc.platform.error

import com.panomc.platform.model.Error

class PostThumbnailWrongContentType(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("POST_THUMBNAIL_WRONG_CONTENT_TYPE", 422, statusMessage, extras)