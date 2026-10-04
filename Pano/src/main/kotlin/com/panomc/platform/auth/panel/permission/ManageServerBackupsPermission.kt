package com.panomc.platform.auth.panel.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Allows creating, downloading, restoring and deleting server backups. */
@PermissionDefinition
class ManageServerBackupsPermission : PanelPermission("fa-box-archive")
