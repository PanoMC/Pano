package com.panomc.platform.route.api.setup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.backup.remote.PanoBackupSetupView
import com.panomc.platform.backup.remote.RemoteBackupSettings
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.mail.PanoMailService
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import java.time.Duration

/**
 * `GET /api/v1/setup/pano-backup` → `{website, connected, account {username} | null, freeGb (number | null), plan {name,
 * quotaBytes, free} | null, usage {usedBytes} | null, daily, manageUrl}`; see [PanoBackupSetupView]. `freeGb` is the
 * public catalog's `backupFreeGb` (cached a few minutes); `plan` / `usage` come from the connected backups call
 * (~6 s timeout). `daily` is whether the remote schedule is DAILY (always false while setting up: nothing is stored
 * before the finish step). Never fails because of the website.
 */
@Endpoint
class SetupGetPanoBackupAPI(
    private val configManager: ConfigManager,
    private val panoBackupManager: PanoBackupManager,
    private val panoMailService: PanoMailService
) : SetupApi() {
    override val paths = listOf(Path("/setup/pano-backup", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        val config = configManager.config
        val connected = config.panoAccount.accessToken.isNotBlank()

        val list = if (connected) {
            try {
                panoBackupManager.mailClient().listBackups(Duration.ofSeconds(6))
            } catch (_: Exception) {
                null
            }
        } else null

        val daily = try {
            panoBackupManager.setupRemote.state().settings.schedule == RemoteBackupSettings.Schedule.DAILY
        } catch (_: Exception) {
            false
        }

        return Successful(PanoBackupSetupView.build(config, list, panoMailService.freeBackupGb(), daily))
    }
}
