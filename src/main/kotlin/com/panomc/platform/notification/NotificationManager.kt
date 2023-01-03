package com.panomc.platform.notification

import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Notification
import com.panomc.platform.db.model.PanelNotification
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
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
        sqlConnection: SqlConnection
    ) {
        val notification = Notification(
            userId = userId,
            type = notificationType.name,
            properties = properties
        )

        databaseManager.notificationDao.add(notification, sqlConnection)
    }

    suspend fun sendNotification(
        userId: Long,
        notificationType: Notifications.UserNotificationType,
        properties: JsonObject = JsonObject()
    ) {
        val sqlConnection = databaseManager.createConnection()

        sendNotification(userId, notificationType, properties, sqlConnection)

        databaseManager.closeConnection(sqlConnection)
    }

    suspend fun sendPanelNotification(
        userId: Long,
        notificationType: Notifications.PanelNotificationType,
        properties: JsonObject = JsonObject(),
        sqlConnection: SqlConnection
    ) {
        val panelNotification = PanelNotification(
            userId = userId,
            type = notificationType.name,
            properties = properties
        )

        databaseManager.panelNotificationDao.add(panelNotification, sqlConnection)
    }

    suspend fun sendPanelNotification(
        userId: Long,
        notificationType: Notifications.PanelNotificationType,
        properties: JsonObject = JsonObject()
    ) {
        val sqlConnection = databaseManager.createConnection()

        sendPanelNotification(userId, notificationType, properties, sqlConnection)

        databaseManager.closeConnection(sqlConnection)
    }

    suspend fun sendNotificationToAll(
        userIdList: List<Long>,
        notificationType: Notifications.UserNotificationType,
        properties: JsonObject = JsonObject(),
        sqlConnection: SqlConnection
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

        databaseManager.notificationDao.addAll(notifications, sqlConnection)
    }

    suspend fun sendNotificationToAll(
        userIdList: List<Long>,
        notificationType: Notifications.UserNotificationType,
        properties: JsonObject = JsonObject()
    ) {
        val sqlConnection = databaseManager.createConnection()

        sendNotificationToAll(userIdList, notificationType, properties, sqlConnection)

        databaseManager.closeConnection(sqlConnection)
    }

    suspend fun sendPanelNotificationToAll(
        userIdList: List<Long>,
        notificationType: Notifications.PanelNotificationType,
        properties: JsonObject = JsonObject(),
        sqlConnection: SqlConnection
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

        databaseManager.panelNotificationDao.addAll(panelNotifications, sqlConnection)
    }

    suspend fun sendPanelNotificationToAll(
        userIdList: List<Long>,
        notificationType: Notifications.PanelNotificationType,
        properties: JsonObject = JsonObject()
    ) {
        val sqlConnection = databaseManager.createConnection()

        sendPanelNotificationToAll(userIdList, notificationType, properties, sqlConnection)

        databaseManager.closeConnection(sqlConnection)
    }

    suspend fun sendNotificationToAllAdmins(
        notificationType: Notifications.PanelNotificationType,
        properties: JsonObject = JsonObject(),
        sqlConnection: SqlConnection
    ) {
        val adminList = authProvider.getAdminList(sqlConnection)

        val adminUserIdList = adminList.map { username ->
            databaseManager.userDao.getUserIdFromUsername(username, sqlConnection)!!
        }

        sendPanelNotificationToAll(adminUserIdList, notificationType, properties, sqlConnection)
    }

    suspend fun sendNotificationToAllAdmins(
        notificationType: Notifications.PanelNotificationType,
        properties: JsonObject = JsonObject()
    ) {
        val sqlConnection = databaseManager.createConnection()

        sendNotificationToAllAdmins(notificationType, properties, sqlConnection)

        databaseManager.closeConnection(sqlConnection)
    }

}