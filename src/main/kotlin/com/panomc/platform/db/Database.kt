package com.panomc.platform.db

import com.panomc.platform.db.dao.*
import com.panomc.platform.db.implementation.*

data class Database(
    val schemeVersionDao: SchemeVersionDao = SchemeVersionDaoImpl(),
    val userDao: UserDao = UserDaoImpl(),
    val permissionDao: PermissionDao = PermissionDaoImpl(),
    val panelConfigDao: PanelConfigDao = PanelConfigDaoImpl(),
    val serverDao: ServerDao = ServerDaoImpl(),
    val systemPropertyDao: SystemPropertyDao = SystemPropertyDaoImpl(),
    val panelNotificationDao: PanelNotificationDao = PanelNotificationDaoImpl(),
    val postDao: PostDao = PostDaoImpl(),
    val postCategoryDao: PostCategoryDao = PostCategoryDaoImpl(),
    val ticketDao: TicketDao = TicketDaoImpl(),
    val ticketCategoryDao: TicketCategoryDao = TicketCategoryDaoImpl(),
    val ticketMessageDao: TicketMessageDao = TicketMessageDaoImpl(),
    val permissionGroupDao: PermissionGroupDao = PermissionGroupDaoImpl(),
    val permissionGroupPermsDao: PermissionGroupPermsDao = PermissionGroupPermsDaoImpl()
) {
    fun init() = listOf(
        schemeVersionDao.init(),
        userDao.init(),
        permissionDao.init(),
        panelConfigDao.init(),
        serverDao.init(),
        systemPropertyDao.init(),
        panelNotificationDao.init(),
        postDao.init(),
        postCategoryDao.init(),
        ticketDao.init(),
        ticketCategoryDao.init(),
        ticketMessageDao.init(),
        permissionGroupDao.init(),
        permissionGroupPermsDao.init()
    )
}