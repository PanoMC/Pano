package com.panomc.platform.auth.panel.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Allows creating, editing and running the scheduled tasks of a server. */
@PermissionDefinition
class ManageServerSchedulesPermission : PanelPermission("fa-clock")
