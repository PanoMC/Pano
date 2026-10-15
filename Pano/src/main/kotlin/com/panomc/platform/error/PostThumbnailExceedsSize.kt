package com.panomc.platform.error

import com.panomc.platform.model.Error

class PostThumbnailExceedsSize(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("POST_THUMBNAIL_EXCEEDS_SIZE", 422, statusMessage, extras)