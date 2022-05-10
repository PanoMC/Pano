package com.panomc.platform.db

import com.panomc.platform.db.dao.*
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component


@Lazy
@Component
class Database(
    val schemeVersionDao: SchemeVersionDao,
    val userDao: UserDao,
    val permissionDao: PermissionDao,
    val panelConfigDao: PanelConfigDao,
    val serverDao: ServerDao,
    val systemPropertyDao: SystemPropertyDao,
    val panelNotificationDao: PanelNotificationDao,
    val postDao: PostDao,
    val postCategoryDao: PostCategoryDao,
    val ticketDao: TicketDao,
    val ticketCategoryDao: TicketCategoryDao,
    val ticketMessageDao: TicketMessageDao,
    val permissionGroupDao: PermissionGroupDao,
    val permissionGroupPermsDao: PermissionGroupPermsDao
) {

}