package com.panomc.platform.auth.panel.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Allows managing the players of a server, such as kicking, banning and whitelisting them. */
@PermissionDefinition
class ManageServerPlayersPermission : PanelPermission("fa-users")
