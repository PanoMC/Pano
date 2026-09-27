package com.panomc.platform.error

import com.panomc.platform.backup.remote.PanoHostException
import com.panomc.platform.backup.remote.PanoRemoteBackupService
import com.panomc.platform.model.Error

/**
 * Pano Host / Pano Backup said no, or could not be reached: `hostError` is its code
 * (`PAYMENT_REQUIRED`, `QUOTA_EXCEEDED`, `CONNECT_REQUIRED`, `PANO_HOST_UNAVAILABLE`, …) plus its
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
                PanoHostException.CONNECT_REQUIRED, PanoRemoteBackupService.PASSPHRASE_NOT_SET, PanoHostException.STOPPED_REMOTELY -> 409
                else -> 400
            }

            return PanoHostError(status, extras = exception.extras.map + mapOf("hostError" to exception.code))
        }
    }
}
