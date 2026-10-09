package com.panomc.platform.error

import com.panomc.platform.model.Error

/** A restore is replacing the database right now; ask again in a moment. */
class PanoBackupRestoring(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("PANO_BACKUP_RESTORING", 503, statusMessage, extras)
