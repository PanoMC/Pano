package com.panomc.platform.auth.panel.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Allows editing the startup configuration of a server, such as its version, flags and variables. */
@PermissionDefinition
class ManageServerStartupPermission : PanelPermission("fa-sliders")
