package com.panomc.platform.error

import com.panomc.platform.backup.remote.PanoHostException
import com.panomc.platform.backup.remote.PanoRemoteBackupService
import com.panomc.platform.model.Error

/**
 * Pano Host / Pano Backup said no, or could not be reached: `hostError` is its code
 * (`PAYMENT_REQUIRED`, `QUOTA_EXCEEDED`, `PANO_HOST_NOT_LINKED`, `PANO_HOST_UNAVAILABLE`, …) plus its
 * extra fields (`reason`, `nextAllowedAt`, `quotaBytes`, …).
 */
class PanoHostError(
    statusCode: Int = 400,
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(statusCode, statusMessage, extras) {
    companion object {
        fun of(exception: PanoHostException): PanoHostError {
            val status = when (exception.code) {
                PanoHostException.UNAVAILABLE -> 502
                PanoRemoteBackupService.NOT_LINKED, PanoRemoteBackupService.PASSPHRASE_NOT_SET -> 409
                else -> 400
            }

            return PanoHostError(status, extras = exception.extras.map + mapOf("hostError" to exception.code))
        }
    }
}
