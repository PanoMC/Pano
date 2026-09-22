package com.panomc.platform.auth.panel.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Allows browsing, editing, uploading and deleting the files of a server. */
@PermissionDefinition
class ManageServerFilesPermission : PanelPermission("fa-folder-open")
