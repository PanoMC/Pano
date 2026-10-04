package com.panomc.platform.auth.panel.permission

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.auth.PanelPermission

/** Allows creating, downloading, deleting and restoring backups of the whole Pano (Pano Backup). */
@PermissionDefinition
class ManagePanoBackupsPermission : PanelPermission("fa-cloud-arrow-up")
