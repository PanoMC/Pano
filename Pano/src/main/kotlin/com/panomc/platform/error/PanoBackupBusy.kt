package com.panomc.platform.error

import com.panomc.platform.model.Error

/** Another Pano backup or restore is running; only one runs at a time. */
class PanoBackupBusy(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(409, statusMessage, extras)
