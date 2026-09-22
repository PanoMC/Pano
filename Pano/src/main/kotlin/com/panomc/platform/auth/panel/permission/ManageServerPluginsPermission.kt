package com.panomc.platform.auth.panel.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Allows installing, updating and removing the plugins and mods of a server. */
@PermissionDefinition
class ManageServerPluginsPermission : PanelPermission("fa-puzzle-piece")
