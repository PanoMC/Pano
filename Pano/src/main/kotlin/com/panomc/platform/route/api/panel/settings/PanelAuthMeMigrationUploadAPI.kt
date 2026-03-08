package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.AppConstants
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import com.panomc.platform.util.PasswordHasher
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLBuilder
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.sqlclient.Tuple
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.sql.DriverManager

@Endpoint
class PanelAuthMeMigrationUploadAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val configManager: ConfigManager,
    private val vertx: Vertx
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/migration/authme/upload", RouteType.POST))

    override fun bodyHandler(): Handler<RoutingContext> =
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            .setBodyLimit(50 * 1024 * 1024) // 50MB

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        if (!authProvider.hasPermission(ManagePlatformSettingsPermission(), context)) {
            throw NoPermission()
        }

        val fileUploads = context.fileUploads()

        val configUpload = fileUploads.find { it.name() == "config" }
            ?: throw InvalidData(extras = mapOf("message" to "config.yml file is required"))

        val configFile = File(configUpload.uploadedFileName())
        val configText = configFile.readText()

        // Parse AuthMe config.yml
        val yaml = Yaml()
        val configMap: Map<String, Any> = try {
            @Suppress("UNCHECKED_CAST")
            yaml.load(configText) as Map<String, Any>
        } catch (e: Exception) {
            throw InvalidData(extras = mapOf("message" to "Invalid YAML config file"))
        }

        // Extract DataSource settings
        val dataSource = getNestedMap(configMap, "DataSource")
            ?: throw InvalidData(extras = mapOf("message" to "DataSource section not found in config"))

        val backend = (dataSource["backend"] as? String)?.uppercase() ?: "SQLITE"
        val tableName = context.request().getFormAttribute("dbTable")?.takeIf { it.isNotBlank() }
            ?: (dataSource["mySQLTablename"] as? String) ?: "authme"

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
        // AuthMe stores it in: settings.security.hashAlgorithm or SecuritySettings.passwordHash
        val securitySettings = getNestedMap(configMap, "settings")?.let { getNestedMap(it, "security") }
            ?: getNestedMap(configMap, "SecuritySettings")
        val authmeHashAlgorithm = (securitySettings?.get("hashAlgorithm") as? String
            ?: securitySettings?.get("passwordHash") as? String)?.uppercase()

        // Read users from AuthMe database
        val authmeUsers: List<Map<String, Any?>> = when (backend) {
            "SQLITE" -> {
                val dbUpload = fileUploads.find { it.name() == "database" }
                    ?: throw InvalidData(extras = mapOf("message" to "SQLite database file is required for SQLite backend"))

                val dbFile = File(dbUpload.uploadedFileName())

                // Save db file temporarily for later import
                val tempFolder = File(AppConstants.TEMP_FOLDER)
                if (!tempFolder.exists()) tempFolder.mkdirs()

                val tempDbPath = AppConstants.TEMP_FOLDER + File.separator + "authme_migration.db"
                dbFile.copyTo(File(tempDbPath), true)

                // Also save config for later import
                val tempConfigPath = AppConstants.TEMP_FOLDER + File.separator + "authme_migration_config.yml"
                configFile.copyTo(File(tempConfigPath), true)

                readUsersFromSQLite(
                    tempDbPath, tableName,
                    nameColumn, usernameColumn, passwordColumn, ipColumn,
                    lastLoginColumn, registrationDateColumn, emailColumn
                )
            }

            "MYSQL", "MARIADB" -> {
                // Use form overrides if provided, otherwise fall back to config values
                val host = context.request().getFormAttribute("dbHost")?.takeIf { it.isNotBlank() }
                    ?: (dataSource["mySQLHost"] as? String) ?: "localhost"
                val port = context.request().getFormAttribute("dbPort")?.takeIf { it.isNotBlank() }?.toIntOrNull()
                    ?: (dataSource["mySQLPort"] as? Number)?.toInt() ?: 3306
                val dbName = context.request().getFormAttribute("dbName")?.takeIf { it.isNotBlank() }
                    ?: (dataSource["mySQLDatabase"] as? String) ?: "authme"
                val username = context.request().getFormAttribute("dbUser")?.takeIf { it.isNotBlank() }
                    ?: (dataSource["mySQLUsername"] as? String) ?: "root"
                val password = context.request().getFormAttribute("dbPassword")
                    ?: (dataSource["mySQLPassword"] as? String) ?: ""

                // Save config for later import
                val tempFolder = File(AppConstants.TEMP_FOLDER)
                if (!tempFolder.exists()) tempFolder.mkdirs()
                val tempConfigPath = AppConstants.TEMP_FOLDER + File.separator + "authme_migration_config.yml"
                configFile.copyTo(File(tempConfigPath), true)

                readUsersFromMySQL(
                    host, port, dbName, username, password, tableName,
                    nameColumn, usernameColumn, passwordColumn, ipColumn,
                    lastLoginColumn, registrationDateColumn, emailColumn
                )
            }

            else -> throw InvalidData(extras = mapOf("message" to "Unsupported backend: $backend"))
        }

        // Check each user against Pano database
        val sqlClient = getSqlClient()
        val previewUsers = mutableListOf<Map<String, Any?>>()
        var newCount = 0
        var existingCount = 0

        for (authmeUser in authmeUsers) {
            val username = authmeUser["username"] as? String ?: continue
            val email = authmeUser["email"] as? String

            val existsInPano = databaseManager.userDao.existsByUsername(username, sqlClient)

            val status = if (existsInPano) {
                existingCount++
                "existing"
            } else {
                newCount++
                "new"
            }

            val passwordStr = authmeUser["password"] as? String
            val passwordType = if (!passwordStr.isNullOrEmpty()) {
                // First try to detect from the hash string itself (structural formats)
                val detected = try {
                    PasswordHasher().detectAlgorithm(passwordStr)
                } catch (_: Exception) {
                    null
                }

                if (detected != null) {
                    detected.name
                } else {
                    // Fall back to AuthMe config's hashAlgorithm setting
                    mapAuthmeAlgorithm(authmeHashAlgorithm)
                }
            } else null

            previewUsers.add(
                mapOf(
                    "username" to username,
                    "realName" to (authmeUser["realName"] ?: username),
                    "email" to (email ?: ""),
                    "ip" to (authmeUser["ip"] ?: ""),
                    "lastLogin" to (authmeUser["lastLogin"] ?: 0L),
                    "registrationDate" to (authmeUser["registrationDate"] ?: 0L),
                    "status" to status,
                    "hasPassword" to (!passwordStr.isNullOrEmpty()),
                    "passwordType" to passwordType
                )
            )
        }

        // Find Pano users that are NOT in AuthMe (matched by username OR email)
        val authmeUsernames = authmeUsers.mapNotNull { it["username"] as? String }.map { it.lowercase() }.toSet()
        val authmeEmails = authmeUsers.mapNotNull { it["email"] as? String }
            .filter { it.isNotEmpty() && it != "your@email.com" }
            .map { it.lowercase() }
            .toSet()

        val allPanoIds = databaseManager.userDao.getAllIds(sqlClient)
        val allPanoUsers = if (allPanoIds.isNotEmpty()) {
            databaseManager.userDao.getAllByIds(allPanoIds, sqlClient)
        } else {
            emptyList()
        }

        val panoOnlyUsers = allPanoUsers
            .filter { panoUser ->
                val usernameMatch = panoUser.username.lowercase() in authmeUsernames
                val emailMatch = !panoUser.email.isNullOrEmpty() && panoUser.email.lowercase() in authmeEmails
                !usernameMatch && !emailMatch
            }
            .map { panoUser -> mapOf("id" to panoUser.id, "username" to panoUser.username, "email" to (panoUser.email ?: "")) }

        return Successful(
            mapOf(
                "users" to previewUsers,
                "totalCount" to previewUsers.size,
                "newCount" to newCount,
                "existingCount" to existingCount,
                "backend" to backend,
                "panoOnlyUsers" to panoOnlyUsers,
                "panoOnlyCount" to panoOnlyUsers.size,
                "authmeHashAlgorithm" to (authmeHashAlgorithm ?: "UNKNOWN"),
                "defaultHashAlgorithm" to configManager.config.auth.passwordHashAlgorithm
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

        val client = try {
            MySQLBuilder.pool()
                .with(poolOptions)
                .connectingTo(connectOptions)
                .using(vertx)
                .build()
        } catch (e: Exception) {
            throw InvalidData(
                extras = mapOf(
                    "message" to "Failed to connect to MySQL/MariaDB at $host:$port/$dbName. " +
                            "Please check the database settings in your config.yml. Error: ${e.message}"
                )
            )
        }

        return try {
            val rows = try {
                client
                    .preparedQuery("SELECT * FROM `$tableName`")
                    .execute(Tuple.tuple())
                    .coAwait()
            } catch (e: Exception) {
                throw InvalidData(
                    extras = mapOf(
                        "message" to "Connected to $host:$port/$dbName but failed to read table '$tableName'. " +
                                "Please verify the table name in your config.yml. Error: ${e.message}"
                    )
                )
            }

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

    /**
     * Map AuthMe's hash algorithm name to a Pano-compatible type string.
     * AuthMe supports: SHA256, BCRYPT, MD5, ARGON2, PBKDF2, XAUTH,
     * JBCRYPT, WBB3, WBB4, IPB3, IPB4, PHPBB, WORDPRESS, MYBB, PLAINTEXT, etc.
     */
    private fun mapAuthmeAlgorithm(authmeAlgorithm: String?): String {
        return when (authmeAlgorithm?.uppercase()) {
            "SHA256" -> "SHA256"
            "BCRYPT", "JBCRYPT" -> "BCRYPT"
            "MD5", "DOUBLEMD5", "SALTED2MD5" -> "MD5"
            "ARGON2" -> "ARGON2ID"
            "PLAINTEXT" -> "PLAINTEXT"
            null -> "UNKNOWN"
            else -> "UNKNOWN"  // PBKDF2, XAUTH, WBB, IPB, PHPBB, WORDPRESS, MYBB, etc.
        }
    }

    override suspend fun getFailureHandler(context: RoutingContext) {
        if (context.failure() == null) {
            throw InvalidData(extras = mapOf("message" to "Invalid upload"))
        }
    }
}
