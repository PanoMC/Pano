package com.panomc.platform.notification

import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Notification
import com.panomc.platform.db.model.PanelNotification
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NotificationManager(private val databaseManager: DatabaseManager, private val authProvider: AuthProvider) {
    suspend fun sendNotification(
        userId: Long,
        notificationType: Notifications.UserNotificationType,
        properties: JsonObject = JsonObject(),
        sqlClient: SqlClient
    ) {
        val notification = Notification(
            userId = userId,
            type = notificationType.name,
            properties = properties
        )

        databaseManager.notificationDao.add(notification, sqlClient)
    }

    suspend fun sendNotification(
        userId: Long,
        notificationType: Notifications.UserNotificationType,
        properties: JsonObject = JsonObject()
    ) {
        val sqlClient = databaseManager.getSqlClient()

        sendNotification(userId, notificationType, properties, sqlClient)
    }

    suspend fun sendPanelNotification(
        userId: Long,
        notificationType: Notifications.PanelNotificationType,
        properties: JsonObject = JsonObject(),
        sqlClient: SqlClient
    ) {
        val panelNotification = PanelNotification(
            userId = userId,
            type = notificationType.name,
            properties = properties
        )

        databaseManager.panelNotificationDao.add(panelNotification, sqlClient)
    }

    suspend fun sendPanelNotification(
        userId: Long,
        notificationType: Notifications.PanelNotificationType,
        properties: JsonObject = JsonObject()
    ) {
        val sqlClient = databaseManager.getSqlClient()

        sendPanelNotification(userId, notificationType, properties, sqlClient)
    }

    suspend fun sendNotificationToAll(
        userIdList: List<Long>,
        notificationType: Notifications.UserNotificationType,
        properties: JsonObject = JsonObject(),
        sqlClient: SqlClient
    ) {
        val notifications = mutableListOf<Notification>()

        userIdList.forEach { userId ->
            val notification = Notification(
                userId = userId,
                type = notificationType.name,
                properties = properties
            )

            notifications.add(notification)
        }

        databaseManager.notificationDao.addAll(notifications, sqlClient)
    }

    suspend fun sendNotificationToAll(
        userIdList: List<Long>,
        notificationType: Notifications.UserNotificationType,
        properties: JsonObject = JsonObject()
    ) {
        val sqlClient = databaseManager.getSqlClient()

        sendNotificationToAll(userIdList, notificationType, properties, sqlClient)
    }

    suspend fun sendPanelNotificationToAll(
        userIdList: List<Long>,
        notificationType: Notifications.PanelNotificationType,
        properties: JsonObject = JsonObject(),
        sqlClient: SqlClient
    ) {
        val panelNotifications = mutableListOf<PanelNotification>()

        userIdList.forEach { userId ->
            val notification = PanelNotification(
                userId = userId,
                type = notificationType.name,
                properties = properties
            )

            panelNotifications.add(notification)
        }

        databaseManager.panelNotificationDao.addAll(panelNotifications, sqlClient)
    }

    suspend fun sendPanelNotificationToAll(
        userIdList: List<Long>,
        notificationType: Notifications.PanelNotificationType,
        properties: JsonObject = JsonObject()
    ) {
        val sqlClient = databaseManager.getSqlClient()

        sendPanelNotificationToAll(userIdList, notificationType, properties, sqlClient)
    }

    suspend fun sendNotificationToAllAdmins(
        notificationType: Notifications.PanelNotificationType,
        properties: JsonObject = JsonObject(),
        sqlClient: SqlClient
    ) {
        val adminList = authProvider.getAdminList(sqlClient)

        val adminUserIdList = adminList.map { username ->
            databaseManager.userDao.getUserIdFromUsername(username, sqlClient)!!
        }

        sendPanelNotificationToAll(adminUserIdList, notificationType, properties, sqlClient)
    }

    suspend fun sendNotificationToAllAdmins(
        notificationType: Notifications.PanelNotificationType,
        properties: JsonObject = JsonObject()
    ) {
        val sqlClient = databaseManager.getSqlClient()

        sendNotificationToAllAdmins(notificationType, properties, sqlClient)
    }

    suspend fun sendNotificationToAllWithPermission(
        notificationType: Notifications.PanelNotificationType,
        properties: JsonObject = JsonObject(),
        panelPermission: PanelPermission,
        sqlClient: SqlClient
    ) {
        val users = mutableSetOf<Long>()
        val usersWithPermission = databaseManager.userDao.getIdsByPermission(panelPermission, sqlClient)
        val adminList = authProvider.getAdminList(sqlClient)

        val adminUserIdList = adminList.map { username ->
            databaseManager.userDao.getUserIdFromUsername(username, sqlClient)!!
        }

        users.addAll(usersWithPermission)
        users.addAll(adminUserIdList)

        sendPanelNotificationToAll(users.toList(), notificationType, properties, sqlClient)
    }

    suspend fun sendNotificationToAllAdminsWithPermission(
        notificationType: Notifications.PanelNotificationType,
        properties: JsonObject = JsonObject(),
        panelPermission: PanelPermission
    ) {
        val sqlClient = databaseManager.getSqlClient()

        sendNotificationToAllWithPermission(notificationType, properties, panelPermission, sqlClient)
    }

}