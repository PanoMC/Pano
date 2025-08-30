package com.panomc.platform.service.impl

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import com.panomc.platform.service.PlayerService
import com.panomc.platform.util.PlayerStatus
import io.vertx.core.json.JsonObject
import org.springframework.stereotype.Service
import kotlin.math.ceil

@Service
class PlayerServiceImpl(private val databaseManager: DatabaseManager) : PlayerService {
    override suspend fun getPlayers(playerStatus: PlayerStatus, page: Long, permissionGroupName: String?): Result {
        val sqlClient = databaseManager.getSqlClient()

        var permissionGroup: PermissionGroup? = null

        if (permissionGroupName != null && permissionGroupName != "-") {
            val isTherePermission =
                databaseManager.permissionGroupDao.isThereByName(permissionGroupName, sqlClient)

            if (!isTherePermission) {
                throw NotExists()
            }

            val permissionGroupId =
                databaseManager.permissionGroupDao.getPermissionGroupIdByName(permissionGroupName, sqlClient)!!

            permissionGroup = PermissionGroup(permissionGroupId, permissionGroupName)
        }

        if (permissionGroupName != null && permissionGroupName == "-") {
            permissionGroup = PermissionGroup(name = "-")
        }

        val count =
            if (permissionGroup != null)
                databaseManager.userDao.getCountOfUsersByPermissionGroupId(permissionGroup.id, sqlClient)
            else
                databaseManager.userDao.countByStatus(playerStatus, sqlClient)

        var totalPage = ceil(count.toDouble() / 10).toLong()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw PageNotFound()
        }

        val userList =
            if (permissionGroup != null)
                databaseManager.userDao.getAllByPageAndPermissionGroup(page, permissionGroup.id, sqlClient)
            else
                databaseManager.userDao.getAllByPageAndStatus(page, playerStatus, sqlClient)


        val result = mutableMapOf<String, Any?>(
            "playerCount" to count,
            "totalPage" to totalPage
        )

        if (permissionGroup != null) {
            result["permissionGroup"] = permissionGroup
        }

        if (userList.isEmpty()) {
            return Successful(result)
        }

        val permissionGroupIdList = userList.map { it.permissionGroupId }
        val userIdList = userList.map { it.id }
        val usernameList = userList.map { it.username }
        val permissions = databaseManager.permissionGroupDao.byListOfId(permissionGroupIdList, sqlClient)
        val userIdTicketCountMap = databaseManager.ticketDao.countByUserIdList(userIdList, sqlClient)
        val usernameInGameMap = databaseManager.serverPlayerDao.existsByUsernameList(usernameList, sqlClient)

        result["players"] = userList.map {
            val user = JsonObject.mapFrom(it)

            user.put("isBanned", it.banned)
            user.put("inGame", usernameInGameMap[it.username])
            user.put("permissionGroup", permissions[it.permissionGroupId]?.name ?: "-")
            user.put("ticketCount", userIdTicketCountMap[it.id])
            user.put("isEmailVerified", it.emailVerified)

            user.remove("password")
            user.remove("banned")

            user
        }

        return Successful(result)
    }
}