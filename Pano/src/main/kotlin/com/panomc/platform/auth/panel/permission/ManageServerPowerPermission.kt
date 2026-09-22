package com.panomc.platform.auth.panel.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Allows starting, stopping, restarting and killing a server. */
@PermissionDefinition
class ManageServerPowerPermission : PanelPermission("fa-power-off")
