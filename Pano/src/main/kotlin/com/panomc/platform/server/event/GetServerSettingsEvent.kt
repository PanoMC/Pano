package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.event.request.GetServerSettingsEventRequest
import com.panomc.platform.server.response.GetServerSettingsEventResponse

@Event
class GetServerSettingsEvent(
    private val i18nManager: I18nManager,
    private val configManager: ConfigManager
) : ServerEvent<GetServerSettingsEventRequest, GetServerSettingsEventResponse>() {
    override suspend fun handle(request: GetServerSettingsEventRequest, server: Server): GetServerSettingsEventResponse {
        val settings = server.settings

        val translationsByLocale = i18nManager.getTranslationsByLocale(TranslationType.MC_PLUGIN)
        val platformLocale = configManager.config.locale

        return GetServerSettingsEventResponse(
            settings.authIntegration,
            settings.banIntegration,
            settings.permissionIntegration,
            settings.authRequireVerified,
            settings.authKickAfterRegister,
            translationsByLocale,
            platformLocale
        )
    }
}