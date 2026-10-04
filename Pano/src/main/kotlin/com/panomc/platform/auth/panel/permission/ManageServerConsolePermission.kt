package com.panomc.platform.auth.panel.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Allows viewing a server console and sending commands to it. */
@PermissionDefinition
class ManageServerConsolePermission : PanelPermission("fa-terminal")
