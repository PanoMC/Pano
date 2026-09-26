package com.panomc.platform.route.api.panel.panoBackup.remote

import com.panomc.platform.backup.PanoBackupJob
import com.panomc.platform.backup.remote.LinkPurpose
import com.panomc.platform.backup.remote.PanoHostException
import com.panomc.platform.backup.remote.PanoRemoteBackupService
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.PanoHostError
import com.panomc.platform.route.api.panel.panoBackup.PanoBackupRoutes
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext

/** Shared bits of the Pano Backup (remote) + transfer routes, panel and setup. */
object PanoBackupRemoteRoutes {
    /** Runs a Pano Host call, turning its errors into [PanoHostError]. */
    suspend fun <T> host(block: suspend () -> T): T = try {
        block()
    } catch (e: PanoHostException) {
        throw PanoHostError.of(e)
    }

    /** Starts a job: BUSY → 409, a precondition (not linked, no passphrase) → [PanoHostError]. */
    fun job(start: () -> PanoBackupJob): PanoBackupJob = try {
        PanoBackupRoutes.startJob(start)
    } catch (e: PanoHostException) {
        throw PanoHostError.of(e)
    }

    /** The JSON body (empty object when none); a malformed body is a 400. */
    fun body(context: RoutingContext): JsonObject = try {
        context.body()?.asJsonObject() ?: JsonObject()
    } catch (_: Exception) {
        throw BadRequest()
    }

    fun purpose(value: String?): LinkPurpose = LinkPurpose.values().firstOrNull { it.name == value?.uppercase() } ?: throw BadRequest()

    fun remoteId(context: RoutingContext, name: String = "id"): String =
        context.pathParam(name)?.takeIf { PanoRemoteBackupService.isValidRemoteId(it) } ?: throw NotExists()

    /** Fails fast (before a job starts) when the link or the passphrase is missing. */
    suspend fun requireReady(remote: PanoRemoteBackupService, purpose: LinkPurpose, passphrase: Boolean) {
        val status = remote.status()

        if (status.getJsonObject("links")?.getJsonObject(purpose.name) == null) {
            throw PanoHostError.of(PanoHostException(PanoRemoteBackupService.NOT_LINKED, extras = JsonObject().put("purpose", purpose.name)))
        }

        if (passphrase && !status.getBoolean("passphraseSet", false)) {
            throw PanoHostError.of(PanoHostException(PanoRemoteBackupService.PASSPHRASE_NOT_SET))
        }
    }
}
