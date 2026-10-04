package com.panomc.platform.model

import com.panomc.platform.route.api.panel.panoBackup.PanelRestoreUploadedPanoBackupAPI
import com.panomc.platform.route.api.panel.transfers.PanelUploadTransferAPI
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.full.declaredMemberFunctions

/**
 * Regression guard: the large panel upload routes authorise in [Api.checkBeforeBody], which
 * [Api.authorizedBodyHandler] runs before the BodyHandler spools a byte (behaviour: [AuthorizedBodyHandlerTest]).
 */
class UploadRoutesCheckBeforeBodyTest {
    @Test
    fun `large panel upload routes authorise before the body is spooled`() {
        listOf(PanelUploadTransferAPI::class, PanelRestoreUploadedPanoBackupAPI::class).forEach { api ->
            assertTrue(api.declaredMemberFunctions.any { it.name == "checkBeforeBody" }, "${api.simpleName} must override checkBeforeBody")
        }
    }
}
