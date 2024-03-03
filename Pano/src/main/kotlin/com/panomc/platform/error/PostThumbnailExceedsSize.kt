package com.panomc.platform.error

import com.panomc.platform.model.Error

class PostThumbnailExceedsSize(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(422, statusMessage, extras)