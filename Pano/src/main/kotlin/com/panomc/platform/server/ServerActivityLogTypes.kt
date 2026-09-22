package com.panomc.platform.server

import com.panomc.platform.auth.panel.log.CreatedServerLog
import com.panomc.platform.auth.panel.log.LinkedAgentServerLog
import com.panomc.platform.auth.panel.log.ReinstalledServerLog
import com.panomc.platform.auth.panel.log.SentServerCommandLog
import com.panomc.platform.auth.panel.log.ServerBackupActionLog
import com.panomc.platform.auth.panel.log.ServerCrashedLog
import com.panomc.platform.auth.panel.log.ServerFileChangedLog
import com.panomc.platform.auth.panel.log.ServerPanoPluginUpdatedLog
import com.panomc.platform.auth.panel.log.ServerPlayerActionLog
import com.panomc.platform.auth.panel.log.ServerPluginFileActionLog
import com.panomc.platform.auth.panel.log.ServerPluginToggledLog
import com.panomc.platform.auth.panel.log.ServerPowerActionLog
import com.panomc.platform.auth.panel.log.ServerScheduleActionLog
import com.panomc.platform.auth.panel.log.ServerScheduleRunLog
import com.panomc.platform.auth.panel.log.UpdatedServerStartupLog
import com.panomc.platform.db.model.PanelActivityLog

/**
 * The activity log types that belong to one server (§2.4.12).
 *
 * The per-server feed is a filtered view of the one `panel_activity_log`, so this is the list that
 * decides what shows up in it. Derived from the log classes rather than written out as strings:
 * [PanelActivityLog] builds its `type` from the class name, and a hand-maintained copy of those
 * names is a list that silently stops matching the day somebody renames a class.
 *
 * `DELETED_SERVER` is deliberately absent — the row it refers to is gone, so nothing can ever ask
 * for that server's feed, and including it would only widen the query for no reader.
 */
object ServerActivityLogTypes {
    val ALL: List<String> = listOf(
        SentServerCommandLog::class,
        ServerPowerActionLog::class,
        ServerPlayerActionLog::class,
        ServerPluginFileActionLog::class,
        ServerPluginToggledLog::class,
        ServerPanoPluginUpdatedLog::class,
        ServerFileChangedLog::class,
        ServerBackupActionLog::class,
        ServerScheduleActionLog::class,
        ServerScheduleRunLog::class,
        ServerCrashedLog::class,
        CreatedServerLog::class,
        ReinstalledServerLog::class,
        UpdatedServerStartupLog::class,
        LinkedAgentServerLog::class
    ).map { PanelActivityLog.typeOf(it.java) }
}
