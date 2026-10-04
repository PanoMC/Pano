package com.panomc.platform.auth.panel.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Allows creating new servers on a node. */
@PermissionDefinition
class CreateServersPermission : PanelPermission("fa-plus")
