package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.AppConstants
import com.panomc.platform.PluginEventManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.event.PlayerEventListener
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.User
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.util.PasswordHasher
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLBuilder
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.sqlclient.Tuple
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.sql.DriverManager

@Endpoint
class PanelAuthMeMigrationImportAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val configManager: ConfigManager,
    private val tokenProvider: TokenProvider,
    private val permissionManager: PermissionManager,
    private val vertx: Vertx
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/migration/authme/import", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("usernames", arraySchema().items(stringSchema()))
                    .optionalProperty("deleteUsernames", arraySchema().items(stringSchema()))
                    .optionalProperty("passwordStrategy", stringSchema())
                    .optionalProperty("existingUserUpdates", objectSchema())
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        if (!authProvider.hasPermission(ManagePlatformSettingsPermission(), context)) {
            throw NoPermission()
        }

        val body = context.body().asJsonObject()
        val usernamesToImport = body.getJsonArray("usernames").map { it as String }.toSet()
        val usernamesToDelete = body.getJsonArray("deleteUsernames")?.map { it as String }?.toSet() ?: emptySet()
        val passwordStrategy = body.getString("passwordStrategy") ?: "reset" // 'hash' or 'reset'
        val existingUserUpdates = body.getJsonObject("existingUserUpdates")
        val updatePassword = existingUserUpdates?.getBoolean("password") ?: false
        val updateUsername = existingUserUpdates?.getBoolean("username") ?: false
        val updateEmail = existingUserUpdates?.getBoolean("email") ?: false

        if (usernamesToImport.isEmpty() && usernamesToDelete.isEmpty()) {
            throw InvalidData(extras = mapOf("message" to "No users selected for import or deletion"))
        }

        // Load saved config from temp
        val tempConfigPath = AppConstants.TEMP_FOLDER + File.separator + "authme_migration_config.yml"
        val configFile = File(tempConfigPath)

        if (!configFile.exists()) {
            throw InvalidData(extras = mapOf("message" to "Migration session expired. Please upload files again."))
        }

        val configText = configFile.readText()
        val yaml = Yaml()

        @Suppress("UNCHECKED_CAST")
        val configMap: Map<String, Any> = yaml.load(configText) as Map<String, Any>

        val dataSource = getNestedMap(configMap, "DataSource")
            ?: throw InvalidData(extras = mapOf("message" to "DataSource section not found"))

        val backend = (dataSource["backend"] as? String)?.uppercase() ?: "SQLITE"
        val tableName = (dataSource["mySQLTablename"] as? String) ?: "authme"

        // Column names from config
        // AuthMe stores column names inside mySQLColumnGroup (or mySQLColumnName as a map).
        // IMPORTANT: Do NOT fall back to dataSource-level keys like "mySQLPassword" or "mySQLUsername"
        // because those are DB CONNECTION credentials, not column names!
        val columns = getNestedMap(dataSource, "mySQLColumnGroup")
            ?: getNestedMap(dataSource, "mySQLColumnName")

        val usernameColumn = getNestedValue(columns, "mySQLRealName") as? String
            ?: (dataSource["mySQLRealName"] as? String)
            ?: "realname"
        val passwordColumn = getNestedValue(columns, "mySQLPassword") as? String
            ?: (dataSource["mySQLColumnPassword"] as? String)
            ?: "password"
        val ipColumn = getNestedValue(columns, "mySQLIp") as? String
            ?: (dataSource["mySQLColumnIp"] as? String)
            ?: "ip"
        val lastLoginColumn = getNestedValue(columns, "mySQLLastLogin") as? String
            ?: (dataSource["mySQLColumnLastLogin"] as? String)
            ?: "lastlogin"
        val registrationDateColumn = getNestedValue(columns, "mySQLRegisterDate") as? String
            ?: (dataSource["mySQLColumnRegisterDate"] as? String)
            ?: "regdate"
        val emailColumn = getNestedValue(columns, "mySQLEmail") as? String
            ?: (dataSource["mySQLColumnEmail"] as? String)
            ?: "email"
        val nameColumn = getNestedValue(columns, "mySQLColumnName") as? String
            ?: (dataSource["mySQLColumnName"] as? String)
            ?: "username"

        // Read AuthMe hash algorithm from config
        val securitySettings = getNestedMap(configMap, "settings")?.let { getNestedMap(it, "security") }
            ?: getNestedMap(configMap, "SecuritySettings")
        val authmeHashAlgorithm = (securitySettings?.get("hashAlgorithm") as? String
            ?: securitySettings?.get("passwordHash") as? String)?.uppercase()

        // Determine if AuthMe is using a Pano-supported hash algorithm
        val isSupportedHash = when (authmeHashAlgorithm) {
            "SHA256", "BCRYPT", "JBCRYPT", "MD5", "DOUBLEMD5", "SALTED2MD5", "ARGON2" -> true
            "PLAINTEXT" -> false  // plaintext passwords must not be stored as-is
            else -> false         // PBKDF2, XAUTH, WBB, IPB, PHPBB, WORDPRESS, MYBB, etc.
        }

        // Read users from AuthMe database
        val authmeUsers: List<Map<String, Any?>> = when (backend) {
            "SQLITE" -> {
                val tempDbPath = AppConstants.TEMP_FOLDER + File.separator + "authme_migration.db"

                if (!File(tempDbPath).exists()) {
                    throw InvalidData(extras = mapOf("message" to "Migration session expired. Please upload files again."))
                }

                readUsersFromSQLite(
                    tempDbPath, tableName,
                    nameColumn, usernameColumn, passwordColumn, ipColumn,
                    lastLoginColumn, registrationDateColumn, emailColumn
                )
            }

            "MYSQL", "MARIADB" -> {
                val host = (dataSource["mySQLHost"] as? String) ?: "localhost"
                val port = (dataSource["mySQLPort"] as? Number)?.toInt() ?: 3306
                val dbName = (dataSource["mySQLDatabase"] as? String) ?: "authme"
                val username = (dataSource["mySQLUsername"] as? String) ?: "root"
                val password = (dataSource["mySQLPassword"] as? String) ?: ""

                readUsersFromMySQL(
                    host, port, dbName, username, password, tableName,
                    nameColumn, usernameColumn, passwordColumn, ipColumn,
                    lastLoginColumn, registrationDateColumn, emailColumn
                )
            }

            else -> throw InvalidData(extras = mapOf("message" to "Unsupported backend: $backend"))
        }

        // Filter only selected users
        val filteredUsers = authmeUsers.filter { user ->
            val name = user["username"] as? String ?: return@filter false
            name in usernamesToImport
        }

        val sqlClient = getSqlClient()
        var importedCount = 0
        var updatedCount = 0
        var skippedCount = 0
        val errors = mutableListOf<Map<String, String>>()

        for (authmeUser in filteredUsers) {
            val username = authmeUser["username"] as? String ?: continue
            val realName = authmeUser["realName"] as? String ?: username
            val email = authmeUser["email"] as? String
            val ip = authmeUser["ip"] as? String ?: "0.0.0.0"
            val lastLogin = authmeUser["lastLogin"] as? Long ?: System.currentTimeMillis()
            val registrationDate = authmeUser["registrationDate"] as? Long ?: System.currentTimeMillis()
            val password = authmeUser["password"] as? String

            try {
                // Determine the password to use (shared logic for both new and existing users)
                val passwordToStore = if (!password.isNullOrEmpty()) {
                    if (isSupportedHash) {
                        val detected = PasswordHasher().detectAlgorithm(password)
                        if (detected != null) password else null
                    } else if (passwordStrategy == "hash") {
                        val defaultAlgorithm = PasswordHasher.Algorithm.fromString(
                            configManager.config.auth.passwordHashAlgorithm
                        )
                        PasswordHasher().hash(password, defaultAlgorithm)
                    } else {
                        null
                    }
                } else null

                // Check if user already exists (try username first, then realName as fallback)
                val existingUserId = databaseManager.userDao.getUserIdFromUsername(username, sqlClient)
                    ?: if (realName != username) databaseManager.userDao.getUserIdFromUsername(realName, sqlClient) else null

                if (existingUserId != null) {
                    // User exists — update selected fields if any checkbox is enabled
                    val hasUpdates = updatePassword || updateUsername || updateEmail
                    if (!hasUpdates) {
                        skippedCount++
                        continue
                    }

                    if (updatePassword && passwordToStore != null) {
                        databaseManager.userDao.setHashedPasswordById(existingUserId, passwordToStore, sqlClient)
                    }

                    if (updateUsername && realName != username) {
                        // realName from AuthMe may differ from the login username
                        databaseManager.userDao.setUsernameById(existingUserId, realName, sqlClient)
                    }

                    if (updateEmail && !email.isNullOrEmpty() && email != "your@email.com") {
                        databaseManager.userDao.setEmailById(existingUserId, email, sqlClient)
                    }

                    updatedCount++
                    continue
                }

                // Also check if email already exists (if email is provided)
                if (!email.isNullOrEmpty()) {
                    val emailExists = databaseManager.userDao.isEmailExists(email, sqlClient)
                    if (emailExists) {
                        skippedCount++
                        errors.add(
                            mapOf(
                                "username" to realName,
                                "error" to "Email already exists: $email"
                            )
                        )
                        continue
                    }
                }

                val user = User(
                    username = realName,
                    email = if (!email.isNullOrEmpty() && email != "your@email.com") email else null,
                    registeredIp = ip,
                    registerDate = registrationDate,
                    lastLoginDate = lastLogin,
                    emailVerified = false
                )

                databaseManager.userDao.add(user, passwordToStore, sqlClient, false)

                importedCount++
            } catch (e: Exception) {
                skippedCount++
                errors.add(
                    mapOf(
                        "username" to realName,
                        "error" to (e.message ?: "Unknown error")
                    )
                )
            }
        }

        // Delete Pano-only users if requested (with full cleanup like PanelDeletePlayerAPI)
        var deletedCount = 0
        for (usernameToDelete in usernamesToDelete) {
            try {
                val userId = databaseManager.userDao.getUserIdFromUsername(usernameToDelete, sqlClient)

                if (userId != null) {
                    val user = databaseManager.userDao.getById(userId, sqlClient)

                    if (user != null) {
                        // Fire plugin event hooks
                        PluginEventManager.getPanoEventListeners<PlayerEventListener>().forEach { eventHandler ->
                            eventHandler.onDelete(user)
                        }
                    }

                    // Invalidate tokens
                    tokenProvider.invalidateTokensBySubject(userId.toString(), sqlClient)

                    // Clean up notifications
                    databaseManager.notificationDao.deleteAllByUserId(userId, sqlClient)
                    databaseManager.panelNotificationDao.deleteAllByUserId(userId, sqlClient)

                    // Update posts to anonymous
                    databaseManager.postDao.updateUserIdByUserId(userId, -1, sqlClient)

                    // Clean up ban history
                    databaseManager.banHistoryDao.deleteByUserId(userId, sqlClient)

                    // Clean up permissions
                    databaseManager.permissionNodeDao.deleteByUserId(userId, sqlClient)

                    // Clean up tickets
                    val tickets = databaseManager.ticketDao.getByUserId(userId, sqlClient)
                    val ticketIdList = JsonArray(tickets.map { it.id })
                    if (ticketIdList.size() != 0) {
                        databaseManager.ticketMessageDao.deleteByTicketIdList(ticketIdList, sqlClient)
                        databaseManager.ticketDao.delete(ticketIdList, sqlClient)
                    }

                    // Clean up panel data
                    databaseManager.panelActivityLogDao.deleteByUserId(userId, sqlClient)
                    databaseManager.panelConfigDao.deleteByUserId(userId, sqlClient)

                    // Finally delete the user
                    databaseManager.userDao.deleteById(userId, sqlClient)

                    deletedCount++
                }
            } catch (e: Exception) {
                errors.add(
                    mapOf(
                        "username" to usernameToDelete,
                        "error" to "Delete failed: ${e.message ?: "Unknown error"}"
                    )
                )
            }
        }

        // Refresh permission cache if any users were deleted
        if (deletedCount > 0) {
            permissionManager.refresh()
        }

        // Cleanup temp files
        try {
            File(AppConstants.TEMP_FOLDER + File.separator + "authme_migration.db").delete()
            File(AppConstants.TEMP_FOLDER + File.separator + "authme_migration_config.yml").delete()
        } catch (_: Exception) {
        }

        return Successful(
            mapOf(
                "imported" to importedCount,
                "updated" to updatedCount,
                "skipped" to skippedCount,
                "deleted" to deletedCount,
                "errors" to errors
            )
        )
    }

    private suspend fun readUsersFromSQLite(
        dbPath: String,
        tableName: String,
        nameColumn: String,
        realNameColumn: String,
        passwordColumn: String,
        ipColumn: String,
        lastLoginColumn: String,
        registrationDateColumn: String,
        emailColumn: String
    ): List<Map<String, Any?>> {
        return vertx.executeBlocking {
            val users = mutableListOf<Map<String, Any?>>()
            val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")

            conn.use { connection ->
                val stmt = connection.createStatement()
                val rs = stmt.executeQuery("SELECT * FROM `$tableName`")

                while (rs.next()) {
                    val username = tryGetString(rs, nameColumn)
                    val realName = tryGetString(rs, realNameColumn)
                    val password = tryGetString(rs, passwordColumn)
                    val ip = tryGetString(rs, ipColumn)
                    val lastLogin = tryGetLong(rs, lastLoginColumn)
                    val registrationDate = tryGetLong(rs, registrationDateColumn)
                    val email = tryGetString(rs, emailColumn)

                    if (username != null) {
                        users.add(
                            mapOf(
                                "username" to username,
                                "realName" to (realName ?: username),
                                "password" to password,
                                "ip" to ip,
                                "lastLogin" to (lastLogin ?: 0L),
                                "registrationDate" to (registrationDate ?: 0L),
                                "email" to email
                            )
                        )
                    }
                }
            }

            users
        }.coAwait()
    }

    private suspend fun readUsersFromMySQL(
        host: String,
        port: Int,
        dbName: String,
        username: String,
        password: String,
        tableName: String,
        nameColumn: String,
        realNameColumn: String,
        passwordColumn: String,
        ipColumn: String,
        lastLoginColumn: String,
        registrationDateColumn: String,
        emailColumn: String
    ): List<Map<String, Any?>> {
        val connectOptions = MySQLConnectOptions()
            .setHost(host)
            .setPort(port)
            .setDatabase(dbName)
            .setUser(username)
            .setPassword(password)

        val poolOptions = io.vertx.sqlclient.PoolOptions().setMaxSize(1)

        val client = MySQLBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(vertx)
            .build()

        return try {
            val rows = client
                .preparedQuery("SELECT * FROM `$tableName`")
                .execute(Tuple.tuple())
                .coAwait()

            val users = mutableListOf<Map<String, Any?>>()
            for (row in rows) {
                val name = try { row.getString(nameColumn) } catch (e: Exception) { null }
                val realName = try { row.getString(realNameColumn) } catch (e: Exception) { null }
                val pwd = try { row.getString(passwordColumn) } catch (e: Exception) { null }
                val ip = try { row.getString(ipColumn) } catch (e: Exception) { null }
                val lastLogin = try { row.getLong(lastLoginColumn) } catch (e: Exception) { null }
                val registrationDate = try { row.getLong(registrationDateColumn) } catch (e: Exception) { null }
                val email = try { row.getString(emailColumn) } catch (e: Exception) { null }

                users.add(
                    mapOf(
                        "username" to (name ?: "unknown"),
                        "realName" to (realName ?: name ?: "unknown"),
                        "password" to pwd,
                        "ip" to ip,
                        "lastLogin" to (lastLogin ?: 0L),
                        "registrationDate" to (registrationDate ?: 0L),
                        "email" to email
                    )
                )
            }
            users
        } finally {
            client.close().coAwait()
        }
    }

    private fun tryGetString(rs: java.sql.ResultSet, column: String): String? {
        return try {
            rs.getString(column)
        } catch (e: Exception) {
            null
        }
    }

    private fun tryGetLong(rs: java.sql.ResultSet, column: String): Long? {
        return try {
            val value = rs.getLong(column)
            if (rs.wasNull()) null else value
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getNestedMap(map: Map<String, Any>?, key: String): Map<String, Any>? {
        return map?.get(key) as? Map<String, Any>
    }

    private fun getNestedValue(map: Map<String, Any>?, key: String): Any? {
        return map?.get(key)
    }
}
