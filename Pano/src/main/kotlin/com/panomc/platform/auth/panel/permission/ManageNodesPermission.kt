package com.panomc.platform.auth.panel.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Allows adding, configuring and removing the nodes that run managed servers. */
@PermissionDefinition
class ManageNodesPermission : PanelPermission("fa-network-wired")
