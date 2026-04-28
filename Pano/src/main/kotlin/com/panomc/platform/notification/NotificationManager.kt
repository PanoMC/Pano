package com.panomc.platform.notification

import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.Permission
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.NotificationDao
import com.panomc.platform.db.dao.PanelNotificationDao
import com.panomc.platform.db.dao.UserDao
import com.panomc.platform.db.model.Notification
import com.panomc.platform.db.model.PanelNotification
import com.panomc.platform.panel.PanelRealtimeHub
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NotificationManager(
    databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val permissionManager: PermissionManager,
    private val panelRealtimeHub: PanelRealtimeHub
) {
    private val notificationDao: NotificationDao = databaseManager.notificationDao
    private val panelNotificationDao: PanelNotificationDao = databaseManager.panelNotificationDao
    private val userDao: UserDao = databaseManager.userDao

    suspend fun sendNotification(
        userId: Long,
        userNotificationType: UserNotificationType,
        sqlClient: SqlClient
    ) {
        val notification = Notification(
            userId = userId,
            type = userNotificationType,
            details = JsonObject.mapFrom(userNotificationType)
        )

        notificationDao.add(notification, sqlClient)
        panelRealtimeHub.notifyPanelNotificationRefresh(userId)
    }

    suspend fun sendPanelNotification(
        userId: Long,
        panelUserNotificationType: PanelUserNotificationType,
        sqlClient: SqlClient
    ) {
        val panelNotification = PanelNotification(
            userId = userId,
            type = panelUserNotificationType,
            details = JsonObject.mapFrom(panelUserNotificationType)
        )

        panelNotificationDao.add(panelNotification, sqlClient)
        panelRealtimeHub.notifyPanelNotificationRefresh(userId)
    }

    suspend fun sendNotificationToAll(
        userIdList: List<Long>,
        userNotificationType: UserNotificationType,
        sqlClient: SqlClient
    ) {
        val notifications = mutableListOf<Notification>()

        userIdList.forEach { userId ->
            val notification = Notification(
                userId = userId,
                type = userNotificationType,
                details = JsonObject.mapFrom(userNotificationType)
            )

            notifications.add(notification)
        }

        notificationDao.addAll(notifications, sqlClient)
        userIdList.distinct().forEach { panelRealtimeHub.notifyPanelNotificationRefresh(it) }
    }

    suspend fun sendPanelNotificationToAll(
        userIdList: List<Long>,
        panelUserNotificationType: PanelUserNotificationType,
        sqlClient: SqlClient
    ) {
        val panelNotifications = mutableListOf<PanelNotification>()

        userIdList.forEach { userId ->
            val notification = PanelNotification(
                userId = userId,
                type = panelUserNotificationType,
                details = JsonObject.mapFrom(panelUserNotificationType)
            )

            panelNotifications.add(notification)
        }

        panelNotificationDao.addAll(panelNotifications, sqlClient)
        userIdList.distinct().forEach { panelRealtimeHub.notifyPanelNotificationRefresh(it) }
    }

    suspend fun sendNotificationToAllAdmins(
        panelUserNotificationType: PanelUserNotificationType,
        sqlClient: SqlClient
    ) {
        val adminList = authProvider.getAdminList(sqlClient)
        val adminIdList = userDao.getIdsByListOfUsername(adminList, sqlClient).map { it.value }

        sendPanelNotificationToAll(adminIdList, panelUserNotificationType, sqlClient)
    }

    suspend fun sendNotificationToAllWithPermission(
        notificationType: PanelUserNotificationType,
        permission: Permission,
        sqlClient: SqlClient
    ) {
        val users = mutableSetOf<Long>()
        val usersWithPermission = permissionManager.getUserIdsWithPermission(permission)
        val adminList = authProvider.getAdminList(sqlClient)

        val adminUserIdList = userDao.getIdsByListOfUsername(adminList, sqlClient).map { it.value }

        users.addAll(usersWithPermission)
        users.addAll(adminUserIdList)

        sendPanelNotificationToAll(users.toList(), notificationType, sqlClient)
    }
}