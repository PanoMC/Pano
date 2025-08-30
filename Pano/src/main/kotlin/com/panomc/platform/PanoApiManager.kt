package com.panomc.platform

import com.panomc.platform.InstallManager.Companion.ResourceType
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageAddonsPermission
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.error.*
import com.panomc.platform.model.Error
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import com.panomc.platform.util.HashUtil
import com.panomc.platform.util.KeyGeneratorUtil
import com.panomc.platform.util.TimeUtil.getCurrentTimeStamp
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.file.OpenOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.HttpRequest
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.kotlin.coroutines.coAwait
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import kotlin.io.path.absolutePathString

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PanoApiManager(
    private val configManager: ConfigManager,
    private val webClient: WebClient,
    private val pluginManager: PluginManager,
    private val installManager: InstallManager,
    applicationContext: ApplicationContext,
    private val vertx: Vertx,
    private val authProvider: AuthProvider
) {
    companion object {
        private const val HEADER_PREFIX = "Bearer "
    }

    private val uiManager: UIManager by lazy {
        applicationContext.getBean(UIManager::class.java)
    }

    private fun getPanoAccountConfig() = configManager.config.panoAccount

    fun isConnected(): Boolean {
        val panoAccountConfig = getPanoAccountConfig()

        return panoAccountConfig.accessToken.isNotBlank()
    }

    fun isConnecting(): Boolean {
        val panoAccountConfig = getPanoAccountConfig()

        return panoAccountConfig.connect != null
    }

    fun createRequest(httpMethod: HttpMethod, uri: String): HttpRequest<Buffer> {
        val panoApiUrl = configManager.config.panoApiUrl

        val request = webClient
            .requestAbs(httpMethod, panoApiUrl + uri)

        if (isConnected()) {
            val panoAccountConfig = getPanoAccountConfig()

            request.putHeader("Authorization", HEADER_PREFIX + panoAccountConfig.accessToken)
        }

        return request
    }

    suspend fun connectPlatform(encodedData: String, state: String): Triple<String, String, String> {
        if (isConnected()) {
            throw AlreadyConnectedToPano()
        }

        if (!isConnecting()) {
            throw PanoConnectFailed()
        }

        val panoAccountConfig = getPanoAccountConfig()

        val connectJsonObject = panoAccountConfig.connect!!
        val connectState = connectJsonObject.state

        if (state != connectState) {
            throw PanoConnectFailed()
        }

        val decoder = Base64.getDecoder()

        val encodedPrivateKey = connectJsonObject.privateKey
        val decodedPrivateKey = decoder.decode(encodedPrivateKey)

        val keySpec = PKCS8EncodedKeySpec(decodedPrivateKey)
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(keySpec)

        val decryptedData: String

        try {
            val decodedData = Base64.getDecoder().decode(encodedData)

            decryptedData = HashUtil.decryptData(decodedData, privateKey)
        } catch (e: Exception) {
            throw PanoConnectFailed()
        }

        val requestBody = JsonObject()

        requestBody.put("code", decryptedData)
        requestBody.put("version", Main.VERSION)

        val username: String
        val email: String
        val platformId: String

        try {
            val authorizeResponse = createRequest(HttpMethod.POST, "/platform/authorize")
                .sendJson(requestBody)
                .coAwait()

            val responseBody = authorizeResponse.bodyAsJsonObject()

            if (responseBody.getString("result") != "ok") {
                throw PanoConnectFailed()
            }

            val responseData = responseBody.getJsonObject("data")

            val jwt = responseData.getString("jwt")
            platformId = responseData.getString("id")
            username = responseData.getString("username")
            email = responseData.getString("email")

            panoAccountConfig.accessToken = jwt
            panoAccountConfig.platformId = platformId
            panoAccountConfig.username = username
            panoAccountConfig.email = email

            panoAccountConfig.connect = null

            configManager.saveConfig()

        } catch (e: Exception) {
            throw PanoConnectFailed()
        }

        return Triple(username, email, platformId)
    }

    suspend fun disconnectPlatform() {
        if (!isConnected()) {
            return
        }

        try {
            val response = createRequest(HttpMethod.POST, "/platform/api/disconnect")
                .send()
                .coAwait()

            if (response.statusCode() != 200) {
                throw PanoDisconnectFailed()
            }
        } catch (_: Exception) {
            throw PanoConnectFailed()
        }

        val panoAccountConfig = getPanoAccountConfig()

        panoAccountConfig.accessToken = ""
        panoAccountConfig.platformId = ""
        panoAccountConfig.username = ""
        panoAccountConfig.email = ""

        panoAccountConfig.connect = null

        configManager.saveConfig()
    }

    fun createPanoCode(): Pair<String, String> {
        if (isConnected()) {
            throw AlreadyConnectedToPano()
        }

        val keyPair = KeyGeneratorUtil.generateKeyPair()
        val base64Encoder = Base64.getEncoder()

        val publicKey = String(base64Encoder.encode(keyPair.public.encoded))
        val privateKey = String(base64Encoder.encode(keyPair.private.encoded))

        val state = UUID.randomUUID()

        val panoAccountConfig = getPanoAccountConfig()

        val connectConfig = PanoConfig.Companion.PanoAccountConnectConfig(publicKey, privateKey, state.toString())

        panoAccountConfig.connect = connectConfig

        configManager.saveConfig()

        return Pair(publicKey, state.toString())
    }

    suspend fun getStoreAuthorizeToken(): Pair<String, String> {
        if (!isConnected()) {
            throw PanoNotConnected()
        }

        try {
            val authorizeResponse = createRequest(HttpMethod.GET, "/platform/api/store/authorize/token")
                .send()
                .coAwait()

            val responseBody = authorizeResponse.bodyAsJsonObject()

            if (authorizeResponse.statusCode() == 401) {
                throw PanoNotConnected()
            }

            if (authorizeResponse.statusCode() != 200) {
                throw PanoConnectFailed()
            }

            val responseData = responseBody.getJsonObject("data")

            val base64Encoder = Base64.getEncoder()

            val token = String(base64Encoder.encode(responseData.getString("token").toByteArray(Charsets.UTF_8)))
            val state = responseData.getString("state")

            return Pair(token, state)
        } catch (_: Exception) {
            throw PanoConnectFailed()
        }
    }

    suspend fun updatePlatformMetadata() {
        if (!isConnected()) {
            throw PanoNotConnected()
        }

        val resources = mutableListOf<ResourceObj>()

        resources.addAll(pluginManager.plugins.map { plugin ->
            ResourceObj(
                plugin.pluginId,
                plugin.descriptor.version,
                ResourceType.PLUGIN
            )
        })

        resources.addAll(uiManager.installedThemeList.map { theme ->
            ResourceObj(
                theme.id,
                theme.version,
                ResourceType.THEME
            )
        })

        val request = JsonObject(
            mapOf(
                "version" to Main.VERSION,
                "resources" to resources
            )
        )

        try {
            val response = createRequest(HttpMethod.PUT, "/platform/api/metadata")
                .sendJson(request)
                .coAwait()

            if (response.statusCode() == 401) {
                throw PanoNotConnected()
            }

            if (response.statusCode() != 200) {
                throw PanoConnectFailed()
            }
        } catch (_: Exception) {
            throw PanoConnectFailed()
        }
    }

    suspend fun getVersionInfo(versionId: UUID): JsonObject {
        val response: HttpResponse<Buffer>
        val data: JsonObject

        try {
            response = createRequest(HttpMethod.GET, "/platform/api/store/versions/${versionId}")
                .send()
                .coAwait()

            val responseBody = response.bodyAsJsonObject()
            data = responseBody.getJsonObject("data")
        } catch (_: Exception) {
            throw PanoConnectFailed()
        }

        if (response.statusCode() == 401) {
            throw PanoNotConnected()
        }

        if (response.statusCode() == 404) {
            throw NotFound()
        }

        return data
    }

    suspend fun getUpdates(): JsonArray? {
        val response: HttpResponse<Buffer>
        val data: JsonArray?

        try {
            response = createRequest(HttpMethod.GET, "/platform/api/store/resources/versions")
                .send()
                .coAwait()

            val responseBody = response.bodyAsJsonObject()
            data = responseBody.getJsonArray("data")
        } catch (_: Exception) {
            throw PanoConnectFailed()
        }

        if (response.statusCode() == 401) {
            throw PanoNotConnected()
        }

        return data
    }

    suspend fun downloadResourceVersionFromStore(
        context: RoutingContext,
        versionId: UUID,
        progressHandler: (result: Result) -> Unit
    ): Map<String, Any?> {
        val versionInfo = getVersionInfo(versionId)
        val versionType = ResourceType.valueOf(versionInfo.getString("type"))

        if (versionType == ResourceType.PLUGIN) {
            authProvider.requirePermission(ManageAddonsPermission(), context)
        } else {
            authProvider.requirePermission(ManageViewPermission(), context)
        }

        val resourceFolderPath =
            if (versionType == ResourceType.PLUGIN) pluginManager.pluginsRoot.absolutePathString() else File(
                AppConstants.THEMES_FOLDER_PATH
            ).absolutePath

        val tempFolder = File(AppConstants.TEMP_FOLDER)

        if (!tempFolder.exists() || !tempFolder.isDirectory) {
            tempFolder.deleteRecursively()
            tempFolder.mkdirs()
        }

        progressHandler.invoke(Successful()) // Getting version info success

        val fileSystem = vertx.fileSystem()
        val temporaryFilePath = AppConstants.TEMP_FOLDER + File.separator + "pano-download_" + getCurrentTimeStamp()
        val writeStream = fileSystem.openBlocking(
            temporaryFilePath,
            OpenOptions().setWrite(true).setCreate(true).setTruncateExisting(true)
        )

        val getFileResponse = createRequest(HttpMethod.GET, "/platform/api/store/versions/${versionId}/file")
            .`as`(BodyCodec.pipe(writeStream))
            .send()
            .coAwait()

        if (getFileResponse.statusCode() != 200) {
            throw PanoConnectFailed()
        }

        progressHandler.invoke(Successful()) // Downloading file success

        val contentDisposition = getFileResponse.getHeader("Content-Disposition")
        val defaultFileName = if (versionType == ResourceType.PLUGIN) {
            "plugin"
        } else {
            "theme"
        } + "_${getCurrentTimeStamp()}"

        val fileName = contentDisposition
            ?.let {
                val regex = Regex("filename=\"?([^\";]+)\"?")
                regex.find(it)?.groups?.get(1)?.value
            } ?: defaultFileName

        val resourceFolder = File(resourceFolderPath)

        if (!resourceFolder.exists() || !resourceFolder.isDirectory) {
            resourceFolder.deleteRecursively()
            resourceFolder.mkdirs()
        }

        val newFilePath = resourceFolderPath + File.separator + fileName
        fileSystem.moveBlocking(temporaryFilePath, newFilePath)

        val hash = versionInfo.getString("hash")
        val verified = versionInfo.getBoolean("verified")
        val file = File(newFilePath)

        return mapOf(
            "hash" to hash,
            "verified" to verified,
            "file" to file,
            "versionType" to versionType
        )
    }

    suspend fun installResourceFromStore(
        context: RoutingContext,
        versionId: UUID,
        progressHandler: (result: Result) -> Unit
    ) {
        try {
            val downloadResult = downloadResourceVersionFromStore(context, versionId) {
                progressHandler.invoke(it)
            }
            val file = downloadResult["file"] as File
            val hash = downloadResult["hash"] as String
            val verified = downloadResult["verified"] as Boolean
            val versionType = downloadResult["versionType"] as ResourceType

            val userId = authProvider.getUserIdFromRoutingContext(context)

            installManager.installResource(userId, hash, verified, file, versionType) {
                progressHandler.invoke(it)

                if (it is Error) {
                    file.delete()
                }
            }
        } catch (e: Error) {
            progressHandler.invoke(e)
        } catch (e: Exception) {
            progressHandler.invoke(FailedToInstallResource(extras = mapOf("message" to e.message)))
        }
    }

    private data class ResourceObj(val id: String, val version: String, val type: ResourceType)
}